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
import kotlin.math.min
import se.lublin.humla.audio.native.Celt7Api
import se.lublin.humla.audio.native.Celt7Native
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

class CELT7Encoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    frameSize: Int,
    channels: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
    private val api: Celt7Api = Celt7Native,
) : IEncoder {
    private val bufferSize = min(maxBufferSize, bitrate / 800)
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private val packetLengths = IntArray(framesPerPacket)
    private var bufferedFrames = 0
    private var ready = false

    private val mode: Long
    private val state: Long

    init {
        val error = intArrayOf(0)
        mode = api.modeCreate(sampleRate, frameSize, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: ${error[0]}")
        state = api.encoderCreate(mode, channels, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: ${error[0]}")
        api.encoderCtlInt(state, Celt7Native.CELT_SET_PREDICTION_REQUEST, 0)
        api.encoderCtlInt(state, Celt7Native.CELT_SET_VBR_RATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = api.encode(state, input, buffer[bufferedFrames], bufferSize)
        if (result < 0) throw NativeAudioException("CELT 0.7.0 encoding failed with error: $result")
        packetLengths[bufferedFrames] = result
        bufferedFrames++
        if (bufferedFrames >= framesPerPacket) ready = true
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = ready && bufferedFrames > 0

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!ready) throw BufferUnderflowException()
        for (x in 0 until bufferedFrames) {
            val frame = buffer[x]
            val length = packetLengths[x]
            var head = length
            if (x < bufferedFrames - 1) head = head or 0x80
            packetBuffer.append(head.toLong())
            packetBuffer.append(frame, length)
        }
        bufferedFrames = 0
        ready = false
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        ready = true
    }

    override fun destroy() {
        api.encoderDestroy(state)
        api.modeDestroy(mode)
    }

    companion object {
        /** The CELT 0.7 bitstream version Mumla announces in `Authenticate.celt_versions`. */
        @JvmStatic
        fun getBitstreamVersion(): Int {
            val mode = Celt7Native.modeCreate(AudioHandler.SAMPLE_RATE, AudioHandler.FRAME_SIZE, null)
            val version = intArrayOf(0)
            Celt7Native.modeInfo(mode, Celt7Native.CELT_GET_BITSTREAM_VERSION, version)
            Celt7Native.modeDestroy(mode)
            return version[0]
        }
    }
}
