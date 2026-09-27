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

import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.OpusDecoderNative
import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.VoicePacket
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.sin

private const val JITTER_MARGIN_FRAMES = 10

/** How many frames of silence may be played while the jitter buffer fills at the start of a talk spurt. */
private const val MAX_PREBUFFER_MISSES = 20

/** Consecutive frames without a packet after which the stream counts as ended. */
private const val MAX_MISSES_ALIVE = 10

/** Per packet, the decay of the average packet count the prebuffering aims for. */
private const val AVAILABLE_DECAY = 0.99f

/** Larger than any voice packet the jitter buffer holds. */
private const val MAX_PACKET_BYTES = 4096

/** Behind the opus frame in the jitter buffer: the volume adjustment (float bits) and the terminator flag. */
private const val TRAILER_BYTES = Int.SIZE_BYTES + 1

/** The user data after the stream ended. */
private const val PASSIVE_FLAGS = 0xFF
private const val BYTE_MASK = 0xFF

/**
 * Decodes one user's incoming Opus stream through a jitter buffer into float PCM. Each [decode]
 * leaves the next mix in [samples]; it reuses its buffers and allocates nothing per call.
 *
 * [averageAvailable] holds the talker's average packet count across their talk spurts, which
 * outlive this instance. [opusApi] and [jitterApi] are the seams JVM tests use to run without
 * native libraries; they default to the `*Native` objects, which load the native library on first
 * touch.
 */
