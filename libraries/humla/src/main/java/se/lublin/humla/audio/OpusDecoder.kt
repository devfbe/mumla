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

import se.lublin.humla.audio.native.NativeHandle
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.OpusDecoderNative
import se.lublin.humla.exception.NativeAudioException

class OpusDecoder(
    sampleRate: Int,
    channels: Int,
    private val api: OpusDecoderApi = OpusDecoderNative,
) : IDecoder {
    private val state: NativeHandle

    init {
        val error = intArrayOf(0)
        state = NativeHandle({ api.create(sampleRate, channels, error) }, api::destroy)
        if (error[0] < 0) throw NativeAudioException("Opus decoder initialization failed with error: ${error[0]}")
    }

    override fun decodeFloat(input: ByteArray?, offset: Int, length: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(state.value, input, offset, length, output, frameSize, 0)
        if (result < 0) throw NativeAudioException("Opus decoding failed with error: $result")
        return result
    }

    override fun close() = state.close()
}
