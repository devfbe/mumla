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

import com.googlecode.javacpp.IntPointer
import com.googlecode.javacpp.Pointer
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import kotlin.math.min
import se.lublin.humla.audio.javacpp.CELT7
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class CELT7Encoder @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    frameSize: Int,
    channels: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
) : IEncoder {
    private val bufferSize = min(maxBufferSize, bitrate / 800)
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private val packetLengths = IntArray(framesPerPacket)
    private var bufferedFrames = 0
    private var ready = false

    private val mode: Pointer
    private val state: Pointer

    init {
        val error = IntPointer(1)
        error.put(0)
        mode = CELT7.celt_mode_create(sampleRate, frameSize, error)
        if (error.get() < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: " + error.get())
        state = CELT7.celt_encoder_create(mode, channels, error)
        if (error.get() < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: " + error.get())
        CELT7.celt_encoder_ctl(state, CELT7.CELT_SET_PREDICTION_REQUEST, 0)
        CELT7.celt_encoder_ctl(state, CELT7.CELT_SET_VBR_RATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = CELT7.celt_encode(state, input, null, buffer[bufferedFrames], bufferSize)
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
        CELT7.celt_encoder_destroy(state)
        CELT7.celt_mode_destroy(mode)
    }
}
