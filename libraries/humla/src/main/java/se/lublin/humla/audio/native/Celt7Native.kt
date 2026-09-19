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

/** CELT 0.7.1 (Mumble "CELT alpha"). Mode handles are `CELTMode*`, state handles `CELTEncoder*` / `CELTDecoder*`. */
interface Celt7Api {
    fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long
    fun modeInfo(mode: Long, request: Int, value: IntArray): Int
    fun modeDestroy(mode: Long)
    fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long
    fun encoderCtlInt(state: Long, request: Int, value: Int): Int
    /** `celt_encode(state, pcm, NULL, out, maxBytes)` */
    fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int
    fun encoderDestroy(state: Long)
    fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long
    fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int
    fun decoderDestroy(state: Long)
}

object Celt7Native : Celt7Api {
    const val CELT_GET_BITSTREAM_VERSION = 2000
    const val CELT_SET_PREDICTION_REQUEST = 4
    const val CELT_SET_VBR_RATE_REQUEST = 6

    init {
        System.loadLibrary("humla_celt7")
    }

    external override fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long
    external override fun modeInfo(mode: Long, request: Int, value: IntArray): Int
    external override fun modeDestroy(mode: Long)
    external override fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long
    external override fun encoderCtlInt(state: Long, request: Int, value: Int): Int
    external override fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int
    external override fun encoderDestroy(state: Long)
    external override fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long
    external override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    external override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int
    external override fun decoderDestroy(state: Long)
}
