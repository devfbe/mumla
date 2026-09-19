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

import java.nio.BufferUnderflowException
import se.lublin.humla.audio.javacpp.Speex
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class ResamplingEncoder(
    private var encoder: IEncoder,
    channels: Int,
    inputSampleRate: Int,
    private val targetFrameSize: Int,
    targetSampleRate: Int,
) : IEncoder {
    private val resampleBuffer = ShortArray(targetFrameSize)
    private val resampler = Speex.SpeexResampler(channels, inputSampleRate, targetSampleRate, SPEEX_RESAMPLE_QUALITY)
    private var destroyed = false

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        resampler.resample(input, resampleBuffer)
        return encoder.encode(resampleBuffer, targetFrameSize)
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
        resampler.destroy()
        encoder.destroy()
    }

    private companion object {
        const val SPEEX_RESAMPLE_QUALITY = 3
    }
}
