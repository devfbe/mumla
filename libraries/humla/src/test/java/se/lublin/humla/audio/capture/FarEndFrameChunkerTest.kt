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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Random
import se.lublin.humla.audio.capture.fakes.FakeWebRtcApmApi
import se.lublin.humla.audio.capture.fakes.RecordingFarEndSink

/**
 * The playback thread hands over whatever the mixer produced; AEC3 needs exactly 10 ms frames, in
 * order, with nothing inserted and nothing lost.
 *
 * **What goes wrong if this is wrong, and why a spot check is not enough.** The native host test
 * measured all three ways of getting the reference stream wrong -- never feeding it, feeding the
 * wrong buffer, feeding it 200 ms late -- at about 21 dB of lost echo cancellation each, and every
 * one of them is silent: every call still returns 0. The same is true one layer up here. A chunker
 * that drops a remainder, repeats a sample or reorders two frames produces a reference stream that
 * is *almost* the playback signal, and nothing above this file can tell. So the load-bearing test
 * is not the two hand-written cases; it is the one that pushes 4 096 buffers of pseudo-random
 * length, reassembles every delivered frame and compares the result against the source stream.
 * That is the JVM's version of the 21 dB measurement:
 * any misalignment at all is a mismatch, rather than a number that has to be far enough from
 * another number.
 */
class FarEndFrameChunkerTest {
    private companion object {
        /** Long enough that a misalignment cannot hide: about 1 000 frames, against the 700 the
         *  native AEC3 measurement needed before a swapped reference separated from a correct one. */
        const val PUSHES = 1_024
    }

    private val sink = RecordingFarEndSink()

    @Test
    fun `regroups arbitrary buffers into exact frames, in order`() {
        val chunker = FarEndFrameChunker(480, sink)
        val first = ShortArray(1000) { it.toShort() }
        val second = ShortArray(440) { (2000 + it).toShort() }

        chunker.push(first, first.size)

        assertThat(sink.frames).hasSize(2)
        assertThat(sink.frames[0][0]).isEqualTo(0.toShort())
        assertThat(sink.frames[1][0]).isEqualTo(480.toShort())
        assertThat(sink.frames[1][479]).isEqualTo(959.toShort())

        chunker.push(second, second.size)

        assertThat(sink.frames).hasSize(3)
        // The 40 samples left over from the first push come first.
        assertThat(sink.frames[2][0]).isEqualTo(960.toShort())
        assertThat(sink.frames[2][40]).isEqualTo(2000.toShort())
        assertThat(sink.frames[2][479]).isEqualTo(2439.toShort())
    }

    @Test
    fun `only the first length samples of the buffer are used`() {
        FarEndFrameChunker(4, sink).push(shortArrayOf(1, 2, 3, 4, 99, 99), 4)

        assertThat(sink.frames.single().toList())
            .containsExactly(1.toShort(), 2.toShort(), 3.toShort(), 4.toShort()).inOrder()
    }

    @Test
    fun `a partial buffer is held back until a later push completes it`() {
        val chunker = FarEndFrameChunker(4, sink)

        chunker.push(shortArrayOf(1, 2), 2)
        assertWithMessage("an incomplete frame must not reach AEC3").that(sink.frames).isEmpty()

        chunker.push(shortArrayOf(3), 1)
        assertThat(sink.frames).isEmpty()

        chunker.push(shortArrayOf(4, 5), 2)
        assertThat(sink.frames.single().toList())
            .containsExactly(1.toShort(), 2.toShort(), 3.toShort(), 4.toShort()).inOrder()
    }

    @Test
    fun `an empty push delivers nothing`() {
        FarEndFrameChunker(4, sink).push(ShortArray(0), 0)

        assertThat(sink.frames).isEmpty()
    }

