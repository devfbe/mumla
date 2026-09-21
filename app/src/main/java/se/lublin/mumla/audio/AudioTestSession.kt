/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.audio

import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.AudioSourcePolicy
import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.capture.CapturePreprocessorFactory
import se.lublin.humla.audio.capture.CaptureRequest
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.PcmCaptureSource
import se.lublin.humla.audio.capture.PcmCaptureSourceFactory
import se.lublin.humla.audio.capture.Resampler
import se.lublin.humla.audio.capture.SpeexResampler
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.protocol.AudioHandler

/**
 * One frame's worth of everything the level meter draws.
 *
 * All levels are dBFS, and `null` means *this mode has no such level* rather than zero -- the
 * speech-model mode compares a probability, which has no dBFS threshold at all, and drawing one
 * would be a mark the gate does not use.
 */
data class MeterReading(
    val levelDbfs: Float,
    val floorDbfs: Float?,
    val speechDbfs: Float?,
    val thresholdDbfs: Float?,
    /** True while the gate is open, i.e. while this frame would be transmitted. */
    val voice: Boolean,
    /** True while the gate is open **only** because of the hold -- the middle of the three zones. */
    val holding: Boolean,
    /** True while the talker is not far enough above the room for the gate to do its job. */
    val tooClose: Boolean,
)

/**
 * Spec B10: a short-lived capture session for the settings screen.
 *
 * It runs **the same pipeline the service runs**, from the same factories with the same settings,
 * so the meter is a measurement of what the microphone will actually do rather than a second
 * opinion about it. It reports one [MeterReading] every [readingIntervalFrames] frames and, with
 * [loopback] on, plays back the frames that would have been transmitted.
 *
 * One bare thread, released by [stop]. Not reusable: [start] twice throws.
 *
 * **While a call is running this takes the microphone.** `SettingsActivity` does not bind
 * `MumlaService` (no `bindService` and no `IHumlaService` in it), so the fragment has no cheap way
 * to ask whether capture is in progress, and adding that binding belongs to the core stream. On
 * API 31+ the newer client wins and the service's capture is silenced while the screen is open;
 * `onPause` stops this session, the service's `AudioRecordingCallback` reports
 * `CaptureState.Silenced`, and the B7 retry re-opens capture two seconds later.
 */
