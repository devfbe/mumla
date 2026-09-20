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
 * Re-blocks the playback thread's arbitrarily sized mix buffers into exact [frameSize] frames for
 * a [FarEndSink].
 *
 * The APM will not take anything else: a frame shorter than 10 ms is refused with -8, and a longer
 * one has its tail ignored. Both are silent -- the reverse stream has no return value to carry
 * them -- and either costs about 21 dB of echo cancellation, which is the whole benefit
 * (`WebRtcApmPreprocessor.rejectedFarEndFrames` is where a refusal becomes visible).
 *
 * ### Not thread-safe, and it does not need to be
 *
 * [pending] and [filled] are touched only by [push], which is called from the playback thread and
 * from nowhere else. The sink it feeds *is* shared -- [SingleHandleStage] takes its one lock
 * around the native call -- so what crosses threads is locked and what is not locked never
 * crosses. One chunker belongs to one playback thread; a mode switch builds a new one rather than
 * repointing this one.
 *
 * ### The copy is not an inefficiency
 *
 * [pending] is one buffer for the life of the chunker, so the playback thread allocates nothing
 * per frame. It is also never bypassed: handing the caller's own array to the sink when a push
 * happens to be an exact multiple of [frameSize] would save a copy and put the APM's render-side
 * processing -- which **may modify the frame in place** -- into the buffer that is on its way to
 * the speaker.
 *
 * The other half of that: the sink is handed the same array every time, so a sink that keeps it
 * keeps a buffer that is about to be overwritten.
 */
class FarEndFrameChunker(private val frameSize: Int, private val sink: FarEndSink) {
    private val pending = ShortArray(frameSize)
    private var filled = 0

    /**
     * Takes the first [length] samples of [samples] and hands every complete frame they make up to
     * the sink, in order. What is left over waits here for the next push.
     *
     * [length] past the end of [samples] is trimmed rather than thrown, for the reason the whole
     * capture path refuses instead of throwing: `System.arraycopy` would throw on the playback
     * thread, once per 10 ms, and the thread that dies is the one playing audio. The valid prefix
     * is used; nothing is invented to fill the rest.
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
