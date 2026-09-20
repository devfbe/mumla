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
import org.junit.Test
import se.lublin.humla.audio.capture.fakes.FakePreprocessor

class ChainedPreprocessorTest {
    @Test
    fun `stages run in order and modify the same frame`() {
        val doubler = FakePreprocessor(transform = { f -> for (i in f.indices) f[i] = (f[i] * 2).toShort() })
        val plusOne = FakePreprocessor(transform = { f -> for (i in f.indices) f[i] = (f[i] + 1).toShort() })
        val chain = ChainedPreprocessor(listOf(doubler, plusOne))
        val frame = shortArrayOf(10, 20)

        chain.process(frame)

        assertThat(frame.toList()).containsExactly(21.toShort(), 41.toShort()).inOrder() // (x*2)+1, not (x+1)*2
        assertThat(plusOne.frames.single().toList()).containsExactly(20.toShort(), 40.toShort()).inOrder()
    }

    /**
     * The same two stages the other way round, so the pass is not an artefact of one arithmetic.
     *
     * It is honestly the weaker of the two, and the comment that used to stand here said the
     * opposite: it claimed the test above alone is "also passed by a chain that reverses or sorts
     * the list". Measured -- `stages.toTypedArray().reversedArray()` turns **three** tests red,
     * the test above among them. On a two-stage chain every reordering this class could commit
     * shows up in the first test as well, so nothing kills this one alone. It is a second sample,
     * not a second pin, and calling it a second pin is what would let a later reader delete the
     * first one.
     *
     * The order is semantics rather than taste either way: see the class KDoc on why echo
     * cancellation has to run before noise suppression.
     */
    @Test
    fun `the chain does not impose an order of its own`() {
        val doubler = FakePreprocessor(transform = { f -> for (i in f.indices) f[i] = (f[i] * 2).toShort() })
        val plusOne = FakePreprocessor(transform = { f -> for (i in f.indices) f[i] = (f[i] + 1).toShort() })
        val frame = shortArrayOf(10, 20)

        ChainedPreprocessor(listOf(plusOne, doubler)).process(frame)

        assertThat(frame.toList()).containsExactly(22.toShort(), 42.toShort()).inOrder() // (x+1)*2
    }

    @Test
    fun `probability comes from the last stage that provides one`() {
        val chain = ChainedPreprocessor(listOf(FakePreprocessor(0.2f), FakePreprocessor(0.9f), FakePreprocessor(null)))
        assertThat(chain.process(ShortArray(480))).isEqualTo(0.9f)
    }

    @Test
    fun `probability is null when no stage provides one`() {
        val chain = ChainedPreprocessor(listOf(FakePreprocessor(null), FakePreprocessor(null)))
        assertThat(chain.process(ShortArray(480))).isNull()
    }

    @Test
    fun `release releases every stage`() {
        val a = FakePreprocessor(); val b = FakePreprocessor()
        ChainedPreprocessor(listOf(a, b)).release()
        assertThat(a.released).isTrue()
        assertThat(b.released).isTrue()
    }

    /** Off has to be really off: no stage left to run "with neutral parameters". */
    @Test
    fun `an empty chain touches nothing and has no opinion`() {
        val frame = shortArrayOf(1, -2, 3)
        val chain = ChainedPreprocessor(emptyList())

        assertThat(chain.process(frame)).isNull()
        chain.release()

        assertThat(frame.toList()).containsExactly(1.toShort(), (-2).toShort(), 3.toShort()).inOrder()
    }

    /**
     * The chain copies the list it is handed. A caller that keeps a mutable list and adds to it
     * later would otherwise be changing the pipeline under the capture thread, with no lock and no
     * announcement -- and the list it passed is exactly the list the factory built stage by stage.
     */
    @Test
    fun `later changes to the caller's list do not reach the chain`() {
        val stages = mutableListOf<CapturePreprocessor>(FakePreprocessor(0.3f))
        val chain = ChainedPreprocessor(stages)
        val added = FakePreprocessor(0.8f)

        stages += added

        assertThat(chain.process(ShortArray(480))).isEqualTo(0.3f)
        assertThat(added.frames).isEmpty()
    }

    @Test
    fun `noop preprocessor leaves the frame untouched and has no opinion`() {
        val frame = shortArrayOf(1, -2, 3)
        assertThat(NoopPreprocessor.process(frame)).isNull()
        assertThat(frame.toList()).containsExactly(1.toShort(), (-2).toShort(), 3.toShort()).inOrder()
    }
}
