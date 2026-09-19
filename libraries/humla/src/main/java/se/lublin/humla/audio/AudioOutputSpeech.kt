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

import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Queue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.sin
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.OpusDecoderNative
import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

/**
 * Decodes one user's incoming voice stream through a jitter buffer into float PCM.
 *
 * [opusApi] and [jitterApi] are the seams JVM tests use to drive the Opus path without native
 * libraries; they default to the `*Native` objects, which load their `.so` on first touch. The
 * CELT and Speex decoders keep their own defaults, so only the Opus codec is testable this way.
 */
class AudioOutputSpeech @JvmOverloads @Throws(NativeAudioException::class) constructor(
    private val user: User,
    private val codec: HumlaUDPMessageType,
    private var requestedSamples: Int,
    private val talkStateListener: TalkStateListener,
    private val opusApi: OpusDecoderApi = OpusDecoderNative,
    jitterApi: SpeexJitterApi = SpeexJitterNative,
) : Callable<AudioOutputSpeech.Result> {

    fun interface TalkStateListener {
        fun onTalkStateUpdated(session: Int, state: TalkState)
    }

    private val decoder: IDecoder
    private val jitterBuffer: SpeexJitterBuffer
    private val jitterLock = Any()
    private var audioBufferSize = AudioHandler.FRAME_SIZE

    // State-specific
    private var buffer: FloatArray
    private val out: FloatArray
    private val fadeOut = FloatArray(AudioHandler.FRAME_SIZE)
    private val fadeIn = FloatArray(AudioHandler.FRAME_SIZE)
    private val frames: Queue<ByteBuffer> = ConcurrentLinkedQueue()
    private var missCount = 0
    private var hasTerminator = false
    private var lastAlive = true
    private var bufferFilled = 0
    private var lastConsume = 0
    private var ucFlags = 0

    init {
        decoder = when (codec) {
            HumlaUDPMessageType.UDPVoiceOpus -> {
                audioBufferSize *= 12
                OpusDecoder(AudioHandler.SAMPLE_RATE, 1, opusApi)
            }
            HumlaUDPMessageType.UDPVoiceCELTBeta -> CELT11Decoder(AudioHandler.SAMPLE_RATE, 1)
            HumlaUDPMessageType.UDPVoiceCELTAlpha -> CELT7Decoder(AudioHandler.SAMPLE_RATE, AudioHandler.FRAME_SIZE, 1)
            HumlaUDPMessageType.UDPVoiceSpeex -> SpeexDecoder()
            else -> throw NativeAudioException("No decoder for codec $codec")
        }

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
        jitterBuffer.control(SpeexJitterNative.JITTER_BUFFER_SET_MARGIN, 10 * AudioHandler.FRAME_SIZE)
    }

    fun addFrameToBuffer(pb: PacketBuffer, flags: Byte, seq: Int) {
        if (pb.capacity() < 2) return

        synchronized(jitterLock) {
            try {
                var samples = 0
                if (codec == HumlaUDPMessageType.UDPVoiceOpus) {
                    val header = pb.readLong()
                    val size = (header and ((1L shl 13) - 1)).toInt()
                    if (size > 0) {
                        val data = pb.dataBlock(size)
                        if (data.size != size) return
                        val frameCount = opusApi.packetGetNbFrames(data, size)
                        samples = frameCount * opusApi.packetGetSamplesPerFrame(data, AudioHandler.SAMPLE_RATE)
                    } else {
                        return
                    }
                } else {
                    try {
                        var header: Int
                        do {
                            header = pb.next()
                            samples += AudioHandler.FRAME_SIZE
                            pb.skip(header and 0x7f)
                        } while ((header and 0x80) > 0)
                    } catch (e: BufferUnderflowException) {
                        // reached end of buffer
                    }
                }
                pb.rewind()

                val size = pb.left()
                val data = pb.dataBlock(size)
                jitterBuffer.put(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags.toInt())
            } catch (e: BufferOverflowException) {
                e.printStackTrace()
            }
        }
    }

    @Throws(Exception::class)
    override fun call(): Result {
        if (bufferFilled - lastConsume > 0) {
            // Shift over the remaining unconsumed data in the buffer.
            System.arraycopy(buffer, lastConsume, buffer, 0, bufferFilled - lastConsume)
        }
        bufferFilled -= lastConsume
        lastConsume = requestedSamples

        if (bufferFilled >= requestedSamples) return Result(this, lastAlive, buffer, bufferFilled)

        var nextAlive = lastAlive

        while (bufferFilled < requestedSamples) {
            var decodedSamples = AudioHandler.FRAME_SIZE
            resizeBuffer(bufferFilled + audioBufferSize)

            if (!lastAlive) {
                Arrays.fill(out, 0f)
            } else {
                val (ts, availPackets) = synchronized(jitterLock) {
                    jitterBuffer.pointerTimestamp to
                        jitterBuffer.control(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT, 0).toFloat()
                }

                // Make sure that we have enough packets in the jitter buffer before we even begin
                // decoding, based on the average # of packets available. Prevents a metallic
                // 'twang' when the user starts talking, caused by buffer underrun. The official
                // Mumble project uses the same technique.
                if (ts == 0) {
                    val want = ceil(user.averageAvailable.toDouble()).toInt()
                    if (availPackets < want) {
                        missCount++
                        if (missCount < 20) {
                            Arrays.fill(out, 0f)
                            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
                            bufferFilled += decodedSamples
                            continue
                        }
                    }
                }

                if (frames.isEmpty()) {
                    val packetBytes = ByteArray(4096)
                    val jbp = synchronized(jitterLock) { jitterBuffer.get(packetBytes, AudioHandler.FRAME_SIZE) }

                    if (jbp.status == SpeexJitterNative.JITTER_BUFFER_OK) {
                        val pb = PacketBuffer(packetBytes, jbp.length)

                        missCount = 0
                        ucFlags = jbp.userData
                        hasTerminator = false
                        try {
                            if (codec == HumlaUDPMessageType.UDPVoiceOpus) {
                                val header = pb.readLong()
                                val size = (header and ((1L shl 13) - 1)).toInt()
                                hasTerminator = (header and (1L shl 13)) > 0
                                frames.add(pb.bufferBlock(size))
                            } else {
                                var header: Int
                                do {
                                    header = pb.next()
                                    val size = header and 0x7f
                                    if (header > 0) {
                                        frames.add(pb.bufferBlock(size))
                                    } else {
                                        hasTerminator = true
                                    }
                                } while ((header and 0x80) > 0)
                            }
                        } catch (e: BufferOverflowException) {
                            e.printStackTrace()
                        } catch (e: BufferUnderflowException) {
                            e.printStackTrace()
                        }

                        if (availPackets >= user.averageAvailable) {
                            user.averageAvailable = availPackets
                        } else {
                            user.averageAvailable = user.averageAvailable * 0.99f
                        }
                    } else {
                        synchronized(jitterLock) { jitterBuffer.updateDelay() }
                        missCount++
                        if (missCount > 10) nextAlive = false
                    }
                }

                try {
                    if (!frames.isEmpty()) {
                        val data = frames.poll()
                        decodedSamples = decoder.decodeFloat(data, data.limit(), out, audioBufferSize)
                        if (frames.isEmpty()) {
                            synchronized(jitterLock) { jitterBuffer.updateDelay() }
                        }
                        if (frames.isEmpty() && hasTerminator) nextAlive = false
                    } else {
                        decodedSamples = decoder.decodeFloat(null, 0, out, AudioHandler.FRAME_SIZE)
                    }
                } catch (e: NativeAudioException) {
                    e.printStackTrace()
                    decodedSamples = AudioHandler.FRAME_SIZE
                }

                if (!nextAlive) {
                    for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeOut[i]
                } else if (ts == 0) {
                    for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeIn[i]
                }

                synchronized(jitterLock) {
                    repeat(decodedSamples / AudioHandler.FRAME_SIZE) { jitterBuffer.tick() }
                }
            }

            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
            bufferFilled += decodedSamples
        }

        if (!nextAlive) ucFlags = 0xFF

        val talkState = when (ucFlags) {
            0 -> TalkState.TALKING
            1 -> TalkState.SHOUTING
            0xFF -> TalkState.PASSIVE
            else -> TalkState.WHISPERING
        }
        talkStateListener.onTalkStateUpdated(user.session, talkState)

        val tmp = lastAlive
        lastAlive = nextAlive
        return Result(this, tmp, buffer, requestedSamples)
    }

    private fun resizeBuffer(newSize: Int) {
        if (newSize > buffer.size) buffer = Arrays.copyOf(buffer, newSize)
    }

    /** Sets the preferred number of samples to return when the callable is executed. */
    fun setRequestedSamples(samples: Int) {
        requestedSamples = samples
    }

    fun getCodec(): HumlaUDPMessageType = codec

    fun getUser(): User = user

    fun getSession(): Int = user.session

    /** Cleans up all native resources linked to this instance. MUST be called eventually. */
    fun destroy() {
        decoder.destroy()
        jitterBuffer.destroy()
    }

    /** The outcome of a decoding pass. */
    class Result internal constructor(
        private val speechOutput: AudioOutputSpeech,
        private val alive: Boolean,
        private val samples: FloatArray,
        private val numSamples: Int,
    ) : IAudioMixerSource<FloatArray> {
        fun getSpeechOutput(): AudioOutputSpeech = speechOutput
        fun isAlive(): Boolean = alive
        override fun getSamples(): FloatArray = samples
        override fun getNumSamples(): Int = numSamples
    }
}