class AudioTestSession(
    private val audioManager: AudioManager,
    private val vadConfig: VadConfig,
    private val noiseSuppression: NoiseSuppressionMode,
    private val speexNoiseSuppressDb: Int,
    private val echoCancellation: EchoCancellationMode,
    private val effects: AndroidAudioEffects,
    private val loopback: Boolean,
    private val onReading: (MeterReading) -> Unit,
    private val readingIntervalFrames: Int = DEFAULT_READING_INTERVAL_FRAMES,
    private val captureFactory: PcmCaptureSourceFactory = AndroidAudioRecordSource.Factory(),
    private val sinkFactory: PcmPlaybackSinkFactory = AndroidAudioTrackSink.Factory(),
    private val preprocessorFactory: CapturePreprocessorFactory =
        CapturePreprocessorFactory(log = { Log.w(TAG, it) }),
    private val resamplerFactory: (Int, Int) -> Resampler = { from, to -> SpeexResampler(from, to) },
) {
    private var source: PcmCaptureSource? = null
    private var sink: PcmPlaybackSink? = null
    private var pipeline: CapturePipeline? = null
    private var thread: Thread? = null
    private val detector = VoiceActivityDetector(vadConfig)

    /** True while this session put the device into communication mode and still owes a restore. */
    private var ownsCommunicationMode = false

    @Volatile
    private var running = false

    /** Forgets what the tracker has learned, which is the screen's "measure again". */
    fun recalibrate() = detector.recalibrate()

    @Throws(AudioInitializationException::class)
    fun start() {
        check(thread == null) { "already started" }
        // Spec B6: the preview has to route capture exactly the way the service will, or the meter
        // and the loopback describe a capture configuration that is not the one being calibrated.
        if (AudioSourcePolicy.needsCommunicationMode(effects, echoCancellation)) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsCommunicationMode = true
        }
        val src = captureFactory.open(
            CaptureRequest(MediaRecorder.AudioSource.MIC, AudioHandler.SAMPLE_RATE, effects, echoCancellation)
        )
        try {
            val chain = preprocessorFactory.create(noiseSuppression, echoCancellation, speexNoiseSuppressDb)
            val resampler =
                if (src.sampleRate != AudioHandler.SAMPLE_RATE) resamplerFactory(src.sampleRate, AudioHandler.SAMPLE_RATE)
                else null
            val pipe = CapturePipeline(
                resampler, chain.preprocessor, ActivityInputMode(detector), 1f, AudioHandler.FRAME_SIZE,
            )
            val snk = if (loopback) sinkFactory.open(AudioManager.STREAM_MUSIC, AudioHandler.SAMPLE_RATE) else null
            source = src
            sink = snk
            pipeline = pipe
            running = true
            thread = Thread({ loop(src, snk, pipe) }, THREAD_NAME).also { it.start() }
        } catch (e: Throwable) {
            // The recorder is open at this point and nothing else holds it yet, so an exception on
            // the way to the thread would leave the microphone taken for the life of the process.
            src.release()
            restoreCommunicationMode()
            throw e
        }
    }

    private fun loop(src: PcmCaptureSource, snk: PcmPlaybackSink?, pipe: CapturePipeline) {
        src.start()
        snk?.play()
        val frameSize = src.sampleRate / 100
        val buffer = ShortArray(frameSize)
        val silence = ShortArray(AudioHandler.FRAME_SIZE)
        var count = 0
        while (running) {
            val read = src.read(buffer, frameSize)
            if (read < 0) break
            if (read == 0) continue
            val frame = pipe.process(buffer, read)
            if (++count % readingIntervalFrames == 0) onReading(currentReading())
            snk?.write(if (frame.transmit) frame.samples else silence, frame.length)
        }
        src.stop()
    }

    /**
     * The reading, taken off the **same** detector the gate just used. There is deliberately no
     * second measurement of the frame anywhere in this class: two readings of one quantity is how
     * a meter ends up showing a number the gate does not act on.
     */
    private fun currentReading(): MeterReading {
        val level = detector.lastLevelDbfs
        val voice = detector.isTalking
        return when (vadConfig.mode) {
            VadMode.ADAPTIVE -> {
                val threshold = detector.thresholdDbfs
                MeterReading(
                    levelDbfs = level,
                    floorDbfs = detector.floorDbfs,
                    speechDbfs = detector.speechDbfs,
                    thresholdDbfs = threshold,
                    voice = voice,
                    holding = voice && level < threshold,
                    tooClose = detector.tooClose,
                )
            }
            VadMode.AMPLITUDE -> {
                // The legacy slider is a score on the 96 dB curve; this is that score as a level,
                // so the mark the user drags and the mark the meter draws are the same number.
                val threshold = (vadConfig.startThreshold - 1f) * 96f
                MeterReading(
                    levelDbfs = level,
                    floorDbfs = null,
                    speechDbfs = null,
                    thresholdDbfs = threshold,
                    voice = voice,
                    holding = voice && level < threshold,
                    tooClose = false,
                )
            }
            VadMode.PROBABILITY -> MeterReading(
                levelDbfs = level,
                floorDbfs = null,
                speechDbfs = null,
                thresholdDbfs = null,
                voice = voice,
                holding = false,
                tooClose = false,
            )
        }
    }

    fun stop() {
        running = false
        source?.stop()
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        sink?.let {
            it.pause()
            it.flush()
            it.stop()
            it.release()
        }
        sink = null
        source?.release()
        source = null
        pipeline?.release()
        pipeline = null
        restoreCommunicationMode()
    }

    private fun restoreCommunicationMode() {
        if (!ownsCommunicationMode) return
        audioManager.mode = AudioManager.MODE_NORMAL
        ownsCommunicationMode = false
    }

    companion object {
        private const val TAG = "AudioTestSession"
        private const val THREAD_NAME = "mumla-audio-test"
        /**
         * **Half of `AudioInput`'s, because this join happens on the main thread.** `stop()` is
         * called from `onPause` and from every settings change, and this project already has an
         * ANR complaint against it. There is nothing for the capture thread to do but notice
         * `running == false`, and `source.stop()` has already unblocked its read; if it has not
         * returned within half a second the recorder is wedged and waiting longer will not free
         * it. Same ruling as spec B8's join timeout, one level down: log and release anyway.
         */
        private const val JOIN_TIMEOUT_MS = 500L

        /** Five frames is 50 ms, i.e. 20 readings a second -- above what a bar can show anyway. */
        const val DEFAULT_READING_INTERVAL_FRAMES = 5
    }
}
