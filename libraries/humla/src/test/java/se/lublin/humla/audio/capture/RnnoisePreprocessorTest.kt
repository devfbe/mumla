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
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.audio.capture.fakes.FakeRnnoiseApi
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** RNNoise as a capture stage: 480-sample frames at 48 kHz, reporting the model's probability. */
class RnnoisePreprocessorTest {
    private companion object {
        const val FRAME = 480
        const val LATENCY = RnnoisePreprocessor.LATENCY_SAMPLES
    }

    private val api = FakeRnnoiseApi()

    @Test
    fun `construction creates exactly one denoiser`() {
        RnnoisePreprocessor(api)

        assertThat(api.created).isEqualTo(1)
    }

    @Test
    fun `construction fails loudly when rnnoise cannot allocate`() {
        api.failCreate = true

        val failure = assertThrows(IllegalStateException::class.java) { RnnoisePreprocessor(api) }

        assertThat(failure).hasMessageThat().contains("rnnoise")
    }

    @Test
    fun `process denoises in place and reports the model probability`() {
        val api = FakeRnnoiseApi(probability = 0.87f, onProcess = { it.fill(7) })
        val frame = ShortArray(FRAME) { 1000 }

        val stage = RnnoisePreprocessor(api, attenuationLimitDb = Float.POSITIVE_INFINITY)

        val probability = stage.process(frame)

        assertThat(probability).isEqualTo(0.87f)
        assertThat(frame[FRAME - 1]).isEqualTo(7.toShort())
        assertThat(api.processedLengths).containsExactly(FRAME)
        assertThat(stage.rejectedFrames).isEqualTo(0)
    }

    /**
     * Like rnnoise: the output is the input from [LATENCY] samples ago, times [gain]. The limit's
     * dry path must line up with that exactly.
     */
    private class DelayingDenoiser(private val gain: Float) {
        private val line = ShortArray(LATENCY)
        private var position = 0

        fun process(frame: ShortArray) {
            for (i in 0 until FRAME) {
                val delayed = line[position]
                line[position] = frame[i]
                position = (position + 1) % LATENCY
                frame[i] = (delayed * gain).roundToInt().toShort()
            }
        }
    }

    /** A stationary input: two tones, no sample ever repeats in a frame-periodic way. */
    private fun input(n: Int): Short = (8000 * sin(n * 0.0731) + 4000 * sin(n * 0.2913)).roundToInt().toShort()

    /** Runs [frames] frames of [input] through [stage]; returns every output sample in order. */
    private fun run(stage: RnnoisePreprocessor, frames: Int): ShortArray {
        val out = ShortArray(frames * FRAME)
        val frame = ShortArray(FRAME)
        for (f in 0 until frames) {
            for (i in 0 until FRAME) frame[i] = input(f * FRAME + i)
            stage.process(frame)
            System.arraycopy(frame, 0, out, f * FRAME, FRAME)
        }
        return out
    }

    /**
     * Aligned, the mix of `g * x(n - L)` and the dry `x(n - L)` is `((1 - a) * g + a) * x(n - L)`:
     * one gain, so no comb. A dry path off by even one sample would break the equality.
     */
    @Test
    fun `the dry path lines up with rnnoise's output, so the mix is a pure gain`() {
        val limitDb = 12f
        val gain = 0.1f
        val a = 10f.pow(-limitDb / 20f)
        val denoiser = DelayingDenoiser(gain)
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(probability = 0.5f, onProcess = denoiser::process), limitDb)

        val out = run(stage, frames = 10)

