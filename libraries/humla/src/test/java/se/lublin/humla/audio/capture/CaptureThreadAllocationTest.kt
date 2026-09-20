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
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.native.RnnoiseApi
import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.WebRtcApmApi

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

    /**
     * Answers like `jni_speexdsp.cpp` does and allocates nothing itself, so what a stage built on
     * it measures is the stage. `FakeSpeexPreprocessApi` cannot be used here: it records every
     * call into a list, which allocates on the frame path and would be measured as the stage's.
     */
    private class SilentSpeexApi : SpeexPreprocessApi {
        override fun init(frameSize: Int, sampleRate: Int): Long = 1L
        override fun run(state: Long, frame: ShortArray): Int = 0
        override fun ctlInt(state: Long, request: Int, value: IntArray): Int {
            value[0] = 50
            return 0
        }
        override fun destroy(state: Long) = Unit
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
     * The first real stage, and the first one that *computes* a probability -- which the test
     * above deliberately does not cover, because a computed `Float?` is a 16 B box per frame and
     * the skeleton never makes one.
     *
     * [SpeexPreprocessor] does not pay it: `SPEEX_PREPROCESS_GET_PROB` answers an integer percent,
     * so the whole range of its return values is 101 pre-boxed objects, and the `int` it passes to
     * `ctlInt` travels in an array the stage allocates once. Four runs on this machine: cold
     * 0.066 B x3 and 0.185 B once, hot 0.000 B every run -- a fixed cost per window (528 B and
     * 1 480 B over 8 000 calls), not a cost per frame.
     *
     * Both windows are measured, and here the cold one is not merely the load-bearing half, it is
     * the **only** half that works. Returning `coerceIn(0, 100) / 100f` directly instead of the
     * table -- the same behaviour, one `java.lang.Float` per frame -- measures **16.066 B cold and
     * 2.451 B hot**: C2 scalar-replaces about six boxes in seven once the loop is hot, so the hot
     * reading lands under this file's threshold and a hot-only test would have called that defect
     * green. ART does no such elimination.
     */
    @Test
    fun `the speex stage allocates nothing per frame`() {
        assertThat(threads.isThreadAllocatedMemorySupported).isTrue()
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()

        val sink = arrayOfNulls<Any>(1)
        val instrument = hot { sink[0] = ShortArray(FRAME_SIZE) }
        assertWithMessage("the allocation counter is not counting; every result below would be a false green")
            .that(instrument).isAtLeast(FRAME_SIZE.toDouble())

        val frame = ShortArray(FRAME_SIZE)
        val stage = SpeexPreprocessor(SilentSpeexApi())

        val speexCold = cold { stage.process(frame) }
        val speexHot = hot { stage.process(frame) }

        println(
            "allocation per 10 ms frame (cold / hot): instrument baseline ${"%.1f".format(instrument)} B, " +
                "SpeexPreprocessor ${"%.3f".format(speexCold)} / ${"%.3f".format(speexHot)} B"
        )

        assertWithMessage("SpeexPreprocessor.process allocates on the audio thread")
            .that(maxOf(speexCold, speexHot)).isLessThan(HALF_AN_OBJECT)
    }

    /**
     * Answers like `jni_rnnoise.cpp` and `jni_webrtc_apm.cpp` do and allocate nothing themselves.
     *
     * The probabilities and levels cycle through a small primitive table rather than being
     * constant: a constant would let C2 fold the value and, with it, possibly the box that is the
     * one thing these two stages are measured for.
     */
    private class SilentRnnoiseApi : RnnoiseApi {
        private var next = 0
        override fun create(): Long = 1L
        override fun processFrame(handle: Long, frame: ShortArray): Float =
            PROBABILITIES[next++ and (PROBABILITIES.size - 1)]
        override fun destroy(handle: Long) = Unit

        private companion object {
            val PROBABILITIES = FloatArray(16) { it / 16f }
        }
    }

    private class SilentApmApi : WebRtcApmApi {
        private var next = 0
        override fun create(
            sampleRate: Int,
            echoCancellation: Boolean,
            noiseSuppression: Boolean,
            noiseSuppressionLevel: Int,
            gainControl: Boolean,
            highPass: Boolean,
        ): Long = 1L
        override fun frameSize(handle: Long): Int = FRAME_SIZE
        override fun processCapture(handle: Long, frame: ShortArray): Int = 0
        override fun processRender(handle: Long, frame: ShortArray): Int = 0
        override fun lastCaptureLevelDbfs(handle: Long): Float =
            LEVELS[next++ and (LEVELS.size - 1)]
        override fun destroy(handle: Long) = Unit

        private companion object {
            val LEVELS = FloatArray(16) { -50f + it }
        }
    }

    /** Swallows the frame without keeping it, so a chunker measured through it measures itself. */
    private class SilentFarEndSink : FarEndSink {
        var frames = 0
            private set

        override fun analyzeReverseStream(frame: ShortArray) {
            frames++
        }
    }

    /**
     * The two stages that **compute** a probability, which is the cost the first test in this file
     * deliberately does not contain and `SpeexPreprocessor` is able to avoid.
     *
     * Neither of these can avoid it. RNNoise answers a continuous probability from its model and
     * the APM answers a continuous level, so there is no finite set of return values to pre-box the
     * way speex's integer percent allows -- one `java.lang.Float` per frame, 16 B, is the floor
     * rather than a defect. Spec §4.1 decided to pay it (about 1.6 KB/s per stage on the audio
     * thread, two to three orders of magnitude below what moves ART's allocation-triggered
     * collection) rather than trade `Float?` for a primitive with a NaN sentinel.
     *
     * So the claim here is **"at most that one box, and nothing else"**, not "nothing": the
     * threshold is one object plus the same half-object of measurement noise the rest of this file
     * uses. What it still catches is everything that would be a *second* allocation per frame -- a
     * scratch array, a captured local, a lambda, a `Pair`, an iterator.
     *
     * Both windows are measured, and the hot one is worth reading rather than skipping: the same
     * C2 escape analysis that made task 5's hot-only measurement a lie is visible here as the box
     * being partly eliminated, so the hot number lands *below* 16 B without the device doing any
     * such thing. The far-end path and the chunker have no box to pay and are held to the same
     * `HALF_AN_OBJECT` as the skeleton.
     */
    @Test
    fun `the rnnoise and apm stages allocate no more than the one accepted box`() {
        assertThat(threads.isThreadAllocatedMemorySupported).isTrue()
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()

        val sink = arrayOfNulls<Any>(1)
        val instrument = hot { sink[0] = ShortArray(FRAME_SIZE) }
        assertWithMessage("the allocation counter is not counting; every result below would be a false green")
            .that(instrument).isAtLeast(FRAME_SIZE.toDouble())

        val frame = ShortArray(FRAME_SIZE)
        val rnnoise = RnnoisePreprocessor(SilentRnnoiseApi())
        val apm = WebRtcApmPreprocessor(SilentApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val farEndSink = SilentFarEndSink()
        val chunker = FarEndFrameChunker(FRAME_SIZE, farEndSink)

        val rnnoiseCold = cold { rnnoise.process(frame) }
        val rnnoiseHot = hot { rnnoise.process(frame) }
        val apmCold = cold { apm.process(frame) }
        val apmHot = hot { apm.process(frame) }
        val renderCold = cold { apm.analyzeReverseStream(frame) }
        val renderHot = hot { apm.analyzeReverseStream(frame) }
        val chunkerCold = cold { chunker.push(frame, FRAME_SIZE) }
        val chunkerHot = hot { chunker.push(frame, FRAME_SIZE) }

        println(
            "allocation per 10 ms frame (cold / hot): instrument baseline ${"%.1f".format(instrument)} B, " +
                "RnnoisePreprocessor ${"%.3f".format(rnnoiseCold)} / ${"%.3f".format(rnnoiseHot)} B, " +
                "WebRtcApmPreprocessor capture ${"%.3f".format(apmCold)} / ${"%.3f".format(apmHot)} B, " +
                "render ${"%.3f".format(renderCold)} / ${"%.3f".format(renderHot)} B, " +
                "FarEndFrameChunker ${"%.3f".format(chunkerCold)} / ${"%.3f".format(chunkerHot)} B"
        )

        assertWithMessage("RnnoisePreprocessor.process allocates more than the probability box")
            .that(maxOf(rnnoiseCold, rnnoiseHot)).isLessThan(ONE_BOXED_FLOAT)
        assertWithMessage("WebRtcApmPreprocessor.process allocates more than the probability box")
            .that(maxOf(apmCold, apmHot)).isLessThan(ONE_BOXED_FLOAT)
        assertWithMessage("WebRtcApmPreprocessor's far-end path allocates on the playback thread")
            .that(maxOf(renderCold, renderHot)).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("FarEndFrameChunker.push allocates on the playback thread")
            .that(maxOf(chunkerCold, chunkerHot)).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("the chunker must actually have produced frames")
            .that(farEndSink.frames).isGreaterThan(0)
    }

    /**
     * The two pieces task 7 and task 8 added to the frame path, and the reason this file is opened
     * by both: `VoiceActivityDetector` had no number at all -- its KDoc said so and named this test
     * as the instrument -- and `CapturePipeline` is now the thing that calls it once per 10 ms.
     *
     * Both are measured against the skeleton's `HALF_AN_OBJECT`, not against the box allowance: the
     * detector is handed a `Float?` that some stage already boxed and `?:` unboxes rather than
     * reboxing, and the pipeline over `NoopPreprocessor` has no probability to box at all. So what
     * is left for either of them to allocate is a mistake rather than a cost.
     *
     * The detector is measured in **both** modes, because they take different halves of `isVoice`:
     * `AMPLITUDE` runs the level loop over the frame, `PROBABILITY` takes the pre-boxed float and
     * skips it. A measurement of one says nothing about the other.
     */
    @Test
    fun `the detector and the pipeline allocate nothing per frame`() {
        assertThat(threads.isThreadAllocatedMemorySupported).isTrue()
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()

        val sink = arrayOfNulls<Any>(1)
        val instrument = hot { sink[0] = ShortArray(FRAME_SIZE) }
        assertWithMessage("the allocation counter is not counting; every result below would be a false green")
            .that(instrument).isAtLeast(FRAME_SIZE.toDouble())

        val frame = ShortArray(FRAME_SIZE) { (it % 997).toShort() }
        val amplitude = VoiceActivityDetector(VadConfig.amplitude(0.5f))
        val probability = VoiceActivityDetector(VadConfig.probability())
        val boxed: Float? = 0.8f
        val pipeline = CapturePipeline(null, NoopPreprocessor, ContinuousInputMode())

        val amplitudeCold = cold { amplitude.isVoice(frame, FRAME_SIZE, null) }
        val amplitudeHot = hot { amplitude.isVoice(frame, FRAME_SIZE, null) }
        val probabilityCold = cold { probability.isVoice(frame, FRAME_SIZE, boxed) }
        val probabilityHot = hot { probability.isVoice(frame, FRAME_SIZE, boxed) }
        val pipelineCold = cold { pipeline.process(frame, FRAME_SIZE) }
        val pipelineHot = hot { pipeline.process(frame, FRAME_SIZE) }

        println(
            "allocation per 10 ms frame (cold / hot): instrument baseline ${"%.1f".format(instrument)} B, " +
                "VoiceActivityDetector amplitude ${"%.3f".format(amplitudeCold)} / ${"%.3f".format(amplitudeHot)} B, " +
                "probability ${"%.3f".format(probabilityCold)} / ${"%.3f".format(probabilityHot)} B, " +
                "CapturePipeline ${"%.3f".format(pipelineCold)} / ${"%.3f".format(pipelineHot)} B"
        )

        assertWithMessage("VoiceActivityDetector.isVoice allocates on the audio thread in amplitude mode")
            .that(maxOf(amplitudeCold, amplitudeHot)).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("VoiceActivityDetector.isVoice allocates on the audio thread in probability mode")
            .that(maxOf(probabilityCold, probabilityHot)).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("CapturePipeline.process allocates on the audio thread")
            .that(maxOf(pipelineCold, pipelineHot)).isLessThan(HALF_AN_OBJECT)
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

        /**
         * One `java.lang.Float` (16 B) plus the same measurement floor. The claim it carries is
         * "at most the one box spec §4.1 accepts, and nothing else on top of it" -- a *second*
         * per-frame allocation of any kind is at least another 16 B and fails.
         */
        const val ONE_BOXED_FLOAT = 16.0 + HALF_AN_OBJECT
    }
}
