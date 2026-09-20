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

package se.lublin.humla.audio.capture

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import se.lublin.humla.exception.AudioInitializationException
import java.util.concurrent.Executor

/**
 * Blocking 16-bit mono PCM capture. The one hardware edge of the capture path: everything above it
 * -- `AudioInput`, `CapturePipeline`, the preprocessor chain -- is testable on the host because
 * this interface can be faked.
 */
interface PcmCaptureSource {
    /** The rate the source really opened at, which is not necessarily the one that was asked for. */
    val sampleRate: Int

    /** The `AudioRecord` session the android audio effects are attached to. */
    val audioSessionId: Int

    fun start()

    /**
     * Blocking read of at most [length] samples into [buffer], starting at index 0.
     *
     * @return how many samples were written: a positive count, `0` when the source produced
     *   nothing, or a negative error code. **It may never exceed [length]**, and no guard above
     *   this interface checks that -- `AudioInput` pads from the returned count to the end of its
     *   frame, and a count past the end is an out-of-bounds write on the capture thread rather than
     *   an error code. Same precondition, and for the same reason, as `Resampler.resample`'s.
     */
    fun read(buffer: ShortArray, length: Int): Int

    /** Stops capture so a blocked [read] returns. Idempotent, and safe after [release]. */
    fun stop()

    /** Frees the native recorder. Idempotent. After it, [read] answers a negative code. */
    fun release()

    /**
     * @param listener called with `true` when the platform silences this client and `false` when it
     *   becomes audible again; `null` unregisters. Delivered on a platform thread, not the capture
     *   thread.
     */
    fun setSilenceListener(listener: ((Boolean) -> Unit)?)
}

/**
 * Everything the capture side needs to open a recorder. [audioSource] is a starting point rather
 * than a decision: [AudioSourcePolicy] may override it (spec B6).
 */
data class CaptureRequest(
    val audioSource: Int,
    val targetSampleRate: Int,
    val effects: AndroidAudioEffects = AndroidAudioEffects(),
    val echo: EchoCancellationMode = EchoCancellationMode.NONE,
    /** Routes capture to this device -- the SCO headset (spec B11) -- when set. */
    val preferredDevice: AudioDeviceInfo? = null,
)

fun interface PcmCaptureSourceFactory {
    @Throws(AudioInitializationException::class)
    fun open(request: CaptureRequest): PcmCaptureSource
}

/**
 * The real source: one `AudioRecord` plus the `android.media.audiofx` effects attached to its
 * session (spec B6).
 */
