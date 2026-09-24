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
 * The capture path runs on the audio thread every 10 ms, so any per-frame allocation risks a GC
 * pause mid-frame. `getThreadAllocatedBytes` measures bytes per call over a window, so the claim
 * is "below [HALF_AN_OBJECT]", never "nothing". Each test first checks the instrument against a
 * known allocation, since a counter that stopped counting would read as a perfect result.
 */
class CaptureThreadAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Allocation-free; the probability is pre-boxed, so a chain of these measures only the chain. */
    private class SilentStage(private val probability: Float?) : CapturePreprocessor {
        var frames = 0
            private set

        override fun process(frame: ShortArray): Float? {
            frames++
            return probability
        }

        override fun release() = Unit
    }

    /** Allocation-free, unlike `FakeSpeexPreprocessApi`, which records every call. */
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
    fun `the capture frame path allocates under half an object per frame`() {
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
     * [SpeexPreprocessor] answers an integer percent, so its probabilities come from a pre-boxed
     * table. The cold window matters: a per-frame `Float` box measures ~16 B cold but only ~2.5 B
     * hot, because C2 scalar-replaces most boxes (ART does not).
     */
    @Test
    fun `the speex stage allocates under half an object per frame`() {
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
     * Allocation-free fakes. Values cycle through a small table so C2 cannot constant-fold away the
     * box these stages are measured for.
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

    /** Swallows the frame, so a chunker measured through it measures itself. */
    private class SilentFarEndSink : FarEndSink {
        var frames = 0
            private set

        override fun analyzeReverseStream(frame: ShortArray) {
            frames++
        }
    }

    /**
     * RNNoise and the APM return continuous values, so one `Float` box (16 B) per frame is
     * accepted; anything beyond that single box fails. The far-end path and the chunker have no box
     * and get the stricter threshold.
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
     * The detector only unboxes an already boxed `Float?` and the pipeline over `NoopPreprocessor`
     * boxes nothing, so both get the strict threshold. Both detector modes are measured because
     * they take different branches of `isVoice`.
     */
    @Test
    fun `the detector and the pipeline allocate under half an object per frame`() {
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
     * Both windows are needed. Once hot, C2's escape analysis removes short-lived allocations such
     * as a for-in `Iterator` (32 B cold, 0 B hot) that ART would still make; the hot window still
     * catches what escape analysis cannot remove.
     */
    private fun cold(body: () -> Unit) = bytesPerCall(warmups = 1_000, iterations = 8_000, body = body)

    private fun hot(body: () -> Unit) = bytesPerCall(warmups = 200_000, iterations = 200_000, body = body)

    private fun bytesPerCall(warmups: Int, iterations: Int, body: () -> Unit): Double {
        repeat(warmups) { body() }
        val self = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(self)
        repeat(iterations) { body() }
        val after = threads.getThreadAllocatedBytes(self)
        return (after - before).toDouble() / iterations
    }

    private companion object {
        const val FRAME_SIZE = 480

        /**
         * Half the smallest JVM object (16 B): separates a per-frame allocation from a one-off
         * allocation elsewhere on the thread. Observed floor readings are at most ~0.12 B per call
         * (a one-off per window), so `isEqualTo(0.0)` would be flaky. An allocation every few
         * frames can pass; that is within the accepted boxing budget.
         */
        const val HALF_AN_OBJECT = 8.0

        /** One `java.lang.Float` (16 B) plus the measurement floor; a second allocation fails. */
        const val ONE_BOXED_FLOAT = 16.0 + HALF_AN_OBJECT
    }
}
