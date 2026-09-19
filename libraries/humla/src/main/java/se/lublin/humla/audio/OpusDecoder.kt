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
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.OpusDecoderNative
import se.lublin.humla.exception.NativeAudioException

class OpusDecoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val api: OpusDecoderApi = OpusDecoderNative,
) : IDecoder {
    private val state: Long

    init {
        val error = intArrayOf(0)
        state = api.create(sampleRate, channels, error)
        if (error[0] < 0) throw NativeAudioException("Opus decoder initialization failed with error: ${error[0]}")
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize, 0)
        if (result < 0) throw NativeAudioException("Opus decoding failed with error: $result")
        return result
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val result = api.decodeShort(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize, 0)
        if (result < 0) throw NativeAudioException("Opus decoding failed with error: $result")
        return result
    }

    override fun destroy() = api.destroy(state)
}