class AndroidAudioRecordSource internal constructor(
    internal val record: AudioRecord,
    internal val effects: List<AudioEffect>,
) : PcmCaptureSource {
    /**
     * Cached, not delegated. The Java original's `getSampleRate()` read the `AudioRecord` field on
     * every call and `shutdown()` set that field to null, so asking a shut-down `AudioInput` for its
     * rate was a `NullPointerException`; reading it off a *released* `AudioRecord` is worse.
     *
     * **Not pinnable here, and the mutation that would do it has been run**: `= record.sampleRate`
     * to `get() = record.sampleRate` leaves all 297 tests green, because Robolectric's
     * `AudioRecord.release()` leaves `mSampleRate` standing and its getter keeps answering. The
     * same property one level up **is** pinned -- `AudioInputTest.the rate and the frame size still
     * answer after shutdown`, over a fake whose accessor throws after release, kills the equivalent
     * mutation in `AudioInput`. What is unproven is only that a *released device* recorder still
     * answers, which is a statement about `AudioRecord`, not about this line.
     */
    override val sampleRate: Int = record.sampleRate
    override val audioSessionId: Int = record.audioSessionId

    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    /**
     * What this flag does and does not do, because the difference decides whether the comment is a
     * guarantee or a description (spec 4.04).
     *
     * It does: make every read **after** [release] answer [ERROR_RELEASED] without touching the
     * released object, so a capture thread that outlived `AudioInput`'s join timeout leaves its
     * loop by the error arm instead of hammering a freed recorder. Pinned by
     * `AndroidAudioRecordSourceTest.a read after release reports the released code`, which without
     * the flag reads `AudioRecord`'s own -3 instead.
     *
     * It does not: close the window on a read that is already **inside** `AudioRecord.read` when
     * [release] runs. That is a native use-after-free and a `@Volatile` cannot reach it; what closes
     * it is `AudioInput` joining the capture thread first, and spec B8 deliberately gives that join
     * a timeout ("a join timeout logs and releases anyway"). The residual risk is in the ledger.
     */
    @Volatile
    private var released = false

    override fun start() = record.startRecording()

    override fun read(buffer: ShortArray, length: Int): Int {
        if (released) return ERROR_RELEASED
        return record.read(buffer, 0, length)
    }

    override fun stop() {
        if (released) return
        record.stop()
    }

    /**
     * No `if (released) return` in front of this, deliberately. Everything below it is idempotent --
     * `AudioEffect.release()` is a no-op once its state is `STATE_UNINITIALIZED`, `AudioRecord`'s
     * swallows the `IllegalStateException` from its own `stop()` and its native side clears the
     * handle it already cleared -- so such a guard would be a line whose premise is false, which
     * spec 4.04 says to delete and replace with a test of the premise. That test is
     * `releasing twice is safe`.
     *
     * The `setSilenceListener(null)` below is **unpinned, and the mutation has been run**: deleting
     * it leaves all 297 tests green, because `ShadowAudioRecord` shadows neither
     * `registerAudioRecordingCallback` nor its counterpart, so `recordingCallback` is null in every
     * test and the call is a no-op there. It stays because `release()` is public and may be reached
     * without `AudioInput.stopRecording` having unregistered first, and because a platform callback
     * outliving the recorder it names is a leak on a device. A device test is the only thing that
     * could kill it.
     */
    override fun release() {
        released = true
        setSilenceListener(null)
        effects.forEach { it.release() }
        record.release()
    }

    override fun setSilenceListener(listener: ((Boolean) -> Unit)?) {
        recordingCallback?.let { record.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        if (listener == null) return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == audioSessionId } ?: return
                listener(mine.isClientSilenced)
            }
        }
        record.registerAudioRecordingCallback(Executor { it.run() }, callback)
        recordingCallback = callback
    }

    class Factory : PcmCaptureSourceFactory {
        // RECORD_AUDIO is requested by the app before it connects (spec P3); a library cannot
        // request it, and AudioHandler's constructor checks it before reaching this.
        @SuppressLint("MissingPermission")
        @Throws(AudioInitializationException::class)
        override fun open(request: CaptureRequest): PcmCaptureSource {
            val source = AudioSourcePolicy.resolve(request.audioSource, request.effects, request.echo)
            val rates = listOf(request.targetSampleRate) + SAMPLE_RATES.filter { it != request.targetSampleRate }
            val record = rates.firstNotNullOfOrNull { tryOpen(source, it) }
                ?: throw AudioInitializationException("Unable to open AudioRecord at any of $rates Hz")
            request.preferredDevice?.let { record.preferredDevice = it }
            Log.i(TAG, "capturing from source $source at ${record.sampleRate} Hz")
            return AndroidAudioRecordSource(record, attachEffects(record.audioSessionId, request))
        }

        @SuppressLint("MissingPermission")
        private fun tryOpen(source: Int, rate: Int): AudioRecord? {
            val minBufferSize =
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBufferSize <= 0) return null
            val record = try {
                AudioRecord(
                    source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
                )
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "no AudioRecord at $rate Hz: ${e.message}")
                return null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return record
        }

        /**
         * Spec B6. [EchoCancellationMode.WEBRTC] attaches **nothing** here on purpose: the
         * canceller is then ours, running inside the capture chain, and the platform one in front
         * of it would hand AEC3 a signal whose echo has already been altered by an unknown
         * algorithm -- the same cascade spec 4.1 refused for noise suppression.
         */
        private fun attachEffects(sessionId: Int, request: CaptureRequest): List<AudioEffect> {
            val attached = mutableListOf<AudioEffect>()
            fun attach(name: String, available: Boolean, create: () -> AudioEffect?) {
                if (!available) {
                    Log.w(TAG, "$name not available on this device")
                    return
                }
                val effect = create()
                if (effect == null) {
                    Log.w(TAG, "$name creation failed")
                    return
                }
                effect.enabled = true
                attached += effect
                Log.i(TAG, "$name enabled")
            }
            if (request.echo == EchoCancellationMode.ANDROID) {
                attach("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)
                }
            }
            if (request.effects.noiseSuppressor) {
                attach("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
            }
            if (request.effects.automaticGainControl) {
                attach("AutomaticGainControl", AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(sessionId)
                }
            }
            return attached
        }
    }

    companion object {
        private const val TAG = "AndroidAudioRecordSource"

        /** What [read] answers after [release]; `AudioInput` maps it like any other negative read. */
        const val ERROR_RELEASED = -100

        /** Probed in order after the requested rate, which is tried first. */
        @JvmField
        val SAMPLE_RATES = intArrayOf(48000, 44100, 16000, 8000)
    }
}
