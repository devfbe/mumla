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
 * order, with nothing inserted and nothing lost. A reference stream that is only almost right fails
 * silently (about 21 dB of lost echo cancellation), hence the sample-exact random sweep below.
 */
class FarEndFrameChunkerTest {
    private companion object {
        /** About 1 000 frames, enough that any misalignment shows up. */
        const val PUSHES = 1_024

        /**
         * `AudioOutput` sizes its mix buffer `minOf(minBufferSizeSamples, AudioHandler.FRAME_SIZE * 12)`,
         * so a push is anywhere from 0 to 12 frames.
         */
        const val MAX_PUSH_FRAMES = 12
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
     * [PUSHES] pushes of pseudo-random length reassembled into one stream: every complete frame must
     * have been delivered, in order, sample for sample, with the remainder still pending.
     */
    @Test
    fun `the delivered stream is the pushed stream, sample for sample, over random buffer sizes`() {
        val frameSize = 480
        val chunker = FarEndFrameChunker(frameSize, sink)
        val random = Random(20260920L)
        val pushed = ShortArray(PUSHES * (MAX_PUSH_FRAMES * frameSize + 1))
        var pushedCount = 0

        repeat(PUSHES) {
            val length = random.nextInt(MAX_PUSH_FRAMES * frameSize + 1)
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

        // Index by index, reporting the first divergence: comparing the two ~3M-element lists with
        // isEqualTo takes minutes and produces a huge failure message.
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
     * `processRender` may modify the frame in place, and the caller's buffer is about to be written
     * to the audio device, so even an exact-multiple push must be copied.
     */
    @Test
    fun `the frame handed to the sink is never the caller's own buffer`() {
        val buffer = ShortArray(8) { it.toShort() }

        FarEndFrameChunker(4, sink).push(buffer, 8)

        assertThat(sink.frames).hasSize(2)
        assertThat(sink.buffers.map { it === buffer }).containsExactly(false, false)
    }

    /**
     * One reusable buffer, since this runs on the playback thread every 10 ms. A sink must not keep
     * the array it is handed.
     */
    @Test
    fun `the same buffer is reused for every frame`() {
        FarEndFrameChunker(4, sink).push(ShortArray(12), 12)

        assertThat(sink.buffers).hasSize(3)
        assertWithMessage(
            "reference identity, not contents: `distinct()` on ShortArray compares by identity, " +
                "which is the axis this pins -- three equal-but-separate arrays would fail here " +
                "and no assertion about the samples could tell them apart"
        ).that(sink.buffers.distinct()).hasSize(1)
    }

    /**
     * A length past the end is a caller bug, but throwing would kill the playback thread; the valid
     * prefix is used and nothing is invented.
     */
    @Test
    fun `a length past the end of the buffer is trimmed instead of throwing`() {
        FarEndFrameChunker(4, sink).push(shortArrayOf(1, 2, 3, 4, 5, 6), 99)

        assertThat(sink.frames.single().toList())
            .containsExactly(1.toShort(), 2.toShort(), 3.toShort(), 4.toShort()).inOrder()
    }

    /** A released stage drops far-end frames, so a chunker outliving a mode switch keeps working. */
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
