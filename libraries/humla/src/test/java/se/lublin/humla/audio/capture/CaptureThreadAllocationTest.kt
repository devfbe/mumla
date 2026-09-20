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
import java.lang.management.ManagementFactory

/**
 * The capture path runs on the audio thread once every 10 ms. Every byte it allocates there is a
 * future garbage collection pause in the middle of a frame, which is a dropout the user hears --
 * so the frame path of the pipeline skeleton allocates nothing at all, and this measures it
 * rather than asserting it in a comment.
 *
 * `getThreadAllocatedBytes` counts bytes this thread handed to the allocator, so it sees the
 * things that are easy to write by accident and impossible to see by reading: the `Iterator` of a
 * `for (x in aList)`, the `Ref.ObjectRef` Kotlin creates for a local `var` captured by a lambda,
 * and the `java.lang.Float` box of a nullable float. The first assertion checks the instrument
 * itself against a known allocation, because a measurement tool that has quietly stopped counting
 * reads as a perfect result.
 */
class CaptureThreadAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Allocates nothing itself, so what a chain of these measures is the chain. */
    private class SilentStage(private val probability: Float?) : CapturePreprocessor {
        var frames = 0
            private set

        override fun process(frame: ShortArray): Float? {
            frames++
            return probability
        }

        override fun release() = Unit
    }

    private class SilentHandleStage : SingleHandleStage(1L, "allocation test stage"), FarEndSink {
        private val probability: Float? = 0.5f
        override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? = probability
        override fun onFarEndFrame(handle: Long, frame: ShortArray) = Unit
        override fun onReleaseHandle(handle: Long) = Unit
        override fun analyzeReverseStream(frame: ShortArray) = farEnd(frame)
    }

    @Test
    fun `the capture frame path allocates nothing`() {
        assertThat(threads.isThreadAllocatedMemorySupported).isTrue()
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()

        val sink = arrayOfNulls<Any>(1)
        val instrument = hot { sink[0] = ShortArray(FRAME_SIZE) }
        assertWithMessage("the allocation counter is not counting; every result below would be a false green")
            .that(instrument).isAtLeast(FRAME_SIZE.toDouble())

        val frame = ShortArray(FRAME_SIZE)
        val chain = ChainedPreprocessor(
            listOf(SilentStage(null), SilentStage(0.25f), SilentStage(0.75f))
        )
        val stage = SilentHandleStage()

        val chainedCold = cold { chain.process(frame) }
        val chainedHot = hot { chain.process(frame) }
        val captureCold = cold { stage.process(frame) }
        val captureHot = hot { stage.process(frame) }
        val renderCold = cold { stage.analyzeReverseStream(frame) }
        val renderHot = hot { stage.analyzeReverseStream(frame) }

        println(
            "allocation per 10 ms frame (cold / hot): instrument baseline ${"%.1f".format(instrument)} B, " +
                "ChainedPreprocessor over 3 stages ${"%.3f".format(chainedCold)} / ${"%.3f".format(chainedHot)} B, " +
                "SingleHandleStage capture ${"%.3f".format(captureCold)} / ${"%.3f".format(captureHot)} B, " +
                "render ${"%.3f".format(renderCold)} / ${"%.3f".format(renderHot)} B"
        )

        assertWithMessage("ChainedPreprocessor.process allocates on the audio thread")
            .that(maxOf(chainedCold, chainedHot)).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("SingleHandleStage.process allocates on the audio thread")
            .that(maxOf(captureCold, captureHot)).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("SingleHandleStage's far-end path allocates on the audio thread")
            .that(maxOf(renderCold, renderHot)).isLessThan(HALF_AN_OBJECT)
    }

    /**
     * Both windows are needed, and the cold one is the load-bearing half.
     *
     * HotSpot's C2 does escape analysis: once the loop is hot it scalar-replaces a short-lived
     * `Iterator`, so a `for (x in aList)` on the frame path measures 0 B and the measurement says
     * the opposite of the truth. Measured here -- with only the hot window, swapping the chain's
     * array for a `List` and a for-in survives the test. ART's optimizing compiler does no such
     * elimination for an escaping-by-default interface iterator, so the device would allocate the
     * iterator the JVM optimised away.
     *
     * The cold window measures before C2 gets there, which is where that allocation is still
     * visible; the hot window keeps catching everything escape analysis cannot remove (a `Ref`
     * holder written by a lambda, a boxed float, anything that leaves the method).
     */
    private fun cold(body: () -> Unit) = bytesPerCall(warmups = 1_000, iterations = 8_000, body = body)

    private fun hot(body: () -> Unit) = bytesPerCall(warmups = 200_000, iterations = 200_000, body = body)

    private fun bytesPerCall(warmups: Int, iterations: Int, body: () -> Unit): Double {
        repeat(warmups) { body() }   // load the classes and bootstrap the lambdas, at least
        val self = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(self)
        repeat(iterations) { body() }
        val after = threads.getThreadAllocatedBytes(self)
        return (after - before).toDouble() / iterations
    }

    private companion object {
        const val FRAME_SIZE = 480

        /**
         * The threshold is half of the smallest object the JVM can allocate (16 B), so it
         * separates "this path allocates something on every frame" from "something allocated once
         * somewhere else on this thread" -- and it is the *claim* the test makes, not a budget.
         *
         * A window has a noise floor: measured here, the cold window read 0.238 B per call once in
         * nine runs, which is one stray ~950 B allocation in an 8 000-call window (JIT bookkeeping
         * on the measuring thread), not a per-call cost. Asserting an exact 0.0 therefore fails
         * about one run in ten for a reason that has nothing to do with the code under test.
         * Against that floor this threshold has 30x of room; against the smallest real defect it
         * would have to catch -- a 32 B iterator per frame, measured at 32.0 B per call with the
         * array swapped for a list -- it has 4x.
         */
        const val HALF_AN_OBJECT = 8.0
    }
}
