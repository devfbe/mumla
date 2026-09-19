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

package se.lublin.humla.audio.native

/** CELT 0.11.1 (Mumble "CELT beta"). Handles are `CELTEncoder*` / `CELTDecoder*`. */
interface Celt11Api {
    fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    fun encoderDestroy(state: Long)
    fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int
    fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int
    fun decoderDestroy(state: Long)
}

object Celt11Native : Celt11Api {
    init {
        System.loadLibrary("humla_celt11")
    }

    external override fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    external override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    external override fun encoderDestroy(state: Long)
    external override fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    external override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int
    external override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int
    external override fun decoderDestroy(state: Long)
}
