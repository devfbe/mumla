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

/** libopus decoder and packet inspection. State handles are `OpusDecoder*`. `data == null` requests PLC. */
interface OpusDecoderApi {
    fun create(sampleRate: Int, channels: Int, error: IntArray): Long
    fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int
    fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int
    fun destroy(state: Long)
    fun packetGetNbFrames(packet: ByteArray, len: Int): Int
    fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int
}

object OpusDecoderNative : OpusDecoderApi {
    init {
        System.loadLibrary("humla_opus")
    }

    external override fun create(sampleRate: Int, channels: Int, error: IntArray): Long
    external override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int
    external override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int
    external override fun destroy(state: Long)
    external override fun packetGetNbFrames(packet: ByteArray, len: Int): Int
    external override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int
}
