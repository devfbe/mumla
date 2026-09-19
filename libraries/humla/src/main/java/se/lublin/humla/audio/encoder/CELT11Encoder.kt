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
import se.lublin.humla.audio.javacpp.CELT11
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class CELT11Encoder(
    sampleRate: Int,
    channels: Int,
    private val framesPerPacket: Int,
) : IEncoder {
    private val bufferSize = sampleRate / 800
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private var bufferedFrames = 0

    private val state: Pointer

    init {
        val error = IntPointer(1)
        error.put(0)
        state = CELT11.celt_encoder_create(sampleRate, channels, error)
        if (error.get() < 0) throw NativeAudioException("CELT 0.11.0 encoder initialization failed with error: " + error.get())
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = CELT11.celt_encode(state, input, inputSize, buffer[bufferedFrames], bufferSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 encoding failed with error: $result")
        bufferedFrames++
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = bufferedFrames == framesPerPacket

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (bufferedFrames < framesPerPacket) throw BufferUnderflowException()
        for (x in 0 until bufferedFrames) {
            val frame = buffer[x]
            var head = frame.size
            if (x < bufferedFrames - 1) head = head or 0x80
            packetBuffer.append(head.toLong())
            packetBuffer.append(frame, frame.size)
        }
        bufferedFrames = 0
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        // The CELT 0.11 encoder has no partial-packet flush; kept as before.
    }

    override fun destroy() {
        CELT11.celt_encoder_destroy(state)
    }
}
