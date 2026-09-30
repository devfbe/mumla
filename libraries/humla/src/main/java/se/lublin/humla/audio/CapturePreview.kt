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

package se.lublin.humla.audio

import android.media.AudioManager
import android.media.MediaRecorder
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
import se.lublin.humla.util.HumlaLog

/** Capture runs in 10 ms frames. */
private const val FRAMES_PER_SECOND = 100

private const val TAG = "CapturePreview"
private const val THREAD_NAME = "humla-capture-preview"

/**
 * Half of `AudioInput`'s, because this join happens on the main thread (`onPause` and every
 * settings change). `source.stop()` has already unblocked the read; if the thread has not
 * returned by then the recorder is wedged, so log and release anyway.
 */
private const val JOIN_TIMEOUT_MS = 500L

/** Five frames is 50 ms, i.e. 20 readings a second -- above what a bar can show anyway. */
private const val DEFAULT_READING_INTERVAL_FRAMES = 5

/**
 * One frame's worth of everything the level meter draws. Levels are dBFS; `null` means the mode
 * has no such level (the speech-model mode compares a probability, not a dBFS threshold).
 */
public data class MeterReading(
    public val levelDbfs: Float,
    public val floorDbfs: Float?,
    public val speechDbfs: Float?,
    public val thresholdDbfs: Float?,
    /** True while the gate is open, i.e. while this frame would be transmitted. */
    public val voice: Boolean,
    /** True while the gate is open only because of the hold. */
    public val holding: Boolean,
    /** True while the talker is not far enough above the room for the gate to do its job. */
    public val tooClose: Boolean,
)

/**
 * A short-lived capture of the microphone for a settings screen's level meter.
 *
 * Runs the same pipeline a session runs, from the same factories and settings, so the meter shows
 * what the microphone will actually do. Reports one [MeterReading] every [readingIntervalFrames]
 * frames and, with [loopback] on, plays back the frames that would have been transmitted.
 * One bare thread, released by [stop]. Not reusable: [start] twice throws.
 *
 * While a call is running this takes the microphone from its session: on API 31+ the newer client
 * wins and the session's capture reports `CaptureState.Silenced` (published as the session's
 * `captureSilenced`). Nothing re-opens it: the platform lets the session's recorder be heard again
 * once this preview has stopped.
 */
@Suppress("LongParameterList") // The settings a session captures with, and the platform seams.
public class CapturePreview internal constructor(
    private val audioManager: AudioManager,
    private val vadConfig: VadConfig,
    private val noiseSuppression: NoiseSuppressionMode,
    private val speexNoiseSuppressDb: Int,
    private val echoCancellation: EchoCancellationMode,
    private val effects: AndroidAudioEffects,
    private val loopback: Boolean,
    private val onReading: (MeterReading) -> Unit,
    private val readingIntervalFrames: Int,
    private val captureFactory: PcmCaptureSourceFactory,
    private val sinkFactory: PcmPlaybackSinkFactory,
    private val preprocessorFactory: CapturePreprocessorFactory,
    private val resamplerFactory: (Int, Int) -> Resampler,
) {
    /**
     * [onReading] is called on the capture thread. [captureFactory] and [sinkFactory] replace the
     * microphone and the speaker.
     */
    public constructor(
        audioManager: AudioManager,
        vadConfig: VadConfig,
        noiseSuppression: NoiseSuppressionMode,
        speexNoiseSuppressDb: Int,
        echoCancellation: EchoCancellationMode,
        effects: AndroidAudioEffects,
        loopback: Boolean,
        onReading: (MeterReading) -> Unit,
        captureFactory: PcmCaptureSourceFactory = AndroidAudioRecordSource.Factory(),
        sinkFactory: PcmPlaybackSinkFactory = AndroidAudioTrackSink.Factory(),
    ) : this(
        audioManager, vadConfig, noiseSuppression, speexNoiseSuppressDb, echoCancellation, effects, loopback,
        onReading, DEFAULT_READING_INTERVAL_FRAMES, captureFactory, sinkFactory,
        CapturePreprocessorFactory(log = { HumlaLog.w(TAG, it) }), { from, to -> SpeexResampler(from, to) },
    )

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
    public fun recalibrate(): Unit = detector.recalibrate()

    public fun start() {
        check(thread == null) { "already started" }
        // Route capture exactly like a session will, or the preview calibrates a different setup.
        if (AudioSourcePolicy.needsCommunicationMode(effects, echoCancellation)) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsCommunicationMode = true
        }
        val src = captureFactory.open(
            CaptureRequest(MediaRecorder.AudioSource.MIC, AudioHandler.SAMPLE_RATE, effects, echoCancellation)
        )
        var started = false
        try {
            val chain = preprocessorFactory.create(noiseSuppression, echoCancellation, speexNoiseSuppressDb)
            val rate = AudioHandler.SAMPLE_RATE
            val resampler = if (src.sampleRate != rate) resamplerFactory(src.sampleRate, rate) else null
            val pipe = CapturePipeline(
                resampler, chain.preprocessor, ActivityInputMode(detector), 1f, AudioHandler.FRAME_SIZE,
            )
            val snk = if (loopback) sinkFactory.open(AudioManager.STREAM_MUSIC, AudioHandler.SAMPLE_RATE) else null
            source = src
            sink = snk
            pipeline = pipe
            running = true
            thread = Thread({ loop(src, snk, pipe) }, THREAD_NAME).also { it.start() }
            started = true
        } finally {
            // The recorder is open and nothing else holds it, so a failure here would leak it.
            if (!started) {
                src.release()
                restoreCommunicationMode()
            }
        }
    }

    private fun loop(src: PcmCaptureSource, snk: PcmPlaybackSink?, pipe: CapturePipeline) {
        src.start()
        snk?.play()
        val frameSize = src.sampleRate / FRAMES_PER_SECOND
        val buffer = ShortArray(frameSize)
        val silence = ShortArray(AudioHandler.FRAME_SIZE)
        var count = 0
        while (running) {
            val read = src.read(buffer, frameSize)
            if (read < 0) break
            if (read > 0) {
                val frame = pipe.process(buffer, read)
                if (++count % readingIntervalFrames == 0) onReading(currentReading())
                snk?.write(if (frame.transmit) frame.samples else silence, frame.length)
            }
        }
        src.stop()
    }

    /**
     * The reading, taken off the same detector the gate just used, so the meter never shows a
     * number the gate does not act on.
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
                val threshold = VoiceActivityDetector.scoreToDbfs(vadConfig.startThreshold)
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

    public fun stop() {
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
}