internal class AudioOutputSpeech(
    val session: Int,
    private val requestedSamples: Int,
    private val listener: Listener,
    private val opusApi: OpusDecoderApi = OpusDecoderNative,
    jitterApi: SpeexJitterApi = SpeexJitterNative,
    private val averageAvailable: FloatArray = FloatArray(1),
) : IAudioMixerSource<FloatArray>, AutoCloseable {

    /** Called on the playback thread for every decoded frame: must neither block nor allocate. */
    fun interface Listener {
        /** Only on a change. */
        fun onTalkStateUpdated(session: Int, state: TalkState)

        /** The local playback gain for [session]; 1 is unchanged. */
        fun gainOf(session: Int): Float = 1f
    }

    private val decoder: IDecoder = OpusDecoder(AudioHandler.SAMPLE_RATE, 1, opusApi)
    private val jitterBuffer: SpeexJitterBuffer
    private val jitterLock = Any()
    private val audioBufferSize = AudioHandler.FRAME_SIZE * AudioHandler.MAX_PACKET_FRAMES

    private var buffer: FloatArray
    private val out: FloatArray
    private val fadeOut = FloatArray(AudioHandler.FRAME_SIZE)
    private val fadeIn = FloatArray(AudioHandler.FRAME_SIZE)

    /** A packet on its way into the jitter buffer; guarded by [jitterLock]. */
    private val ingestBytes = ByteArray(MAX_PACKET_BYTES)

    /** The packet taken out of the jitter buffer: an opus frame of [frameLength] bytes, then the trailer. */
    private val packetBytes = ByteArray(MAX_PACKET_BYTES)
    private var hasFrame = false
    private var frameLength = 0

    /** The volume adjustment of the last packet taken; concealed frames keep it. */
    private var frameVolume = 1f

    private var missCount = 0
    private var hasTerminator = false
    private var lastAlive = true

    /** Whether the stream is still alive after the mix being decoded. */
    private var nextAlive = true
    private var bufferFilled = 0
    private var lastConsume = 0
    private var ucFlags = 0
    private var reportedState: TalkState? = null

    override val samples: FloatArray get() = buffer
    override var numSamples: Int = 0
        private set

    init {
        // Larger initial buffer so we can save performance by not resizing at runtime.
        buffer = FloatArray(audioBufferSize * 2)
        out = FloatArray(audioBufferSize)

        // Sine function to represent fade in/out. Period is FRAME_SIZE.
        val mul = (Math.PI / (2.0 * AudioHandler.FRAME_SIZE)).toFloat()
        for (i in 0 until AudioHandler.FRAME_SIZE) {
            val v = sin((i * mul).toDouble()).toFloat()
            fadeIn[i] = v
            fadeOut[AudioHandler.FRAME_SIZE - i - 1] = v
        }

        jitterBuffer = SpeexJitterBuffer(AudioHandler.FRAME_SIZE, jitterApi)
        jitterBuffer.control(SpeexJitterNative.JITTER_BUFFER_SET_MARGIN, JITTER_MARGIN_FRAMES * AudioHandler.FRAME_SIZE)
    }

    /**
     * Queues the Opus packet of [packet] in the jitter buffer, with its terminator flag and volume
     * adjustment behind it and its context as the user data. Called on the network thread.
     */
    fun addFrameToBuffer(packet: VoicePacket) {
        val length = packet.opusLength
        if (length <= 0 || length + TRAILER_BYTES > ingestBytes.size) return

        synchronized(jitterLock) {
            System.arraycopy(packet.data, packet.opusOffset, ingestBytes, 0, length)
            val frameCount = opusApi.packetGetNbFrames(ingestBytes, length)
            if (frameCount <= 0) return
            val samples = frameCount * opusApi.packetGetSamplesPerFrame(ingestBytes, AudioHandler.SAMPLE_RATE)
            val volumeBits = packet.volumeAdjustment.toRawBits()
            for (i in 0 until Int.SIZE_BYTES) {
                ingestBytes[length + i] = (volumeBits ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - i))).toByte()
            }
            ingestBytes[length + Int.SIZE_BYTES] = if (packet.isTerminator) 1 else 0
            val timestamp = AudioHandler.FRAME_SIZE * packet.frameNumber.toInt()
            jitterBuffer.put(ingestBytes, length + TRAILER_BYTES, timestamp, samples, 0, packet.context)
        }
    }

    /**
     * Decodes the next mix into [samples] ([numSamples] valid). Called on the playback thread only.
     *
     * @return false once the stream has ended and the previous mix was its last; the caller then
     *   destroys this instance.
     */
    fun decode(): Boolean {
        if (bufferFilled - lastConsume > 0) {
            // Shift over the remaining unconsumed data in the buffer.
            System.arraycopy(buffer, lastConsume, buffer, 0, bufferFilled - lastConsume)
        }
        bufferFilled -= lastConsume
        lastConsume = requestedSamples

        if (bufferFilled >= requestedSamples) {
            numSamples = bufferFilled
            return lastAlive
        }

        nextAlive = lastAlive
        while (bufferFilled < requestedSamples) {
            resizeBuffer(bufferFilled + audioBufferSize)
            val decodedSamples = if (lastAlive) {
                decodeFrame()
            } else {
                Arrays.fill(out, 0f)
                AudioHandler.FRAME_SIZE
            }
            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
            bufferFilled += decodedSamples
        }

        if (!nextAlive) ucFlags = PASSIVE_FLAGS
        reportTalkState()

        val tmp = lastAlive
        lastAlive = nextAlive
        numSamples = requestedSamples
        return tmp
    }

    /** Decodes (or conceals) the next frame into [out]; returns the number of samples. */
    private fun decodeFrame(): Int {
        val ts: Int
        val availPackets: Float
        synchronized(jitterLock) {
            ts = jitterBuffer.pointerTimestamp
            availPackets =
                jitterBuffer.control(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT, 0).toFloat()
        }

        // Make sure that we have enough packets in the jitter buffer before we even begin
        // decoding, based on the average # of packets available. Prevents a metallic 'twang' when
        // the user starts talking, caused by buffer underrun. The official Mumble project uses the
        // same technique.
        if (ts == 0 && availPackets < ceil(averageAvailable[0].toDouble()).toInt()) {
            missCount++
            if (missCount < MAX_PREBUFFER_MISSES) {
                Arrays.fill(out, 0f)
                return AudioHandler.FRAME_SIZE
            }
        }

        if (!hasFrame) fetchFrame(availPackets)

        val decodedSamples = try {
            if (hasFrame) {
                hasFrame = false
                val decoded = decoder.decodeFloat(packetBytes, 0, frameLength, out, audioBufferSize)
                synchronized(jitterLock) { jitterBuffer.updateDelay() }
                if (hasTerminator) nextAlive = false
                decoded
            } else {
                decoder.decodeFloat(null, 0, 0, out, AudioHandler.FRAME_SIZE)
            }
        } catch (e: NativeAudioException) {
            e.printStackTrace()
            AudioHandler.FRAME_SIZE
        }

        val gain = listener.gainOf(session) * frameVolume
        if (gain != 1f) {
            for (i in 0 until decodedSamples) out[i] *= gain
        }

        if (!nextAlive) {
            for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeOut[i]
        } else if (ts == 0) {
            for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeIn[i]
        }

        synchronized(jitterLock) {
            repeat(decodedSamples / AudioHandler.FRAME_SIZE) { jitterBuffer.tick() }
        }
        return decodedSamples
    }

    /** Takes the next packet out of the jitter buffer, or counts a miss. */
    private fun fetchFrame(availPackets: Float) {
        val status = synchronized(jitterLock) { jitterBuffer.get(packetBytes, AudioHandler.FRAME_SIZE) }
        if (status == SpeexJitterNative.JITTER_BUFFER_OK) {
            missCount = 0
            ucFlags = jitterBuffer.packetUserData
            takeFrame(jitterBuffer.packetLength)

            averageAvailable[0] = if (availPackets >= averageAvailable[0]) {
                availPackets
            } else {
                averageAvailable[0] * AVAILABLE_DECAY
            }
        } else {
            synchronized(jitterLock) { jitterBuffer.updateDelay() }
            missCount++
            if (missCount > MAX_MISSES_ALIVE) {
                if (nextAlive) {
                    se.lublin.humla.util.HumlaLog.d(
                        "StutterDiag",
                        "session $session: stream ends after $missCount misses; available=$availPackets " +
                            "average=${averageAvailable[0]} requested=$requestedSamples filled=$bufferFilled",
                    )
                }
                nextAlive = false
            }
        }
    }

    private fun reportTalkState() {
        // Like desktop Mumble: audio heard through a channel listener, or in an unknown context,
        // shows as plain talking.
        val talkState = when (ucFlags) {
            PASSIVE_FLAGS -> TalkState.PASSIVE
            VoicePacket.CONTEXT_SHOUT -> TalkState.SHOUTING
            VoicePacket.CONTEXT_WHISPER -> TalkState.WHISPERING
            else -> TalkState.TALKING
        }
        if (talkState != reportedState) {
            reportedState = talkState
            listener.onTalkStateUpdated(session, talkState)
        }
    }

    /** Takes the frame and trailer out of the first [length] bytes of [packetBytes]. */
    private fun takeFrame(length: Int) {
        hasTerminator = false
        if (length <= TRAILER_BYTES || length > packetBytes.size) return
        frameLength = length - TRAILER_BYTES
        var volumeBits = 0
        for (i in 0 until Int.SIZE_BYTES) {
            volumeBits = (volumeBits shl Byte.SIZE_BITS) or (packetBytes[frameLength + i].toInt() and BYTE_MASK)
        }
        frameVolume = Float.fromBits(volumeBits)
        hasTerminator = packetBytes[length - 1].toInt() != 0
        hasFrame = true
    }

    private fun resizeBuffer(newSize: Int) {
        if (newSize > buffer.size) buffer = Arrays.copyOf(buffer, newSize)
    }

    /** Frees the native decoder and jitter buffer. Must be called eventually. */
    override fun close() {
        decoder.close()
        jitterBuffer.close()
    }
}
