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
import java.nio.BufferUnderflowException
import se.lublin.humla.audio.javacpp.Speex
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class PreprocessingEncoder(
    private var encoder: IEncoder,
    frameSize: Int,
    sampleRate: Int,
) : IEncoder {
    private val preprocessor = Speex.SpeexPreprocessState(frameSize, sampleRate)
    private var destroyed = false

    init {
        val arg = IntPointer(1)
        arg.put(0)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_VAD, arg)
        arg.put(1)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_AGC, arg)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_DENOISE, arg)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_DEREVERB, arg)
        arg.put(30000)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_AGC_TARGET, arg)
        // Increase VAD difficulty. NOTE: the request id is GET_PROB_START, as in the Java
        // original; stream B (spec B9) corrects this to SET_PROB_START.
        arg.put(99)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_GET_PROB_START, arg)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        preprocessor.preprocess(input)
        return encoder.encode(input, inputSize)
    }

    override fun getBufferedFrames(): Int = encoder.getBufferedFrames()

    override fun isReady(): Boolean = encoder.isReady()

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) = encoder.getEncodedData(packetBuffer)

    @Throws(NativeAudioException::class)
    override fun terminate() = encoder.terminate()

    fun setEncoder(encoder: IEncoder) {
        this.encoder.destroy()
        this.encoder = encoder
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        preprocessor.destroy()
        encoder.destroy()
    }
}
