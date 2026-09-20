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
 * libspeexdsp adaptive jitter buffer. [put] copies `len` bytes of `data` (the buffer keeps its own
 * copy). [get] writes the packet into `out` and fills `meta` with
 * `[len, timestamp, span, sequence, userData]`; it returns a `JITTER_BUFFER_*` status.
 */
interface SpeexJitterApi {
    fun init(stepSize: Int): Long
    fun destroy(handle: Long)
    fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int)
    fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int
    fun pointerTimestamp(handle: Long): Int
    fun tick(handle: Long)

    /**
     * Runs `jitter_buffer_ctl` with `value[0]` as the in/out int argument.
     *
     * [request] has to be one of [SpeexJitterNative.JITTER_BUFFER_SET_MARGIN],
     * [SpeexJitterNative.JITTER_BUFFER_GET_MARGIN] or
     * [SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT] -- those are the requests the bridge
     * allows through, and any other number is refused with `JITTER_BUFFER_BAD_ARGUMENT` without
     * reaching libspeexdsp. The bridge hands it the address of a four-byte int on its own stack
     * frame, and `GET_DESTROY_CALLBACK` writes eight bytes through that address while
     * `SET_DESTROY_CALLBACK` keeps it as the function `jitter_buffer_destroy` later calls.
     */
    fun ctl(handle: Long, request: Int, value: IntArray): Int
    fun updateDelay(handle: Long): Int
}

object SpeexJitterNative : SpeexJitterApi {
    // The first five are statuses [get] returns, the last three are [ctl] requests; only the
    // requests are on the bridge's allow list.
    const val JITTER_BUFFER_OK = 0
    const val JITTER_BUFFER_MISSING = 1

    /** speexdsp's own name for 2 (`speex_jitter.h:74`); libspeex called it `INCOMPLETE`. */
    const val JITTER_BUFFER_INSERTION = 2
    const val JITTER_BUFFER_INTERNAL_ERROR = -1
    const val JITTER_BUFFER_BAD_ARGUMENT = -2
    const val JITTER_BUFFER_SET_MARGIN = 0
    const val JITTER_BUFFER_GET_MARGIN = 1
    const val JITTER_BUFFER_GET_AVAILABLE_COUNT = 3

    init {
        System.loadLibrary("humla_speexdsp")
    }

    external override fun init(stepSize: Int): Long
    external override fun destroy(handle: Long)
    external override fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int)
    external override fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int
    external override fun pointerTimestamp(handle: Long): Int
    external override fun tick(handle: Long)
    external override fun ctl(handle: Long, request: Int, value: IntArray): Int
    external override fun updateDelay(handle: Long): Int
}
