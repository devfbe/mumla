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

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.capture.CapturePreprocessor
import se.lublin.humla.audio.capture.CapturePreprocessorFactory
import se.lublin.humla.audio.capture.CaptureRequest
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.FarEndFrameChunker
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.PcmCaptureSource
import se.lublin.humla.audio.capture.PcmCaptureSourceFactory
import se.lublin.humla.audio.capture.Resampler
import se.lublin.humla.audio.capture.RnnoisePreprocessor
import se.lublin.humla.audio.capture.SpeexResampler
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.routing.AndroidCommunicationDevices
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.CommunicationDevices
import se.lublin.humla.util.HumlaLog
import kotlin.math.log10
import kotlin.math.roundToInt

private const val TAG = "DoubleTalkSelfTest"
private const val FRAME = AudioHandler.FRAME_SIZE
private const val FRAMES_PER_SECOND = 100
private const val JOIN_TIMEOUT_MS = 500L
private const val DEFAULT_READING_INTERVAL_FRAMES = 5

/** Six seconds of the voice alone before the user is asked to talk over it. */
private const val DEFAULT_LISTEN_FRAMES = 6 * FRAMES_PER_SECOND

/**
 * The first second of every listening phase is not counted: AEC3 converges during the first one,
 * and after a strength change the gate may still hold the user's last words open.
 */
private const val LISTEN_SETTLE_FRAMES = FRAMES_PER_SECOND

/** How long after a voiced frame of the test voice its echo counts as still in the room: 500 ms. */
private const val ECHO_TAIL_FRAMES = 50

/** A frame of the test voice is voiced when it is within 40 dB of the clip's loudest frame. */
private const val VOICED_RANGE_DB = 40f

/** Fewer counted frames than this (half a second) give no percentage: too little to say anything. */
private const val MIN_COUNTED_FRAMES = 50

private const val PERCENT = 100

/** Which half of the test runs: the voice alone first, then the user talking over it. */
public enum class SelfTestPhase { LISTEN, TALK }

/**
 * One reading of [DoubleTalkSelfTest], every few frames.
 *
 * @param meter what the final gate measured, as the level meter draws it; [MeterReading.voice] is
 *   the lamp: true while the app would transmit the user.
 * @param voicePlaying whether the test voice (or its echo) is in the room right now.
 * @param heardPercent in [SelfTestPhase.TALK], of the frames in which the test voice played and the
 *   echo canceller alone would have let the user through (the reference gate, see
 *   [DoubleTalkSelfTest]), the share the whole chain transmitted; null until enough frames count.
 * @param falseOpenPercent in [SelfTestPhase.LISTEN] (the user asked to stay quiet), the share of
 *   frames with the test voice playing on which the gate opened; null until enough frames count.
 */
public data class SelfTestReading(
    val meter: MeterReading,
    val phase: SelfTestPhase,
    val voicePlaying: Boolean,
    val heardPercent: Int?,
    val falseOpenPercent: Int?,
)

