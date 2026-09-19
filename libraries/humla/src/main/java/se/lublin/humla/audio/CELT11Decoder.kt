/*
 * Copyright (C) 2026 The Mumla Authors
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

import java.nio.ByteBuffer
import se.lublin.humla.audio.native.Celt11Api
import se.lublin.humla.audio.native.Celt11Native
import se.lublin.humla.exception.NativeAudioException

class CELT11Decoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val api: Celt11Api = Celt11Native,
) : IDecoder {
    private var state: Long
    private var destroyed = false

    init {
        val error = intArrayOf(0)
        state = api.decoderCreate(sampleRate, channels, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.11.0 decoder initialization failed with error: ${error[0]}")
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 decoding failed with error: $result")
        return frameSize
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val result = api.decodeShort(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 decoding failed with error: $result")
        return frameSize
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        api.decoderDestroy(state)
        state = 0L
    }
}
