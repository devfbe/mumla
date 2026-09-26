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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import se.lublin.humla.audio.capture.FarEndFrameChunker
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.VoicePacket
import java.util.Arrays
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decodes and mixes all users' voice streams on one playback thread, inline and without allocating
 * per mix.
 *
 * @param farEnd receives every mixed buffer as the AEC3 far-end reference, or null when the
 *               WebRTC canceller is not in the capture chain. Used only by the playback thread.
 */
class AudioOutput @JvmOverloads constructor(
    private val listener: AudioOutputListener,
    private val farEnd: FarEndFrameChunker?,
    /** Builds one user's decoder chain; the seam JVM tests use to run without native codecs. */
    private val speechFactory: SpeechFactory = SpeechFactory { user, samples, talkStateListener ->
        AudioOutputSpeech(user, samples, talkStateListener)
    },
) : Runnable, AudioOutputSpeech.TalkStateListener {

    fun interface SpeechFactory {
        @Throws(NativeAudioException::class)
        fun create(
            user: User,
            requestedSamples: Int,
            talkStateListener: AudioOutputSpeech.TalkStateListener,
        ): AudioOutputSpeech
    }

    private val audioOutputs = HashMap<Int, AudioOutputSpeech>()
    private var audioTrack: AudioTrack? = null
    private var bufferSize = 0
    private var thread: Thread? = null

    // Lock that the audio thread waits on when there's no audio to play. Wake when we get a frame.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait()/notify()
    private val inactiveLock = Object()
    /** The same talkers as [audioOutputs], in the order they are decoded and mixed. */
    private val mix = PlaybackMix()

    /**
     * Guards [audioOutputs] and [mix] between the network thread, the playback thread and [stopPlaying].
     * Only taken through `withLock` so an exception in the critical section cannot leave it held.
     */
    private val packetLock: Lock = ReentrantLock()
    /** Set before the thread starts so an early [stopPlaying] still sees a running output. */
    @Volatile
    private var running = false
    private var woken = false // set by every notify() on inactiveLock, read and written under it
    /** Legacy-codec packets are dropped; this keeps it to one log line per output. */
    @Volatile
    private var loggedUnsupportedCodec = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val onEnded: (AudioOutputSpeech) -> Unit = { speech ->
        Log.v(TAG, "Deleted audio user " + speech.user.name)
        audioOutputs.remove(speech.session)
        speech.destroy()
    }

    @Throws(AudioInitializationException::class)
    fun startPlaying(audioStream: Int): Thread? {
        if (thread != null || running) return null

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioHandler.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            throw AudioInitializationException("AudioTrack has no minimum buffer size for this format: $minBufferSize")
        }
        val sizes = playbackBuffer(minBufferSize)
        bufferSize = sizes.mixSamples
        Log.v(
            TAG,
            "Mixing ${sizes.mixSamples} samples per write into a ${sizes.trackBytes}-byte track " +
                "(system minimum $minBufferSize bytes)",
        )

        audioTrack = buildTrack(audioStream, sizes.trackBytes)

        val t = Thread(this)
        thread = t
        running = true
        t.start()
        return t
    }

    @Throws(AudioInitializationException::class)
    private fun buildTrack(audioStream: Int, bytes: Int): AudioTrack = try {
        AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes(audioStream))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioHandler.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    } catch (e: IllegalArgumentException) {
        throw AudioInitializationException(e)
    } catch (e: UnsupportedOperationException) {
        throw AudioInitializationException(e)
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

        packetLock.withLock {
            mix.clear { it.destroy() }
            audioOutputs.clear()
        }
        audioTrack?.release()
        audioTrack = null
    }

    fun isPlaying(): Boolean = running

    @VisibleForTesting
    internal fun playbackTrack(): AudioTrack? = audioTrack

    override fun run() {
        Log.v(TAG, "Started thread.")
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = audioTrack!!
        track.play()

        val pcm = ShortArray(bufferSize)

        while (running) {
            if (fetchAudio(pcm, 0, bufferSize)) {
                // Feed the canceller before write(), which blocks: a late reference misaligns
                // the echo estimate. The chunker copies, so the APM cannot modify this buffer.
                farEnd?.push(pcm, bufferSize)
                track.write(pcm, 0, bufferSize)
            } else {
                Log.v(TAG, "Pausing thread.")
                synchronized(inactiveLock) {
                    track.flush()
                    track.pause()

                    try {
                        if (farEnd != null) {
                            // AEC3 needs a continuous reference to keep its delay estimate;
                            // otherwise the first fragment of a word leaks after a silence. Feed
                            // silence at the real-time rate. `woken` tells a real frame from the
                            // timeout and keeps a notify() sent before this block from being lost.
                            Arrays.fill(pcm, 0.toShort())
                            val tickMs = maxOf(1L, (bufferSize * 1000L) / AudioHandler.SAMPLE_RATE)
                            woken = false
                            while (running && !woken) {
                                inactiveLock.wait(tickMs)
                                if (!woken) {
                                    farEnd.push(pcm, bufferSize)
                                }
                            }
                        } else {
                            // `woken` keeps a notify() sent before this block from being lost.
                            while (running && !woken) {
                                inactiveLock.wait()
                            }
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
     * @param bufferOffset The offset into the buffer.
     * @param bufferSize The size of the buffer.
     * @return true if the buffer contains audio data.
     */
    private fun fetchAudio(buffer: ShortArray, bufferOffset: Int, bufferSize: Int): Boolean {
        Arrays.fill(buffer, bufferOffset, bufferOffset + bufferSize, 0.toShort())
        return packetLock.withLock { mix.mixInto(buffer, bufferOffset, bufferSize, onEnded) }
    }

    /** True for Opus; anything else is dropped, with one log line per output. */
    private fun isDecodable(messageType: HumlaUDPMessageType): Boolean {
        if (messageType == HumlaUDPMessageType.UDPVoiceOpus) return true
        if (!loggedUnsupportedCodec) {
            loggedUnsupportedCodec = true
            Log.w(TAG, "Dropping $messageType voice packets: only Opus is supported")
        }
        return false
    }

    /** Queues [packet] for its talker; [packet] is not kept. */
    fun queueVoiceData(packet: VoicePacket) {
        if (!running || !isDecodable(packet.codec)) return

        val session = packet.session
        val user = listener.getUser(session)
        if (user != null && !user.isLocalMuted) {
            val aop = packetLock.withLock {
                audioOutputs[session] ?: try {
                    speechFactory.create(user, bufferSize, this).also {
                        Log.v(TAG, "Created audio user " + user.name)
                        audioOutputs[session] = it
                        mix.add(it)
                    }
                } catch (e: NativeAudioException) {
                    Log.v(TAG, "Failed to create audio user " + user.name)
                    e.printStackTrace()
                    null
                }
            } ?: return

            aop.addFrameToBuffer(packet)

            synchronized(inactiveLock) {
                woken = true
                inactiveLock.notify()
            }
        }
    }

    override fun onTalkStateUpdated(session: Int, state: TalkState) {
        mainHandler.post {
            val user = listener.getUser(session)
            if (user != null && user.talkState != state) {
                user.talkState = state
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

    /**
     * [mixSamples]: 16-bit mono samples per mix and per [AudioTrack.write]; [trackBytes]: the
     * track's `bufferSizeInBytes`.
     */
    internal data class PlaybackBuffer(val mixSamples: Int, val trackBytes: Int)

    internal companion object {
        private val TAG: String = AudioOutput::class.java.name

        private const val BYTES_PER_SAMPLE = 2 // ENCODING_PCM_16BIT, CHANNEL_OUT_MONO

        /**
         * [minBufferBytes] is [AudioTrack.getMinBufferSize], in **bytes**. The track gets it in
         * full; a mix is at most what it holds and never more than twelve frames (120 ms), which
         * is what the far-end chunker and the decoders are sized for.
         */
        /**
         * The attributes a track on [stream] had with the legacy stream-type constructor. The
         * voice-call stream is spelled out as voice communication, the usage that follows the
         * communication device the router selects.
         */
        fun playbackAttributes(stream: Int): AudioAttributes =
            if (stream == AudioManager.STREAM_VOICE_CALL) {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            } else {
                AudioAttributes.Builder().setLegacyStreamType(stream).build()
            }

        fun playbackBuffer(minBufferBytes: Int): PlaybackBuffer {
            val mixSamples = minOf(minBufferBytes / BYTES_PER_SAMPLE, AudioHandler.FRAME_SIZE * 12)
            return PlaybackBuffer(mixSamples = mixSamples, trackBytes = minBufferBytes)
        }
    }
}
