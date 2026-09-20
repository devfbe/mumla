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

/**
 * libspeex decoder. A handle owns the decoder state, its SpeexBits and a frame buffer of the
 * mode's frame size; [decodeFloat] copies at most `out.size` samples. `data == null` feeds an
 * empty bit stream (what the old binding did for a lost frame).
 */
interface SpeexDecoderApi {
    fun create(modeId: Int): Long

    /**
     * Runs `speex_decoder_ctl` with [value] as the int argument.
     *
     * [request] has to be [SpeexDecoderNative.SPEEX_SET_ENH] -- that is the only request the
     * bridge allows through, and any other number is refused with -1 without reaching libspeex.
     * The bridge hands it the address of a four-byte int on its own stack frame, and
     * `SPEEX_GET_STACK` writes eight bytes through that address while `SPEEX_SET_INNOVATION_SAVE`
     * keeps it and writes a subframe of samples through it on every later decode.
     *
     * -1 is also what libspeex answers for a request it does not know; the bridge deliberately
     * does not distinguish the two, since this returns libspeex's own status and both mean the
     * same to a caller.
     */
    fun ctlInt(handle: Long, request: Int, value: Int): Int
    fun decodeFloat(handle: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    fun destroy(handle: Long)
}

object SpeexDecoderNative : SpeexDecoderApi {
    const val SPEEX_SET_ENH = 0
    const val SPEEX_MODEID_UWB = 2

    init {
        System.loadLibrary("humla_speex")
    }

    external override fun create(modeId: Int): Long
    external override fun ctlInt(handle: Long, request: Int, value: Int): Int
    external override fun decodeFloat(handle: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    external override fun destroy(handle: Long)
}
