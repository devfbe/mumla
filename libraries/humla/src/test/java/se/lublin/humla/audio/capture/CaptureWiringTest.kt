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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.fakes.FakeResampler
import se.lublin.humla.audio.capture.fakes.FakeRnnoiseApi
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.util.HumlaLogger

/**
 * The two assurances the wiring commit carries, and nothing else: **the preprocessed frame is what
 * reaches the consumer**, and **a chain that cannot be built does not take capture down with it.**
 *
 * The seam is here rather than in `AudioHandler` because that class cannot be instantiated on the
 * host: its constructor opens an `AudioRecord` and its encoders `System.loadLibrary`. The six lines
 * it gained are covered by the device build, and that is said in the report rather than implied.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureWiringTest {
    private companion object {
        const val FRAME = 480
    }

    private val warnings = mutableListOf<String>()
    private val logger = object : HumlaLogger {
        override fun logInfo(message: String) = Unit
        override fun logWarning(message: String) { warnings += message }
        override fun logError(message: String) = Unit
    }

    private fun factory(rnnoise: () -> se.lublin.humla.audio.native.RnnoiseApi) =
        CapturePreprocessorFactory(rnnoiseApi = rnnoise, log = { warnings += it })

    /** What the user gets by default: one stage, RNNoise, and its probability on every frame. */
    @Test
    fun `the default chain denoises every frame with rnnoise`() {
        val api = FakeRnnoiseApi(probability = 0.9f, onProcess = { it.fill(11) })
        val pipeline = CaptureWiring.capturePipeline(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.RNNOISE, logger, factory { api },
        )

        val frame = pipeline.process(ShortArray(FRAME) { 1000 }, FRAME)

        assertThat(frame.samples.toSet()).containsExactly(11.toShort())
        assertThat(frame.length).isEqualTo(FRAME)
        assertThat(frame.probability).isEqualTo(0.9f)
        assertThat(warnings).isEmpty()
    }

    /**
     * The first hard requirement of the wiring: a missing `.so` is a skipped stage, not a dead
     * microphone. `RnnoiseNative` loads its library in its object initialiser, so the first touch
     * arrives as an `ExceptionInInitializerError` -- a `LinkageError`, not an `Exception`.
     */
    @Test
    fun `a chain that cannot be built leaves capture running and says so`() {
        val pipeline = CaptureWiring.capturePipeline(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.RNNOISE, logger,
            factory { throw ExceptionInInitializerError(UnsatisfiedLinkError("libhumlarnnoise.so")) },
        )

        val frame = pipeline.process(ShortArray(FRAME) { 1234 }, FRAME)

        assertThat(frame.samples.toSet()).containsExactly(1234.toShort())
        assertThat(frame.length).isEqualTo(FRAME)
        assertThat(frame.transmit).isTrue()
        assertThat(warnings.any { it.contains("rnnoise") }).isTrue()
    }

    /** Off is off: no stage, and no warning about a stage nobody asked for. */
    @Test
    fun `no noise suppression builds no stage and warns about nothing`() {
        val pipeline = CaptureWiring.capturePipeline(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE, logger,
            factory { throw ExceptionInInitializerError(UnsatisfiedLinkError("libhumlarnnoise.so")) },
        )

        assertThat(pipeline.process(ShortArray(FRAME) { 7 }, FRAME).probability).isNull()
        assertThat(warnings).isEmpty()
    }

    // ------------------------------------------------------------------ the resampler

    @Test
    fun `capture at 48 kHz needs no resampler`() {
        val built = mutableListOf<Pair<Int, Int>>()

        CaptureWiring.capturePipeline(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE, logger, factory { FakeRnnoiseApi() },
        ) { from, to -> built += from to to; FakeResampler(1) }

        assertThat(built).isEmpty()
    }

    /**
     * Spec B11 and the SCO case. Exactly one resampler may exist: the old `ResamplingEncoder` wrap
     * in `AudioHandler.setCodecLocked` is removed in the same commit, or the frame is converted
     * twice.
     */
    @Test
    fun `capture below 48 kHz gets a resampler up to the codec rate`() {
        val built = mutableListOf<Pair<Int, Int>>()
        val pipeline = CaptureWiring.capturePipeline(
            16000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE, logger, factory { FakeRnnoiseApi() },
        ) { from, to -> built += from to to; FakeResampler(3) }

        val frame = pipeline.process(ShortArray(160) { 5 }, 160)

        assertThat(built).containsExactly(16000 to 48000)
        assertThat(frame.length).isEqualTo(FRAME)
        assertThat(frame.samples.toSet()).containsExactly(5.toShort())
    }
}