/**
 * The double-talk self-test: plays a test voice on the speaker the way a call plays a remote user,
 * while the user talks over it, and reports whether the app would transmit the user.
 *
 * Runs what a call runs, built from the same settings: `MODE_IN_COMMUNICATION` with the built-in
 * speaker as communication device, a playback track configured like `AudioOutput`'s on the
 * voice-call stream, every played frame handed to the echo canceller as its far-end reference
 * before the write ([FarEndFrameChunker], as `AudioOutput` does), and the capture chain
 * (AEC3 + AGC2, then the denoiser with the given attenuation limit) in front of the user's voice
 * gate. [stop] restores the audio mode and the communication device it found.
 *
 * Nobody knows when the user really spoke, so the result uses a proxy, and says so: a second,
 * adaptive voice gate on the signal after echo cancellation and before the denoiser (the
 * "reference gate"), which is what the app would transmit without noise suppression. The test first
 * asks the user to stay quiet for [listenFrames] ([SelfTestPhase.LISTEN]): any opening then is the
 * voice alone getting through. Afterwards ([SelfTestPhase.TALK]) it counts how often the whole
 * chain transmits while the voice plays and the reference gate hears the user.
 *
 * Two bare threads, playback and capture, both released by [stop]. Not reusable. [onReading] is
 * called on the capture thread.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The settings a call captures with, and the seams.
public class DoubleTalkSelfTest internal constructor(
    private val audioManager: AudioManager,
    private val devices: CommunicationDevices,
    private val vadConfig: VadConfig,
    private val noiseSuppression: NoiseSuppressionMode,
    private val speexNoiseSuppressDb: Int,
    private val echoCancellation: EchoCancellationMode,
    private val effects: AndroidAudioEffects,
    attenuationLimitDb: Float,
    clip: ShortArray,
    private val onReading: (SelfTestReading) -> Unit,
    private val captureFactory: PcmCaptureSourceFactory,
    private val sinkFactory: PcmPlaybackSinkFactory,
    private val preprocessorFactory: CapturePreprocessorFactory,
    private val resamplerFactory: (Int, Int) -> Resampler,
    private val readingIntervalFrames: Int = DEFAULT_READING_INTERVAL_FRAMES,
    private val listenFrames: Int = DEFAULT_LISTEN_FRAMES,
) {
    /**
     * [clip] is the test voice, 48 kHz mono, played in a loop. [attenuationLimitDb] is RNNoise's
     * limit, as in [PipelineSettings.rnnoiseAttenuationLimitDb]; [setAttenuationLimitDb] moves it.
     */
    public constructor(
        audioManager: AudioManager,
        vadConfig: VadConfig,
        noiseSuppression: NoiseSuppressionMode,
        speexNoiseSuppressDb: Int,
        echoCancellation: EchoCancellationMode,
        effects: AndroidAudioEffects,
        attenuationLimitDb: Float,
        clip: ShortArray,
        onReading: (SelfTestReading) -> Unit,
    ) : this(
        audioManager,
        AndroidCommunicationDevices(audioManager, Handler(Looper.getMainLooper())) {
            HumlaLog.w(TAG, "the platform refused to route to the speaker", it)
        },
        vadConfig, noiseSuppression, speexNoiseSuppressDb, echoCancellation, effects, attenuationLimitDb, clip,
        onReading, AndroidAudioRecordSource.Factory(), AndroidAudioTrackSink.CallFactory(),
        CapturePreprocessorFactory(log = { HumlaLog.w(TAG, it) }), { from, to -> SpeexResampler(from, to) },
    )

    init {
        require(attenuationLimitDb >= 0f) { "attenuation limit must be at least 0 dB" }
        require(clip.isNotEmpty()) { "the test voice is empty" }
    }

    /** The clip, padded with silence to whole frames. */
    private val voice: ShortArray = clip.copyOf((clip.size + FRAME - 1) / FRAME * FRAME)
    private val voiceFrames = voice.size / FRAME

    /** Per frame of [voice]: whether it, or its echo tail, is in the room while that frame plays. */
    private val voiceActive: BooleanArray = activeFrames(voice)

    private val detector = VoiceActivityDetector(vadConfig)
    private val referenceGate = ReferenceGate(
        if (vadConfig.mode == VadMode.ADAPTIVE) vadConfig else VadConfig.adaptive(),
    )

    @Volatile
    private var attenuationLimit: Float = attenuationLimitDb

    @Volatile
    private var rnnoise: RnnoisePreprocessor? = null

    private var source: PcmCaptureSource? = null
    private var sink: PcmPlaybackSink? = null
    private var pipeline: CapturePipeline? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var restore: Restore? = null

    @Volatile
    private var running = false

    /** The frame of [voice] the playback thread handed over last. */
    @Volatile
    private var playedFrame = 0

    @Volatile
    private var restartRequested = false

    // Written and read by the capture thread only.
    private var phase = SelfTestPhase.LISTEN
    private var phaseFrames = 0
    private var listened = 0
    private var falseOpens = 0
    private var talked = 0
    private var heard = 0

    /** What [start] changed and [stop] puts back. */
    private class Restore(val mode: Int, val device: CommunicationDevice?, val routed: Boolean)

    /** Whether the echo canceller runs; without it the voice goes back to whoever talks. */
    public val echoCancelled: Boolean get() = echoCancellation == EchoCancellationMode.WEBRTC

    /** The running RNNoise stage's limit, null without one; for tests. */
    internal val rnnoiseLimitDb: Float? get() = rnnoise?.attenuationLimitDb

    public fun start() {
        check(captureThread == null && restore == null) { "already started" }
        restore = route()
        var started = false
        try {
            val src = captureFactory.open(
                CaptureRequest(MediaRecorder.AudioSource.MIC, AudioHandler.SAMPLE_RATE, effects, echoCancellation),
            )
            source = src
            val chain = preprocessorFactory.create(
                noiseSuppression, echoCancellation, speexNoiseSuppressDb, attenuationLimit, referenceGate,
            )
            rnnoise = chain.rnnoise
            // A strength moved while the chain was being built must not be lost.
            chain.rnnoise?.attenuationLimitDb = attenuationLimit
            val rate = AudioHandler.SAMPLE_RATE
            val resampler = if (src.sampleRate != rate) resamplerFactory(src.sampleRate, rate) else null
            val pipe = CapturePipeline(resampler, chain.preprocessor, ActivityInputMode(detector), 1f, FRAME)
            pipeline = pipe
            val farEnd = chain.farEndSink?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
            val snk = sinkFactory.open(AudioManager.STREAM_VOICE_CALL, rate)
            sink = snk
            running = true
            playbackThread = Thread({ play(snk, farEnd) }, "humla-selftest-playback").also { it.start() }
            captureThread = Thread({ capture(src, pipe) }, "humla-selftest-capture").also { it.start() }
            started = true
        } finally {
            if (!started) stop()
        }
    }

    /** Moves RNNoise's limit while the test runs, and starts the measurement over for it. */
    public fun setAttenuationLimitDb(limitDb: Float) {
        require(limitDb >= 0f) { "attenuation limit must be at least 0 dB" }
        attenuationLimit = limitDb
        rnnoise?.attenuationLimitDb = limitDb
        restartRequested = true
    }

    /** Starts the measurement over, with the listening phase, keeping the audio running. */
    public fun restartMeasurement() {
        restartRequested = true
    }

    /** Stops both threads, releases the recorder, the track and the chain, and restores the route. */
    public fun stop() {
        running = false
        source?.stop()
        captureThread?.join(JOIN_TIMEOUT_MS)
        playbackThread?.join(JOIN_TIMEOUT_MS)
        captureThread = null
        playbackThread = null
        sink?.let {
            it.pause()
            it.flush()
            it.stop()
            it.release()
        }
        sink = null
        source?.release()
        source = null
        // After both threads are gone: the canceller is on both of them.
        pipeline?.release()
        pipeline = null
        rnnoise = null
        restore?.let(::unroute)
        restore = null
    }

    private fun route(): Restore {
        val saved = Restore(audioManager.mode, devices.current(), routed = false)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val speaker = devices.available().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val routed = speaker != null && devices.select(speaker.id)
        if (!routed) HumlaLog.w(TAG, "could not route to the speaker; playing on ${devices.current()?.type}")
        return Restore(saved.mode, saved.device, routed)
    }

    private fun unroute(saved: Restore) {
        if (saved.routed) {
            val previous = saved.device?.let { d -> devices.available().firstOrNull { it.id == d.id } }
            if (previous == null || !devices.select(previous.id)) devices.clear()
        }
        audioManager.mode = saved.mode
    }

    /** Feeds the canceller each frame before the blocking write, as `AudioOutput` does. */
    private fun play(snk: PcmPlaybackSink, farEnd: FarEndFrameChunker?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(FRAME)
        var index = 0
        snk.play()
        while (running) {
            System.arraycopy(voice, index * FRAME, frame, 0, FRAME)
            farEnd?.push(frame, FRAME)
            playedFrame = index
            if (snk.write(frame, FRAME) < 0) break
            if (++index == voiceFrames) index = 0
        }
    }

    private fun capture(src: PcmCaptureSource, pipe: CapturePipeline) {
        src.start()
        val frameSize = src.sampleRate / FRAMES_PER_SECOND
        val buffer = ShortArray(frameSize)
        var count = 0
        while (running) {
            val read = src.read(buffer, frameSize)
            if (read < 0) break
            if (read == 0) continue
            val transmit = pipe.process(buffer, read).transmit
            count(transmit, referenceGate.open, voiceActive[playedFrame])
            if (++count % readingIntervalFrames == 0) onReading(reading())
        }
        src.stop()
    }

    private fun count(transmit: Boolean, referenceOpen: Boolean, playing: Boolean) {
        if (restartRequested) {
            restartRequested = false
            phase = SelfTestPhase.LISTEN
            phaseFrames = 0
            listened = 0
            falseOpens = 0
            talked = 0
            heard = 0
        }
        phaseFrames++
        when (phase) {
            SelfTestPhase.LISTEN -> {
                if (phaseFrames > LISTEN_SETTLE_FRAMES && playing) {
                    listened++
                    if (transmit) falseOpens++
                }
                if (phaseFrames >= listenFrames) phase = SelfTestPhase.TALK
            }
            SelfTestPhase.TALK -> if (playing && referenceOpen) {
                talked++
                if (transmit) heard++
            }
        }
    }

    private fun reading() = SelfTestReading(
        meter = detector.meterReading(vadConfig),
        phase = phase,
        voicePlaying = voiceActive[playedFrame],
        heardPercent = percent(heard, talked),
        falseOpenPercent = percent(falseOpens, listened),
    )

    private fun percent(part: Int, whole: Int): Int? =
        if (whole < MIN_COUNTED_FRAMES) null else (PERCENT * part.toFloat() / whole).roundToInt()

    /**
     * The reference gate: an adaptive voice gate on the frame after echo cancellation and before the
     * denoiser, which leaves the frame alone and has no opinion on the chain's probability.
     */
    private class ReferenceGate(config: VadConfig) : CapturePreprocessor {
        private val gate = VoiceActivityDetector(config)

        @Volatile
        var open: Boolean = false
            private set

        override fun process(frame: ShortArray): Float? {
            open = gate.isVoice(frame, frame.size, null)
            return null
        }

        override fun release() = Unit
    }

    internal companion object {
        /**
         * Frame by frame, whether the test voice is in the room while that frame of [voice] plays:
         * voiced itself, or within [ECHO_TAIL_FRAMES] after a voiced frame (the echo and the room's
         * tail arrive late). Wraps around, because the clip loops.
         */
        fun activeFrames(voice: ShortArray): BooleanArray {
            val frames = voice.size / FRAME
            val levels = FloatArray(frames) { f ->
                var sum = 0.0
                for (i in f * FRAME until (f + 1) * FRAME) sum += voice[i].toDouble() * voice[i]
                (10 * log10(sum / FRAME / FULL_SCALE_SQUARED + TINY)).toFloat()
            }
            val loudest = levels.maxOrNull() ?: return BooleanArray(0)
            val voiced = BooleanArray(frames) { levels[it] > loudest - VOICED_RANGE_DB }
            return BooleanArray(frames) { f ->
                (0..minOf(ECHO_TAIL_FRAMES, frames - 1)).any { back -> voiced[(f - back).mod(frames)] }
            }
        }

        private const val FULL_SCALE_SQUARED = 32768.0 * 32768.0
        private const val TINY = 1e-12
    }
}
