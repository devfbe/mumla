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
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.native.RnnoiseApi
import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.WebRtcApmApi
import se.lublin.humla.testutil.AllocationMeter
import se.lublin.humla.testutil.AllocationMeter.HALF_AN_OBJECT
import se.lublin.humla.testutil.AllocationMeter.checkInstrument
import se.lublin.humla.testutil.AllocationMeter.worstPerCall

/** The capture path runs on the audio thread every 10 ms; see [AllocationMeter]. */
class CaptureThreadAllocationTest {
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
        checkInstrument(FRAME_SIZE)
        val frame = ShortArray(FRAME_SIZE)
        val chain = ChainedPreprocessor(
            listOf(SilentStage(null), SilentStage(0.25f), SilentStage(0.75f))
        )
        val stage = SilentHandleStage()

        assertWithMessage("ChainedPreprocessor.process allocates on the audio thread")
            .that(worstPerCall("ChainedPreprocessor over 3 stages") { chain.process(frame) })
            .isLessThan(HALF_AN_OBJECT)
        assertWithMessage("SingleHandleStage.process allocates on the audio thread")
            .that(worstPerCall("SingleHandleStage capture") { stage.process(frame) }).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("SingleHandleStage's far-end path allocates on the audio thread")
            .that(worstPerCall("SingleHandleStage render") { stage.analyzeReverseStream(frame) })
            .isLessThan(HALF_AN_OBJECT)
    }

    /**
     * [SpeexPreprocessor] answers an integer percent, so its probabilities come from a pre-boxed
     * table. The cold window matters: a per-frame `Float` box measures ~16 B cold but only ~2.5 B
     * hot, because C2 scalar-replaces most boxes (ART does not).
     */
    @Test
    fun `the speex stage allocates under half an object per frame`() {
        checkInstrument(FRAME_SIZE)
        val frame = ShortArray(FRAME_SIZE)
        val stage = SpeexPreprocessor(SilentSpeexApi())

        assertWithMessage("SpeexPreprocessor.process allocates on the audio thread")
            .that(worstPerCall("SpeexPreprocessor") { stage.process(frame) }).isLessThan(HALF_AN_OBJECT)
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
            aec3Tuning: FloatArray?,
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
        checkInstrument(FRAME_SIZE)
        val frame = ShortArray(FRAME_SIZE)
        val rnnoise = RnnoisePreprocessor(SilentRnnoiseApi())
        val apm = WebRtcApmPreprocessor(SilentApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val farEndSink = SilentFarEndSink()
        val chunker = FarEndFrameChunker(FRAME_SIZE, farEndSink)

        assertWithMessage("RnnoisePreprocessor.process allocates more than the probability box")
            .that(worstPerCall("RnnoisePreprocessor") { rnnoise.process(frame) }).isLessThan(ONE_BOXED_FLOAT)
        assertWithMessage("WebRtcApmPreprocessor.process allocates more than the probability box")
            .that(worstPerCall("WebRtcApmPreprocessor capture") { apm.process(frame) }).isLessThan(ONE_BOXED_FLOAT)
        assertWithMessage("WebRtcApmPreprocessor's far-end path allocates on the playback thread")
            .that(worstPerCall("WebRtcApmPreprocessor render") { apm.analyzeReverseStream(frame) })
            .isLessThan(HALF_AN_OBJECT)
        assertWithMessage("FarEndFrameChunker.push allocates on the playback thread")
            .that(worstPerCall("FarEndFrameChunker") { chunker.push(frame, FRAME_SIZE) }).isLessThan(HALF_AN_OBJECT)
        assertWithMessage("the chunker must actually have produced frames")
            .that(farEndSink.frames).isGreaterThan(0)
    }

    /**
     * The double-talk relief runs on both threads: the far end's level on the playback thread, the
     * user's evidence and the ramp on the capture thread. Neither may add to the probability box.
     * The frames alternate so the relief engages and releases while it is measured.
     */
    @Test
    fun `the double-talk relief allocates nothing on either thread`() {
        checkInstrument(FRAME_SIZE)
        val loud = ShortArray(FRAME_SIZE) { if (it % 2 == 0) 8000 else -8000 }
        val quiet = ShortArray(FRAME_SIZE)
        val frame = ShortArray(FRAME_SIZE)
        val activity = FarEndActivity()
        val rnnoise = RnnoisePreprocessor(SilentRnnoiseApi(), relief = DoubleTalkRelief(activity))
        val apm =
            WebRtcApmPreprocessor(SilentApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION, farEndActivity = activity)
        var tick = 0

        assertWithMessage("the far end's activity allocates on the playback thread")
            .that(worstPerCall("WebRtcApmPreprocessor render with activity") { apm.analyzeReverseStream(loud) })
            .isLessThan(HALF_AN_OBJECT)
        assertWithMessage("RnnoisePreprocessor with a relief allocates more than the probability box")
            .that(
                worstPerCall("RnnoisePreprocessor with relief") {
                    (if (tick++ % 16 < 8) loud else quiet).copyInto(frame)
                    rnnoise.process(frame)
                },
            ).isLessThan(ONE_BOXED_FLOAT)
    }

    /**
     * The detector only unboxes an already boxed `Float?` and the pipeline over `NoopPreprocessor`
     * boxes nothing, so both get the strict threshold. Both detector modes are measured because
     * they take different branches of `isVoice`.
     */
    @Test
    fun `the detector and the pipeline allocate under half an object per frame`() {
        checkInstrument(FRAME_SIZE)
        val frame = ShortArray(FRAME_SIZE) { (it % 997).toShort() }
        val amplitude = VoiceActivityDetector(VadConfig.amplitude(0.5f))
        val probability = VoiceActivityDetector(VadConfig.probability())
        val boxed: Float? = 0.8f
        val pipeline = CapturePipeline(null, NoopPreprocessor, ContinuousInputMode())

        assertWithMessage("VoiceActivityDetector.isVoice allocates on the audio thread in amplitude mode")
            .that(worstPerCall("VoiceActivityDetector amplitude") { amplitude.isVoice(frame, FRAME_SIZE, null) })
            .isLessThan(HALF_AN_OBJECT)
        assertWithMessage("VoiceActivityDetector.isVoice allocates on the audio thread in probability mode")
            .that(worstPerCall("VoiceActivityDetector probability") { probability.isVoice(frame, FRAME_SIZE, boxed) })
            .isLessThan(HALF_AN_OBJECT)
        assertWithMessage("CapturePipeline.process allocates on the audio thread")
            .that(worstPerCall("CapturePipeline") { pipeline.process(frame, FRAME_SIZE) }).isLessThan(HALF_AN_OBJECT)
    }

    private companion object {
        const val FRAME_SIZE = 480

        /** One `java.lang.Float` (16 B) plus the measurement floor; a second allocation fails. */
        const val ONE_BOXED_FLOAT = 16.0 + HALF_AN_OBJECT
    }
}
