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

    /**
     * Allocates nothing itself, so what a chain of these measures is the chain.
     *
     * Note what that excludes, because 0.000 B here is easy to read as more than it is: the
     * probability is a **pre-boxed** `Float?` held in a field, so returning it allocates nothing.
     * A stage that *computes* a probability boxes 16 B per frame, which is a real cost this
     * measurement deliberately does not contain -- spec §4.1 decided to pay it (about 4.8 KB/s for
     * three stages) and says why. What is measured here is the skeleton: the chain, the lock, and
     * the two entry points.
     */
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
     * iterator the JVM optimised away. Measured on this tree, three runs out of three: cold
     * 32.000 B per call (32.119 B once), hot 0.000 B.
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
         * Six consecutive runs on this machine, so the floor is a measurement rather than an
         * estimate:
         *
         * | reading                    | six runs                                     |
         * |----------------------------|----------------------------------------------|
         * | instrument baseline        | 976.0 B every run (a `ShortArray(480)` exactly) |
         * | chain, cold                | 0.000 B x4, 0.119 B x2                       |
         * | capture path, cold         | 0.016 B every run                            |
         * | far-end path, cold         | 0.000 B every run                            |
         * | all three, hot             | 0.000 B every run                            |
         *
         * Both non-zero readings are a fixed cost per *window*, not per call: 0.119 x 8 000 is one
         * stray 952 B allocation, and 0.016 x 8 000 is a reproducible 128 B that the cold window
         * pays once while it is still interpreting. So **`isEqualTo(0.0)` would have been red in
         * all six of those runs**, on the capture path, with nothing wrong. That is what the
         * threshold is for, and it is why it is not a matter of taste.
         *
         * Headroom, both directions: 67x above the largest floor reading, and 4x below the
         * smallest real defect it has to catch -- swapping the chain's array for a `List` and a
         * for-in measures 32.000 B per call cold (32.119 B once, the same stray on top) and
         * 0.000 B hot, three runs out of three.
         *
         * **What it does not carry:** 8.0 is the right line for "one object per frame". It is not
         * a line for one object every *three* frames, which reads 5.33 B per call and passes --
         * about 533 B/s on the audio thread. That is deliberate rather than overlooked: spec §4.1
         * already accepts about 4.8 KB/s for the `Float?` boxing of three stages, an order of
         * magnitude more, so a test that failed at 533 B/s would be stricter than the contract it
         * is testing. A per-frame allocation, which is what the mistakes in this file's KDoc all
         * produce, is at least 16 B and is caught.
         */
        const val HALF_AN_OBJECT = 8.0
    }
}
