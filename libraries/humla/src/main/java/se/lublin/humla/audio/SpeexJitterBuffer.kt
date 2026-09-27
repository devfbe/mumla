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

package se.lublin.humla.audio

import se.lublin.humla.audio.native.NativeHandle
import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative

/** The bridge's `get` metadata: length, timestamp, span, sequence, user data. */
private const val META_SIZE = 5
private const val META_LENGTH = 0
private const val META_USER_DATA = 4

/** Object wrapper around the speexdsp jitter buffer. */
internal class SpeexJitterBuffer(
    stepSize: Int,
    private val api: SpeexJitterApi = SpeexJitterNative,
) : AutoCloseable {
    private val handle = NativeHandle({ api.init(stepSize) }, api::destroy)
    private val meta = IntArray(META_SIZE)
    private val scratch = IntArray(1)

    fun put(data: ByteArray, length: Int, timestamp: Int, span: Int, sequence: Int, userData: Int) =
        api.put(handle.value, data, length, timestamp, span, sequence, userData)

    /** Takes the next packet into [out]; returns a `JITTER_BUFFER_*` status. */
    fun get(out: ByteArray, desiredSpan: Int): Int = api.get(handle.value, out, desiredSpan, meta)

    /** The length of the packet the last [get] delivered. */
    val packetLength: Int get() = meta[META_LENGTH]

    /** The user data of the packet the last [get] delivered. */
    val packetUserData: Int get() = meta[META_USER_DATA]

    val pointerTimestamp: Int
        get() = api.pointerTimestamp(handle.value)

    /** Runs `jitter_buffer_ctl` with an int argument and returns the (possibly updated) argument. */
    fun control(request: Int, value: Int): Int {
        scratch[0] = value
        api.ctl(handle.value, request, scratch)
        return scratch[0]
    }

    fun updateDelay(): Int = api.updateDelay(handle.value)

    fun tick() = api.tick(handle.value)

    override fun close() = handle.close()
}
