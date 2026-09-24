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
package se.lublin.humla.audio

import se.lublin.humla.exception.NativeAudioException
import java.nio.ByteBuffer

/** A native voice decoder producing float PCM. */
interface IDecoder {
    /**
     * Decodes [inputSize] bytes of [input] into [output], which holds at least [frameSize] samples.
     * A null [input] asks for loss concealment.
     * @return the number of decoded samples.
     * @throws NativeAudioException if decoding failed.
     */
    fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int

    /** Frees native resources. The decoder must not be called afterwards. */
    fun destroy()
}
