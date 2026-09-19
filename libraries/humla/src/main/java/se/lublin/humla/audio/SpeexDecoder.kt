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
import se.lublin.humla.audio.native.SpeexDecoderApi
import se.lublin.humla.audio.native.SpeexDecoderNative
import se.lublin.humla.exception.NativeAudioException

class SpeexDecoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    private val api: SpeexDecoderApi = SpeexDecoderNative,
) : IDecoder {
    private var handle: Long = api.create(SpeexDecoderNative.SPEEX_MODEID_UWB)
    private var destroyed = false

    init {
        // create returns 0 when the mode is unknown or reports a non-positive frame size.
        if (handle == 0L) throw NativeAudioException("Speex decoder initialization failed")
        api.ctlInt(handle, SpeexDecoderNative.SPEEX_SET_ENH, 1)
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(handle, PacketBytes.copy(input, inputSize), inputSize, output)
        if (result < 0) throw NativeAudioException("Speex decoding failed with error: $result")
        for (i in 0 until frameSize) output[i] *= (1.0f / Short.MAX_VALUE)
        return frameSize
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val floats = FloatArray(frameSize)
        val result = api.decodeFloat(handle, PacketBytes.copy(input, inputSize), inputSize, floats)
        if (result < 0) throw NativeAudioException("Speex decoding failed with error: $result")
        for (i in 0 until frameSize) output[i] = floats[i].toInt().toShort()
        return frameSize
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        api.destroy(handle)
        handle = 0L
    }
}
