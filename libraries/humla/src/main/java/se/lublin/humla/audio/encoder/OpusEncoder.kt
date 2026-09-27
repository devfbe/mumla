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
import se.lublin.humla.audio.native.NativeHandle
import se.lublin.humla.audio.native.OpusEncoderApi
import se.lublin.humla.audio.native.OpusEncoderNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

internal class OpusEncoder(
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

    override var bufferedFrames = 0
        private set
    override var encodedLength = 0
        private set
    private var terminated = false

    private val state: NativeHandle

    init {
        val error = intArrayOf(0)
        state = NativeHandle(
            { api.create(sampleRate, channels, OpusEncoderNative.OPUS_APPLICATION_VOIP, error) },
            api::destroy,
        )
        if (error[0] < 0) throw NativeAudioException("Opus encoder initialization failed with error: ${error[0]}")
        val handle = state.value
        api.ctlSetInt(handle, OpusEncoderNative.OPUS_SET_VBR_REQUEST, 0)
        api.ctlSetInt(handle, OpusEncoderNative.OPUS_SET_BITRATE_REQUEST, bitrate)
        api.ctlSetInt(handle, OpusEncoderNative.OPUS_SET_INBAND_FEC_REQUEST, 1)
        api.ctlSetInt(handle, OpusEncoderNative.OPUS_SET_PACKET_LOSS_PERC_REQUEST, EXPECTED_PACKET_LOSS_PERCENT)
        api.ctlSetInt(handle, OpusEncoderNative.OPUS_SET_DTX_REQUEST, 0)
    }

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

    private fun encodePacket(): Int {
        if (bufferedFrames < framesPerPacket) {
            // If encoding is done before enough frames are buffered, fill rest of packet.
            Arrays.fill(audioBuffer, frameSize * bufferedFrames, audioBuffer.size, 0.toShort())
            bufferedFrames = framesPerPacket
        }
        val result = api.encode(state.value, audioBuffer, frameSize * bufferedFrames, buffer, buffer.size)
        if (result < 0) throw NativeAudioException("Opus encoding failed with error: $result")
        encodedLength = result
        return result
    }

    override val isReady: Boolean
        get() = encodedLength > 0

    override val isTerminator: Boolean
        get() = terminated

    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!isReady) throw BufferUnderflowException()
        packetBuffer.append(buffer, encodedLength)
        bufferedFrames = 0
        encodedLength = 0
        terminated = false
    }

    override fun terminate() {
        terminated = true
        if (bufferedFrames > 0 && !isReady) {
            encodePacket()
        }
    }

    override fun close() = state.close()

    companion object {
        /** Loss rate the encoder plans its in-band FEC for; higher spends more bits on redundancy. */
        const val EXPECTED_PACKET_LOSS_PERCENT = 10
    }
}
