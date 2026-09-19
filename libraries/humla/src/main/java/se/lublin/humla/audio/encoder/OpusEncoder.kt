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

package se.lublin.humla.audio.encoder

import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.util.Arrays
import se.lublin.humla.audio.native.OpusEncoderApi
import se.lublin.humla.audio.native.OpusEncoderNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class OpusEncoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val frameSize: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
    private val api: OpusEncoderApi = OpusEncoderNative,
) : IEncoder {
    private val buffer = ByteArray(maxBufferSize)
    private val audioBuffer = ShortArray(framesPerPacket * frameSize)

    // Stateful
    private var bufferedFrames = 0
    private var encodedLength = 0
    private var terminated = false

    private var state: Long
    private var destroyed = false

    init {
        val error = intArrayOf(0)
        state = api.create(sampleRate, channels, OpusEncoderNative.OPUS_APPLICATION_VOIP, error)
        if (error[0] < 0) throw NativeAudioException("Opus encoder initialization failed with error: ${error[0]}")
        api.ctlSetInt(state, OpusEncoderNative.OPUS_SET_VBR_REQUEST, 0)
        api.ctlSetInt(state, OpusEncoderNative.OPUS_SET_BITRATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        if (inputSize != frameSize) {
            throw IllegalArgumentException("This Opus encoder implementation requires a constant frame size.")
        }
        terminated = false
        System.arraycopy(input, 0, audioBuffer, frameSize * bufferedFrames, frameSize)
        bufferedFrames++
        return if (bufferedFrames == framesPerPacket) encodePacket() else 0
    }

    @Throws(NativeAudioException::class)
    private fun encodePacket(): Int {
        if (bufferedFrames < framesPerPacket) {
            // If encoding is done before enough frames are buffered, fill rest of packet.
            Arrays.fill(audioBuffer, frameSize * bufferedFrames, audioBuffer.size, 0.toShort())
            bufferedFrames = framesPerPacket
        }
        val result = api.encode(state, audioBuffer, frameSize * bufferedFrames, buffer, buffer.size)
        if (result < 0) throw NativeAudioException("Opus encoding failed with error: $result")
        encodedLength = result
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = encodedLength > 0

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!isReady()) throw BufferUnderflowException()
        var size = encodedLength
        if (terminated) size = size or (1 shl 13)
        packetBuffer.writeLong(size.toLong())
        packetBuffer.append(buffer, encodedLength)
        bufferedFrames = 0
        encodedLength = 0
        terminated = false
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        terminated = true
        if (bufferedFrames > 0 && !isReady()) {
            // Perform encode operation on remaining audio if available.
            encodePacket()
        }
    }

    fun getBitrate(): Int {
        val value = intArrayOf(0)
        api.ctlGetInt(state, OpusEncoderNative.OPUS_GET_BITRATE_REQUEST, value)
        return value[0]
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        api.destroy(state)
        state = 0L
    }
}