    /**
     * The measurement. 4 096 pushes of pseudo-random length reassembled into one stream: every
     * complete frame that could be built must have been delivered, in order, sample for sample,
     * and the remainder must still be waiting rather than lost or guessed at.
     *
     * A dropped remainder, a repeated sample, a swapped pair of frames and an off-by-one in the
     * copy length all break this and none of them break the two hand-written cases above.
     */
    @Test
    fun `the delivered stream is the pushed stream, sample for sample, over random buffer sizes`() {
        val frameSize = 480
        val chunker = FarEndFrameChunker(frameSize, sink)
        val random = Random(20260920L)
        val pushed = ShortArray(PUSHES * (2 * frameSize + 1))
        var pushedCount = 0

        repeat(PUSHES) {
            val length = random.nextInt(2 * frameSize + 1)
            val buffer = ShortArray(length + random.nextInt(8)) { random.nextInt().toShort() }
            System.arraycopy(buffer, 0, pushed, pushedCount, length)
            pushedCount += length
            chunker.push(buffer, length)
        }

        assertWithMessage("every frame handed over must be exactly one 10 ms frame")
            .that(sink.frames.map { it.size }.distinct()).containsExactly(frameSize)
        val delivered = sink.frames.size * frameSize
        assertWithMessage("the chunker delivered %s samples of %s pushed", delivered, pushedCount)
            .that(delivered).isEqualTo(pushedCount / frameSize * frameSize)

        // Index by index, reporting the first divergence. Handing two ~500 000-element lists to
        // isEqualTo instead is a test that does not fail so much as stop: Truth renders both in
        // the failure message, and the run that measured it hit the suite timeout with no output
        // at all -- which is spec 4.05's "a removed guard can hang the suite instead of failing
        // it", in a test rather than in a native bridge.
        var firstDivergence = -1
        var index = 0
        for (frame in sink.frames) {
            for (sample in frame) {
                if (sample != pushed[index]) {
                    firstDivergence = index
                    break
                }
                index++
            }
            if (firstDivergence >= 0) break
        }
        assertWithMessage("the reference stream diverges from the playback stream at sample %s", firstDivergence)
            .that(firstDivergence).isEqualTo(-1)
    }

    /**
     * The optimisation nobody may add: when a push happens to be an exact multiple of the frame
     * size, handing the caller's array straight to the sink saves a copy -- and `processRender`
     * **may modify the frame in place**. The buffer the playback thread handed over is the buffer
     * it is about to write to the audio device, so the APM's render-side processing would land in
     * the user's speakers.
     */
    @Test
    fun `the frame handed to the sink is never the caller's own buffer`() {
        val buffer = ShortArray(8) { it.toShort() }

        FarEndFrameChunker(4, sink).push(buffer, 8)

        assertThat(sink.frames).hasSize(2)
        assertThat(sink.buffers.map { it === buffer }).containsExactly(false, false)
    }

    /**
     * One reusable buffer, because this runs on the playback thread once per 10 ms and a garbage
     * collection between two frames is a dropout. The consequence is part of the contract: a sink
     * that keeps the array it is handed keeps a buffer that will be overwritten.
     */
    @Test
    fun `the same buffer is reused for every frame`() {
        FarEndFrameChunker(4, sink).push(ShortArray(12), 12)

        assertThat(sink.buffers).hasSize(3)
        assertThat(sink.buffers.distinct()).hasSize(1)
    }

    /**
     * A length past the end of the buffer is a caller's bug, and the honest place to find it is a
     * failing test rather than the playback thread: `System.arraycopy` would throw there, once per
     * 10 ms, and the thread that dies is the one playing audio. The valid prefix is used and the
     * rest is not invented.
     */
    @Test
    fun `a length past the end of the buffer is trimmed instead of throwing`() {
        FarEndFrameChunker(4, sink).push(shortArrayOf(1, 2, 3, 4, 5, 6), 99)

        assertThat(sink.frames.single().toList())
            .containsExactly(1.toShort(), 2.toShort(), 3.toShort(), 4.toShort()).inOrder()
    }

    /**
     * The far-end path of a released stage drops its frames (`SingleHandleStage`), so a chunker
     * that outlives a mode switch must not be the thing that crashes: it keeps chunking into a
     * sink that does nothing, which is what makes a swap survivable.
     */
    @Test
    fun `a chunker whose sink has been released keeps working`() {
        val stage = WebRtcApmPreprocessor(
            FakeWebRtcApmApi(),
            WebRtcApmConfig.FOR_ECHO_CANCELLATION,
        )
        val chunker = FarEndFrameChunker(480, stage)
        stage.release()

        chunker.push(ShortArray(960), 960)

        assertThat(stage.rejectedFarEndFrames).isEqualTo(0)
    }
}
