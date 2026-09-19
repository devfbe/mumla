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

/** libspeexdsp resampler. `inLen[0]`/`outLen[0]` are in/out sample counts as in `speex_resampler_process_int`. */
interface SpeexResamplerApi {
    fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long
    fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int
    fun destroy(state: Long)
}

object SpeexResamplerNative : SpeexResamplerApi {
    init {
        System.loadLibrary("humla_speexdsp")
    }

    external override fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long
    external override fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int
    external override fun destroy(state: Long)
}
