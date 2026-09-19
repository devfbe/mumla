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

import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative

/** Object wrapper around the speexdsp jitter buffer (replaces the old generated `Speex.JitterBuffer`). */
class SpeexJitterBuffer @JvmOverloads constructor(
    stepSize: Int,
    private val api: SpeexJitterApi = SpeexJitterNative,
) {
    /** Result of [get]: [status] is a `JITTER_BUFFER_*` code; [length] and [userData] come from the packet. */
    class Packet(val status: Int, val length: Int, val userData: Int)

    private val handle: Long = api.init(stepSize)
    private val meta = IntArray(5)
    private val scratch = IntArray(1)

    fun put(data: ByteArray, length: Int, timestamp: Int, span: Int, sequence: Int, userData: Int) =
        api.put(handle, data, length, timestamp, span, sequence, userData)

    fun get(out: ByteArray, desiredSpan: Int): Packet {
        val status = api.get(handle, out, desiredSpan, meta)
        return Packet(status, meta[0], meta[4])
    }

    val pointerTimestamp: Int
        get() = api.pointerTimestamp(handle)

    /** Runs `jitter_buffer_ctl` with an int argument and returns the (possibly updated) argument. */
    fun control(request: Int, value: Int): Int {
        scratch[0] = value
        api.ctl(handle, request, scratch)
        return scratch[0]
    }

    fun updateDelay(): Int = api.updateDelay(handle)

    fun tick() = api.tick(handle)

    fun destroy() = api.destroy(handle)
}
