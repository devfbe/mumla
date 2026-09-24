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

package se.lublin.humla.audio.capture

/**
 * Re-blocks the playback thread's arbitrarily sized mix buffers into exact [frameSize] frames for a
 * [FarEndSink]. The APM silently refuses shorter frames and ignores the tail of longer ones.
 *
 * Not thread-safe: owned by one playback thread. Reuses one internal buffer (no per-call allocation)
 * and always copies, because the APM's render processing may modify the frame in place and must not
 * touch the buffer headed for the speaker. The sink gets the same array each time and must not keep it.
 */
class FarEndFrameChunker(private val frameSize: Int, private val sink: FarEndSink) {
    private val pending = ShortArray(frameSize)
    private var filled = 0

    /**
     * Hands every complete frame in the first [length] samples of [samples] to the sink, in order; the
     * remainder waits for the next push. A [length] past the end of [samples] is trimmed rather than
     * thrown, so the playback thread never dies on it.
     */
    fun push(samples: ShortArray, length: Int) {
        val available = minOf(length, samples.size)
        var offset = 0
        while (offset < available) {
            val n = minOf(frameSize - filled, available - offset)
            System.arraycopy(samples, offset, pending, filled, n)
            filled += n
            offset += n
            if (filled == frameSize) {
                sink.analyzeReverseStream(pending)
                filled = 0
            }
        }
    }
}
