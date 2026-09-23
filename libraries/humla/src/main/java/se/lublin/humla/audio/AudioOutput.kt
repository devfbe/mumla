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

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.Arrays
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import se.lublin.humla.audio.capture.FarEndFrameChunker
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

/**
 * Created by andrew on 16/07/13.
 *
 * @param farEnd where every mixed buffer is handed over a second time, on its way to the
 *               speaker. It belongs to this thread: [FarEndFrameChunker] is not
 *               thread-safe and one chunker serves one playback thread, while the sink behind
 *               it takes the one lock that also covers the capture thread.
 */
class AudioOutput(
    private val listener: AudioOutputListener,
    /**
     * The far-end reference for AEC3, or null when the WebRTC canceller is not in the capture
     * chain -- which includes the case where it was asked for and could not be built. Written once
     * in the constructor and read only by the playback thread in [run].
     */
    private val farEnd: FarEndFrameChunker?,
) : Runnable, AudioOutputSpeech.TalkStateListener {

    private val audioOutputs = HashMap<Int, AudioOutputSpeech>()
    private var audioTrack: AudioTrack? = null
    private var bufferSize = 0
    private var thread: Thread? = null

    // Lock that the audio thread waits on when there's no audio to play. Wake when we get a frame.
    private val inactiveLock = Object()
    private val packetLock: Lock = ReentrantLock()
    private var running = false
    private var woken = false // set by every notify() on inactiveLock

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mixer: IAudioMixer<FloatArray, ShortArray> = BasicClippingShortMixer()
    private val decodeExecutorService: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    @Throws(AudioInitializationException::class)
    fun startPlaying(audioStream: Int): Thread? {
        if (thread != null || running) return null

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioHandler.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        bufferSize = minOf(minBufferSize, AudioHandler.FRAME_SIZE * 12)
        Log.v(TAG, "Using buffer size $bufferSize, system's min buffer size: $minBufferSize")

        audioTrack = try {
            @Suppress("DEPRECATION")
            AudioTrack(
                audioStream,
                AudioHandler.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        } catch (e: IllegalArgumentException) {
            throw AudioInitializationException(e)
        }

        val t = Thread(this)
        thread = t
        t.start()
        return t
    }

    fun stopPlaying() {
        if (!running) return

        running = false
        synchronized(inactiveLock) {
            woken = true
            inactiveLock.notify() // Wake inactive lock if active
        }
        try {
            thread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        thread = null

        packetLock.lock()
        for (speech in audioOutputs.values) {
            speech.destroy()
        }
        packetLock.unlock()

        audioOutputs.clear()
        audioTrack?.release()
        audioTrack = null
    }

    fun isPlaying(): Boolean = running

    override fun run() {
        Log.v(TAG, "Started thread.")
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        running = true
        val track = audioTrack!!
        track.play()

        val mix = ShortArray(bufferSize)

        while (running) {
            if (fetchAudio(mix, 0, bufferSize)) {
                // The same samples the speaker gets, handed to the canceller before the write
                // rather than after it: write() blocks until the track has room, and every
                // millisecond the reference spends waiting here is a millisecond it is later than
                // the capture frame that will carry its echo. The chunker copies what it takes, so
                // the APM's render-side processing -- which may modify a frame in place -- cannot
                // reach this buffer on its way to AudioTrack.
                farEnd?.push(mix, bufferSize)
                track.write(mix, 0, bufferSize)
            } else {
                Log.v(TAG, "Pausing thread.")
                synchronized(inactiveLock) {
                    track.flush()
                    track.pause()

                    try {
                        if (farEnd != null) {
                            // AEC3 estimates the delay between what the speaker plays and what the
                            // microphone hears, and it estimates it from a *continuous* reference.
                            // Letting the stream stop here is what makes the first fragment of a
                            // word leak through after a silence: the filter has to re-converge.
                            // Silence at the real-time rate keeps that estimate alive, and costs
                            // one wakeup per buffer (120 ms at 48 kHz) while nobody is speaking.
                            // woken separates a real frame arriving from the timeout; without it
                            // a timed wait cannot tell the two apart -- and it also closes a
                            // pre-existing lost-notify hole, where a notify() landing before this
                            // block was entered left the thread waiting forever.
                            Arrays.fill(mix, 0.toShort())
                            val tickMs = maxOf(1L, (bufferSize * 1000L) / AudioHandler.SAMPLE_RATE)
                            woken = false
                            while (running && !woken) {
                                inactiveLock.wait(tickMs)
                                if (!woken) {
                                    farEnd.push(mix, bufferSize)
                                }
                            }
                        } else {
                            inactiveLock.wait()
                        }
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                    woken = false
                    track.play()
                }
                Log.v(TAG, "Resuming thread.")
            }
        }

        track.flush()
        track.stop()
    }

    /**
     * Fetches audio data from registered audio output users and mixes them into the given buffer.
     * TODO: add priority speaker support.
     * @param buffer The buffer to mix output data into.
     * @param bufferOffset The offset of the
     * @param bufferSize The size of the buffer.
     * @return true if the buffer contains audio data.
     */
    private fun fetchAudio(buffer: ShortArray, bufferOffset: Int, bufferSize: Int): Boolean {
        Arrays.fill(buffer, bufferOffset, bufferOffset + bufferSize, 0.toShort())
        val sources = ArrayList<IAudioMixerSource<FloatArray>>()
        try {
            packetLock.lock()
            // Parallelize decoding using a fixed thread pool equal to the number of cores
            val futureResults = decodeExecutorService.invokeAll(audioOutputs.values)
            for (future in futureResults) {
                val result = future.get()
                if (result.isAlive()) {
                    sources.add(result)
                } else {
                    val speech = result.getSpeechOutput()
                    Log.v(TAG, "Deleted audio user " + speech.getUser().getName())
                    audioOutputs.remove(speech.getSession())
                    speech.destroy()
                }
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
            return false
        } catch (e: ExecutionException) {
            e.printStackTrace()
            return false
        } finally {
            packetLock.unlock()
        }

        if (sources.isEmpty()) return false

        mixer.mix(sources, buffer, bufferOffset, bufferSize)
        return true
    }

    fun queueVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
        if (!running) return

        val msgFlags = (data[0].toInt() and 0x1f).toByte()
        val pds = PacketBuffer(data, data.size)
        pds.skip(1)
        val session = pds.readLong().toInt()
        val user = listener.getUser(session)
        if (user != null && !user.isLocalMuted()) {
            // TODO check for whispers here
            val seq = pds.readLong().toInt()

            // Synchronize so we don't destroy an output while we add a buffer to it.
            packetLock.lock()
            var aop = audioOutputs[session]
            if (aop != null && aop.getCodec() != messageType) {
                aop.destroy()
                aop = null
            }
            if (aop == null) {
                try {
                    aop = AudioOutputSpeech(user, messageType, bufferSize, this)
                } catch (e: NativeAudioException) {
                    Log.v(TAG, "Failed to create audio user " + user.getName())
                    e.printStackTrace()
                    return
                }
                Log.v(TAG, "Created audio user " + user.getName())
                audioOutputs[session] = aop
            }
            packetLock.unlock()

            val dataBuffer = PacketBuffer(pds.bufferBlock(pds.left()))
            aop.addFrameToBuffer(dataBuffer, msgFlags, seq)

            synchronized(inactiveLock) {
                woken = true
                inactiveLock.notify()
            }
        }
    }

    override fun onTalkStateUpdated(session: Int, state: TalkState) {
        mainHandler.post {
            val user = listener.getUser(session)
            if (user != null && user.getTalkState() != state) {
                user.setTalkState(state)
                listener.onUserTalkStateUpdated(user)
            }
        }
    }

    interface AudioOutputListener {
        /**
         * Called when a user's talking state is changed.
         * @param user The user whose talking state has been modified.
         */
        fun onUserTalkStateUpdated(user: User)

        /**
         * Used to set audio-related user data.
         * @return The user for the associated session.
         */
        fun getUser(session: Int): User?
    }

    private companion object {
        private val TAG: String = AudioOutput::class.java.name
    }
}
