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
    /** A new state, or 0 if [channels] is not positive or speex could not allocate one. `error[0]`
     *  receives a `RESAMPLER_ERR_*` code when [error] is given and has room for it. */
    fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long

    /**
     * Resamples one channel.
     *
     * [channelIndex] is an index, not a count: it has to be in `0 until channels` as passed to
     * [init]. speex uses it to reach three per-channel arrays that were sized for that count and
     * compares it against nothing, so an index outside the range is refused here with
     * `RESAMPLER_ERR_INVALID_ARG` rather than passed on as a heap read and write.
     *
     * `inLen[0]` and `outLen[0]` are clamped to `input.size` and `out.size` before speex sees
     * them, and come back as the counts actually consumed and produced.
     */
    fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int

    /** Releases [state]; 0 is a no-op. */
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
