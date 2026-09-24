/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.Manifest
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.CaptureRequest
import se.lublin.humla.audio.capture.CaptureState
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.PcmCaptureSource
import se.lublin.humla.exception.AudioInitializationException

/**
 * Owns the capture thread and pumps 10 ms frames from a [PcmCaptureSource] to [listener].
 *
 * [startRecording], [stopRecording] and [shutdown] are `synchronized` on this instance and are the
 * only writers of [thread]. The capture thread never takes this monitor and [listener] is called
 * outside it, so the bounded join in [stopRecording] cannot deadlock against the loop.
 */
class AudioInput(
    private val listener: AudioInputListener,
    private val source: PcmCaptureSource,
    private val stateListener: ((CaptureState) -> Unit)? = null,
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
) {
    /** Opens an [AndroidAudioRecordSource] for [audioSource] at [targetSampleRate]. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Throws(AudioInitializationException::class)
    constructor(
        listener: AudioInputListener,
        audioSource: Int,
        targetSampleRate: Int,
        echoCancellationMethod: String,
        effects: AndroidAudioEffects = AndroidAudioEffects(),
    ) : this(
        listener,
        AndroidAudioRecordSource.Factory().open(
            CaptureRequest(
                audioSource = audioSource,
                targetSampleRate = targetSampleRate,
                effects = effects,
                echo = EchoCancellationMode.fromPreferenceValue(echoCancellationMethod),
            ),
        ),
    )

    fun interface AudioInputListener {
        /**
         * @param frame the **reused** capture buffer: valid until this call returns, never kept.
         * @param frameSize always the whole buffer; short reads are padded with silence because
         *   the encoder needs a full frame.
         */
        fun onAudioInputReceived(frame: ShortArray, frameSize: Int)
    }

    /** The rate the source really opened at, cached before anything can release it. */
    val sampleRate: Int = source.sampleRate

    /** Samples per 10 ms at [sampleRate]. */
    val frameSize: Int = sampleRate / FRAMES_PER_SECOND

    @Volatile
    private var recording = false

    private var thread: Thread? = null

    /**
     * @throws IllegalStateException if a capture thread from a previous run (e.g. after a timed-out
     *   join) is still alive.
     */
    @Synchronized
    fun startRecording() {
        check(thread?.isAlive != true) { "the capture thread from the previous run is still alive" }
        recording = true
        source.setSilenceListener { silenced ->
            stateListener?.invoke(if (silenced) CaptureState.Silenced else CaptureState.Active)
        }
        thread = Thread(::loop, THREAD_NAME).also { it.start() }
    }

    /**
     * Stops the source (which unblocks a pending read), then interrupts and joins with a bound. The
     * interrupt wakes a listener parked in `ToggleInputMode.waitForInput`.
     *
     * @return whether the capture thread really exited. `false` means it may still reach native
     *   state downstream, so the caller must not free it.
     */
    @Synchronized
    fun stopRecording(): Boolean {
        val t = thread ?: return true
        recording = false
        source.stop()
        t.interrupt()
        try {
            t.join(joinTimeoutMs)
        } catch (e: InterruptedException) {
            // Restore the flag so callers further up see the cancellation.
            Thread.currentThread().interrupt()
        }
        val exited = !t.isAlive
        if (!exited) Log.e(TAG, "capture thread still running after $joinTimeoutMs ms; releasing anyway")
        thread = if (exited) null else t
        source.setSilenceListener(null)
        return exited
    }

    /** @return what [stopRecording] answered; the source is released either way. */
    @Synchronized
    fun shutdown(): Boolean {
        val exited = stopRecording()
        source.release()
        return exited
    }

    /** Whether capture is *meant* to be running. A timed-out join leaves this false and a thread alive. */
    fun isRecording(): Boolean = recording

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            source.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "capture could not be started", e)
            stateListener?.invoke(CaptureState.Error("capture could not be started: ${e.message}"))
            return
        }
        Log.i(TAG, "capturing at $sampleRate Hz in frames of $frameSize")
        val buffer = ShortArray(frameSize)
        while (recording) {
            val read = source.read(buffer, frameSize)
            if (read < 0) {
                // A read racing our own shutdown fails too; don't report that to the user.
                if (recording) {
                    Log.e(TAG, "capture read error $read")
                    stateListener?.invoke(CaptureState.Error("capture read error $read"))
                }
                break
            }
            // A stopped AudioRecord answers 0; padding it would invent 10 ms of silence.
            if (read == 0) continue
            // The buffer is reused: clear the stale tail after a short read.
            buffer.fill(0, read, frameSize)
            listener.onAudioInputReceived(buffer, frameSize)
        }
        source.stop()
        Log.i(TAG, "capture stopped")
    }

    companion object {
        private const val TAG = "AudioInput"
        private const val THREAD_NAME = "humla-capture"
        private const val FRAMES_PER_SECOND = 100
        const val DEFAULT_JOIN_TIMEOUT_MS = 2000L
    }
}
