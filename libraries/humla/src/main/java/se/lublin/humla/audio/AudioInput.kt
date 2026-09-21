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
 * Owns the capture thread -- the one bare `Thread` the audio path is allowed (spec section 2) --
 * and pumps 10 ms frames from a [PcmCaptureSource] to [listener]. Nothing else: the recorder, the
 * android audio effects and the device routing live behind the source, and the preprocessing,
 * detection and encoding above the listener.
 *
 * **Thread ownership, because this class is nothing but that.** [startRecording], [stopRecording]
 * and [shutdown] are `synchronized` on this instance and are the only writers of [thread];
 * [recording] is `@Volatile` because the capture thread reads it every frame while another thread
 * writes it. `AudioHandler` additionally wraps its own calls in `synchronized (mInput)`, which is
 * the same monitor and reentrant. Nothing the capture thread touches takes this monitor, so the
 * bounded join inside it cannot deadlock against the loop -- that ordering is the reason
 * [listener] is called outside any lock this class holds.
 *
 * **Three corrections to the Java original, all of them race conditions it had from the start:**
 * - `mRecording` was a plain `boolean` read by the capture thread and written by the main one.
 * - `stopRecording()` joined **without a timeout** while `AudioHandler` held a lock the UI thread
 *   wants, so one wedged `AudioRecord.read` froze the app rather than one thread. Spec B8 asks for
 *   `stop()` before `join(2000)` and for the timeout to log and carry on.
 * - `getSampleRate()` read `mAudioRecord.getSampleRate()` and `shutdown()` set `mAudioRecord` to
 *   null, so the getter threw for every caller after a disconnect. The rate is read once now.
 */
class AudioInput(
    private val listener: AudioInputListener,
    private val source: PcmCaptureSource,
    private val stateListener: ((CaptureState) -> Unit)? = null,
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
) {
    /**
     * The bridge `AudioHandler.java:138` still calls. It dies with that file in task 11; until then
     * it is the only production caller and the reason the source seam has a real implementation
     * behind it at all.
     */
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
         * @param frameSize always the whole buffer. A read that produced fewer samples is padded
         *   with silence rather than shortened, because the encoder needs a full frame -- see the
         *   padding in [loop].
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
     * @throws IllegalStateException if a capture thread is still alive. Two threads reading one
     *   source is not a recoverable state, and [isRecording] cannot be used to rule it out: that
     *   flag is about intent and is already false when [stopRecording] has given up on a join.
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
     * Stops the source first -- which is what makes a blocked read return -- then interrupts and
     * joins with a bound. The interrupt is not for the read (a native one ignores it) but for
     * whatever the listener is blocked on: `ToggleInputMode.waitForInput` parks the capture thread
     * until the talk key is pressed, and without the interrupt the loop never comes back to notice
     * that [recording] is false.
     *
     * @return whether the capture thread really exited. `false` means it is still running against
     *   a source [shutdown] is about to release, and the caller must not free native state the
     *   capture path still reaches (spec B8: the timeout "logs and releases anyway", which is a
     *   decision about the recorder, not a licence for everything downstream of it).
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
            // Our caller is being cancelled. Do not swallow it the way the Java original did:
            // every frame above this one has to be able to see it too.
            Thread.currentThread().interrupt()
        }
        val exited = !t.isAlive
        if (!exited) Log.e(TAG, "capture thread still running after $joinTimeoutMs ms; releasing anyway")
        thread = if (exited) null else t
        source.setSilenceListener(null)
        return exited
    }

    /** @return what [stopRecording] answered; the source is released either way (spec B8). */
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
                // A read racing our own shutdown answers a negative code because the recorder is
                // gone. That is not news, and AudioHandler puts every CaptureState.Error in front
                // of the user (stream A8), so reporting it means a warning on every disconnect.
                if (recording) {
                    Log.e(TAG, "capture read error $read")
                    stateListener?.invoke(CaptureState.Error("capture read error $read"))
                }
                break
            }
            // Nothing was read, so there is nothing to deliver: a blocking AudioRecord answers 0
            // when it is no longer recording, and the padding below would turn that into 10 ms of
            // silence the microphone never produced.
            if (read == 0) continue
            // The buffer is allocated once, so after a short read its tail is the previous frame --
            // audio that was already sent, going out again. No `if (read < frameSize)` in front of
            // this: `fill` over an empty range is a no-op, and a cost guard here would be a branch
            // no test can tell from its absence.
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
