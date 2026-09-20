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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.annotation.RequiresPermission
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.protocol.AudioHandler

/**
 * Created by andrew on 23/08/13.
 *
 * Mechanically converted from Java; behaviour is unchanged, including the defects the next commit
 * removes (unsynchronised start/stop, a non-volatile `recording` flag, an unbounded `join()`, a
 * `sampleRate` getter that dereferences a field `shutdown()` nulls out, and a listener that is
 * handed the whole reused buffer whatever the read count was).
 */
class AudioInput
@RequiresPermission(Manifest.permission.RECORD_AUDIO)
@Throws(AudioInitializationException::class)
constructor(
    private val listener: AudioInputListener,
    audioSource: Int,
    targetSampleRate: Int,
    private val echoCancellationMethod: String,
) : Runnable {
    fun interface AudioInputListener {
        fun onAudioInputReceived(frame: ShortArray, frameSize: Int)
    }

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private val mFrameSize: Int
    private var recordThread: Thread? = null
    private var recording = false

    init {
        // Attempt to construct an AudioRecord with the target sample rate first.
        // If it fails, keep producing AudioRecord instances until we find one that initializes
        // correctly. Maybe one day Android will let us probe for supported sample rates, as we
        // aren't even guaranteed that 44100hz will work across all devices.
        for (i in 0 until SAMPLE_RATES.size + 1) {
            val rate = if (i == 0) targetSampleRate else SAMPLE_RATES[i - 1]
            try {
                audioRecord = setupAudioRecord(rate, audioSource)
                if (enableEchoCancellation()) {
                    Log.w(TAG, "echo cancellation enabled: $echoCancellationMethod")
                }
                break
            } catch (e: AudioInitializationException) {
                // Continue iteration, probing for a supported sample rate.
            }
        }

        if (audioRecord == null) throw AudioInitializationException("Unable to initialize AudioInput.")

        // FIXME: does not work properly if 10ms frames cannot be represented as integers
        mFrameSize = (sampleRate * AudioHandler.FRAME_SIZE) / AudioHandler.SAMPLE_RATE
    }

    private fun enableEchoCancellation(): Boolean {
        if (echoCancellationMethod == "system" /* android.media.audiofx.AcousticEchoCanceler */) {
            if (!AcousticEchoCanceler.isAvailable()) {
                Log.e(TAG, "could not enable system AEC: not available")
                return false
            }
            aec?.release()
            val created = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
            if (created == null) {
                Log.e(TAG, "could not enable system AEC: create failed")
                return false
            }
            created.enabled = true
            aec = created
            return true
        } else if (echoCancellationMethod == "none") {
            Log.w(TAG, "echocancellation not enabled by user")
        } else {
            Log.w(TAG, "ignoring unknown echocancellation method: $echoCancellationMethod")
        }
        return false
    }

    /**
     * Starts the recording thread.
     * Not thread-safe.
     */
    fun startRecording() {
        recording = true
        recordThread = Thread(this).also { it.start() }
    }

    /**
     * Stops the record loop after the current iteration, joining it.
     * Not thread-safe.
     */
    fun stopRecording() {
        if (!recording) return
        recording = false
        try {
            recordThread?.interrupt()
            recordThread?.join()
            recordThread = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /**
     * Stops the record loop and waits on it to finish.
     * Releases native audio resources.
     * NOTE: It is not safe to call startRecording after.
     */
    fun shutdown() {
        stopRecording()
        if (audioRecord != null) {
            aec?.release()
            aec = null
            audioRecord?.release()
            audioRecord = null
        }
    }

    fun isRecording(): Boolean = recording

    /** @return the sample rate used by the AudioRecord instance. */
    val sampleRate: Int get() = audioRecord!!.sampleRate

    /** @return the frame size used, varying depending on the sample rate selected. */
    val frameSize: Int get() = mFrameSize

    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        Log.i(TAG, "started")

        val record = audioRecord ?: return
        record.startRecording()

        if (record.state != AudioRecord.STATE_INITIALIZED) return

        val audioBuffer = ShortArray(mFrameSize)
        // We loop when the 'recording' instance var is true instead of checking audio record state
        // because we want to always cleanly shutdown.
        while (recording) {
            val shortsRead = record.read(audioBuffer, 0, mFrameSize)
            if (shortsRead > 0) {
                listener.onAudioInputReceived(audioBuffer, mFrameSize)
            } else {
                Log.e(TAG, "Error fetching audio! AudioRecord error $shortsRead")
            }
        }

        record.stop()

        Log.i(TAG, "stopped")
    }

    companion object {
        private val TAG: String = AudioInput::class.java.name

        @JvmField
        val SAMPLE_RATES = intArrayOf(48000, 44100, 16000, 8000)

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        @Throws(AudioInitializationException::class)
        private fun setupAudioRecord(sampleRate: Int, audioSource: Int): AudioRecord {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                throw AudioInitializationException("Invalid buffer size returned (unsupported sample rate).")
            }

            val audioRecord = try {
                AudioRecord(
                    audioSource, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
                )
            } catch (e: IllegalArgumentException) {
                throw AudioInitializationException(e)
            }

            if (audioRecord.state == AudioRecord.STATE_UNINITIALIZED) {
                audioRecord.release()
                throw AudioInitializationException("AudioRecord failed to initialize!")
            }

            return audioRecord
        }
    }
}