        val expected = (1 - a) * gain + a
        for (n in LATENCY until out.size) {
            val want = input(n - LATENCY) * expected
            assertWithMessage("sample %s", n).that(abs(out[n] - want)).isAtMost(1f)
        }
    }

    /** rnnoise silencing a frame completely still leaves the input, down by exactly the limit. */
    @Test
    fun `a frame rnnoise zeroes comes out attenuated by exactly the limit`() {
        val limitDb = 18f
        val a = 10f.pow(-limitDb / 20f)
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(onProcess = { it.fill(0) }), limitDb)

        val out = run(stage, frames = 6)

        for (n in LATENCY until out.size) {
            assertWithMessage("sample %s", n).that(abs(out[n] - input(n - LATENCY) * a)).isAtMost(0.5f)
        }
    }

    /** No limit: rnnoise's output as it is, sample for sample, the chain before the limit existed. */
    @Test
    fun `without a limit the output is rnnoise's own`() {
        val api = FakeRnnoiseApi(onProcess = DelayingDenoiser(0.3f)::process)
        val limited = run(RnnoisePreprocessor(api, Float.POSITIVE_INFINITY), 6)
        val bare = ShortArray(6 * FRAME) { input(it) }
        val denoiser = DelayingDenoiser(0.3f)
        for (f in 0 until 6) {
            val frame = bare.copyOfRange(f * FRAME, (f + 1) * FRAME)
            denoiser.process(frame)
            System.arraycopy(frame, 0, bare, f * FRAME, FRAME)
        }

        assertThat(limited).isEqualTo(bare)
    }

    /**
     * 18 dB ships (see [RnnoisePreprocessor.ATTENUATION_LIMIT_DB]); the user may move it. Changing
     * the default means re-running `RoomAcousticsDeviceTest`, `RealSpeechDeviceTest` and
     * `DoubleTalkAec3DeviceTest.shippedChainInDoubleTalk` and moving their bounds.
     */
    @Test
    fun `the shipped chain limits rnnoise to 18 dB and the dry path matches its two-frame latency`() {
        assertThat(RnnoisePreprocessor.ATTENUATION_LIMIT_DB).isEqualTo(18f)
        assertThat(RnnoisePreprocessor(api).attenuationLimitDb).isEqualTo(18f)
        assertThat(RnnoisePreprocessor.LATENCY_SAMPLES).isEqualTo(2 * FRAME)
    }

    /**
     * The self-test's slider moves the limit while frames flow. The delay line runs whatever the
     * limit, so the first frame after a switch to a finite limit already mixes an aligned dry path.
     */
    @Test
    fun `a limit set while running applies from the next frame, aligned from its first sample`() {
        val limitDb = 12f
        val gain = 0.1f
        val a = 10f.pow(-limitDb / 20f)
        val stage = RnnoisePreprocessor(
            FakeRnnoiseApi(probability = 0.5f, onProcess = DelayingDenoiser(gain)::process), Float.POSITIVE_INFINITY,
        )
        val frame = ShortArray(FRAME)
        for (f in 0 until 8) {
            if (f == 4) stage.attenuationLimitDb = limitDb
            for (i in 0 until FRAME) frame[i] = input(f * FRAME + i)
            stage.process(frame)
            val mix = if (f < 4) gain else (1 - a) * gain + a
            for (i in 0 until FRAME) {
                val n = f * FRAME + i
                if (n < LATENCY) continue
                assertWithMessage("frame %s sample %s", f, i).that(abs(frame[i] - input(n - LATENCY) * mix))
                    .isAtMost(1f)
            }
        }
    }

    /** Back to no limit mid-stream: rnnoise's own output again, sample for sample. */
    @Test
    fun `switching back to no limit is rnnoise's own output again`() {
        val limited = RnnoisePreprocessor(FakeRnnoiseApi(onProcess = DelayingDenoiser(0.3f)::process), 12f)
        run(limited, 3)
        limited.attenuationLimitDb = Float.POSITIVE_INFINITY
        val bare = DelayingDenoiser(0.3f)
        repeat(3) { f -> bare.process(ShortArray(FRAME) { input(f * FRAME + it) }) }

        val frame = ShortArray(FRAME) { input(3 * FRAME + it) }
        limited.process(frame)
        val expected = ShortArray(FRAME) { input(3 * FRAME + it) }
        bare.process(expected)

        assertThat(frame).isEqualTo(expected)
    }

    @Test
    fun `a negative limit set while running is refused and the old one kept`() {
        val stage = RnnoisePreprocessor(api, 18f)

        assertThrows(IllegalArgumentException::class.java) { stage.attenuationLimitDb = -3f }
        assertThat(stage.attenuationLimitDb).isEqualTo(18f)
    }

    /** A refused frame never reached rnnoise, so it must not enter the dry path either. */
    @Test
    fun `a refused frame leaves the delay line alone`() {
        val limitDb = 12f
        val a = 10f.pow(-limitDb / 20f)
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(probability = 0.5f, onProcess = { it.fill(0) }), limitDb)
        val frame = ShortArray(FRAME)
        val out = ShortArray(4 * FRAME)
        for (f in 0 until 4) {
            assertThat(stage.process(ShortArray(FRAME - 1) { 1234 })).isNull()
            for (i in 0 until FRAME) frame[i] = input(f * FRAME + i)
            stage.process(frame)
            System.arraycopy(frame, 0, out, f * FRAME, FRAME)
        }

        for (n in LATENCY until out.size) {
            assertWithMessage("sample %s", n).that(abs(out[n] - input(n - LATENCY) * a)).isAtMost(0.5f)
        }
        assertThat(stage.rejectedFrames).isEqualTo(4)
    }

    /** Checked before the native state exists: a bad argument must not leak a denoiser. */
    @Test
    fun `a negative limit is refused before rnnoise is created`() {
        assertThrows(IllegalArgumentException::class.java) { RnnoisePreprocessor(api, attenuationLimitDb = -1f) }
        assertThrows(IllegalArgumentException::class.java) { RnnoisePreprocessor(api, attenuationLimitDb = Float.NaN) }

        assertThat(api.createAttempts).isEqualTo(0)
    }

    /**
     * Only the upper end is clamped: -1 is the bridge's refusal sentinel and must not become 0
     * ("certainly not speech"), which would mute the user.
     */
    @Test
    fun `a probability above one is clamped rather than propagated`() {
        assertThat(RnnoisePreprocessor(FakeRnnoiseApi(probability = 1.4f)).process(ShortArray(FRAME)))
            .isEqualTo(1f)
    }

    /**
     * The bridge refuses a frame shorter than 480 samples with -1. Throwing instead would kill the
     * capture thread once per frame, so the frame is refused and counted.
     */
    @Test
    fun `a frame the bridge refuses is reported rather than thrown`() {
        val api = FakeRnnoiseApi(probability = 0.9f)
        val stage = RnnoisePreprocessor(api)

        val probability = stage.process(ShortArray(441))

        assertThat(probability).isNull()
        assertThat(stage.rejectedFrames).isEqualTo(1)
        assertThat(api.processedLengths).isEmpty()
    }

    /** `p < 0` misses NaN, which would compare false against both thresholds and read as silence. */
    @Test
    fun `a not-a-number probability is refused rather than passed on`() {
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(probability = Float.NaN))

        assertThat(stage.process(ShortArray(FRAME))).isNull()
        assertThat(stage.rejectedFrames).isEqualTo(1)
    }

    @Test
    fun `a longer frame is accepted, because the bridge only reads the first 480 samples`() {
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(probability = 0.5f))

        assertThat(stage.process(ShortArray(960))).isEqualTo(0.5f)
        assertThat(stage.rejectedFrames).isEqualTo(0)
    }

    /** A capture thread that outlived its join timeout must not reach rnnoise on a freed state. */
    @Test
    fun `release destroys the denoiser exactly once and process after it touches nothing`() {
        val api = FakeRnnoiseApi(probability = 0.9f)
        val stage = RnnoisePreprocessor(api)
        stage.release()
        stage.release()

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(api.processedLengths).isEmpty()
        assertThat(api.destroyed).isEqualTo(1)
    }

    /**
     * A far-end frame swallowed here would silently cost about 21 dB of echo cancellation. Declaring
     * [FarEndSink] would make the factory hand out a sink that throws on the playback thread.
     */
    @Test
    fun `the stage is not a far-end sink and refuses the reverse stream`() {
        val stage = RnnoisePreprocessor(api)

        assertThat(stage).isNotInstanceOf(FarEndSink::class.java)
        assertThrows(UnsupportedOperationException::class.java) {
            stage.analyzeReverseStream(ShortArray(FRAME))
        }
    }
}
