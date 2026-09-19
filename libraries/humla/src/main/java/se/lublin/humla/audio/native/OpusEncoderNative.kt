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

/** libopus encoder. State handles are `OpusEncoder*`. */
interface OpusEncoderApi {
    fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long
    fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    fun ctlSetInt(state: Long, request: Int, value: Int): Int
    fun ctlGetInt(state: Long, request: Int, value: IntArray): Int
    fun destroy(state: Long)
}

object OpusEncoderNative : OpusEncoderApi {
    const val OPUS_APPLICATION_VOIP = 2048
    const val OPUS_SET_BITRATE_REQUEST = 4002
    const val OPUS_GET_BITRATE_REQUEST = 4003
    const val OPUS_SET_VBR_REQUEST = 4006

    init {
        System.loadLibrary("humla_opus")
    }

    external override fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long
    external override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    external override fun ctlSetInt(state: Long, request: Int, value: Int): Int
    external override fun ctlGetInt(state: Long, request: Int, value: IntArray): Int
    external override fun destroy(state: Long)
}
