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
import se.lublin.humla.audio.capture.fakes.FailingResampler
import se.lublin.humla.audio.capture.fakes.FakePreprocessor
import se.lublin.humla.audio.capture.fakes.FakeResampler
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode

class CapturePipelineTest {
    private fun constant(value: Int, size: Int = 480) = ShortArray(size) { value.toShort() }

    /** Regression for voice activation being triggered by background noise. */
    @Test
    fun `vad decides on the preprocessed frame, not the raw frame`() {
        val loudNoise = constant(8000)   // level score 0.87243, far above a 0.5 amplitude threshold
        val gate = FakePreprocessor(transform = { it.fill(0) })   // a denoiser that removes everything

        val withDenoiser = CapturePipeline(null, gate, ActivityInputMode(0.5f))
        val withoutDenoiser = CapturePipeline(null, NoopPreprocessor, ActivityInputMode(0.5f))

        assertThat(withDenoiser.process(loudNoise, 480).transmit).isFalse()
        assertThat(withoutDenoiser.process(loudNoise, 480).transmit).isTrue()
    }

    @Test
    fun `probability mode transmits on the preprocessor probability`() {
        val silent = constant(0)
        val mode = ActivityInputMode(VoiceActivityDetector(VadConfig.probability()) { 0L })
        assertThat(CapturePipeline(null, FakePreprocessor(0.9f), mode).process(silent, 480).transmit).isTrue()
        val mode2 = ActivityInputMode(VoiceActivityDetector(VadConfig.probability()) { 0L })
        assertThat(CapturePipeline(null, FakePreprocessor(0.1f), mode2).process(silent, 480).transmit).isFalse()
    }

    @Test
    fun `preprocessing runs on every frame even while not transmitting`() {
        val fake = FakePreprocessor()
        val ptt = ToggleInputMode()   // off
        val pipeline = CapturePipeline(null, fake, ptt)
        repeat(3) { pipeline.process(constant(100), 480) }
        assertThat(fake.frames).hasSize(3)
    }

    @Test
    fun `the resampler runs before the preprocessor`() {
        val fake = FakePreprocessor()
        val logs = mutableListOf<String>()
        val pipeline = CapturePipeline(FakeResampler(3), fake, ContinuousInputMode(), log = { logs += it })
        val input = ShortArray(160) { (it + 1).toShort() }
        val out = pipeline.process(input, 160)
        assertThat(out.length).isEqualTo(480)
        assertThat(fake.frames.single()[0]).isEqualTo(1.toShort())
        assertThat(fake.frames.single()[3]).isEqualTo(2.toShort())
        assertThat(fake.frames.single()[479]).isEqualTo(160.toShort())
        assertWithMessage("a frame the resampler filled completely is not a short frame")
            .that(logs).isEmpty()
    }

    @Test
    fun `a short resampler output is zero-padded to a full frame, logged once, and not judged by the vad`() {
        val logs = mutableListOf<String>()
        val mode = RecordingInputMode()
        val pipeline = CapturePipeline(FakeResampler(3), FakePreprocessor(), mode, log = { logs += it })

        val out = pipeline.process(constant(7, 100), 100)   // 300 samples come back
        pipeline.process(constant(7, 100), 100)

        assertThat(out.length).isEqualTo(480)              // the encoder always gets a full frame
        assertThat(out.samples[299]).isEqualTo(7.toShort())
        assertThat(out.samples[300]).isEqualTo(0.toShort())
        assertThat(mode.lengths).containsExactly(300, 300)  // the VAD never sees the zero padding
        assertThat(logs).hasSize(1)                         // rate-limited: once per pipeline
        assertThat(logs.single()).contains("300")
    }

    /**
     * The pipeline reuses its frame buffer, so without the padding write the samples behind a short
     * frame would still hold the previous frame's tail and go out on the wire again.
     */
    @Test
    fun `the samples behind a short frame are cleared rather than left from the previous frame`() {
        val pipeline = CapturePipeline(PassThroughResampler(), NoopPreprocessor, ContinuousInputMode())

        val loud = pipeline.process(constant(20000), 480)
        assertWithMessage("the fixture has to put a loud tail in the buffer first")
            .that(loud.samples[479]).isEqualTo(20000.toShort())

        val short = pipeline.process(constant(300, 300), 300)

        assertThat(short.samples[299]).isEqualTo(300.toShort())
        assertThat(short.samples[300]).isEqualTo(0.toShort())
        assertThat(short.samples[479]).isEqualTo(0.toShort())
    }

    @Test
    fun `the voice detector judges a short frame on its own length, not the whole buffer`() {
        val mode = RecordingInputMode()
        val pipeline = CapturePipeline(PassThroughResampler(), NoopPreprocessor, mode)

        pipeline.process(constant(20000), 480)
        pipeline.process(constant(300, 300), 300)

        assertThat(mode.lengths).containsExactly(480, 300).inOrder()
        assertWithMessage(
            "0.55409 would be the padding dragging the level down, 0.91097 the previous frame's tail holding it up",
        )
            .that(mode.scores[1]).isWithin(1e-4f).of(0.57535f)
    }

    /** `SpeexResampler` returns 0 for every speex error, so this is the production failure case. */
    @Test
    fun `a resampler that produces no samples yields a silent frame the vad refuses`() {
        val mode = RecordingInputMode()
        val failing = FailingResampler()
        val pipeline = CapturePipeline(PassThroughResampler(), NoopPreprocessor, mode)

        pipeline.process(constant(20000), 480)
        pipeline.setResampler(failing)
        val out = pipeline.process(constant(20000), 480)

        assertThat(failing.calls).isEqualTo(1)
        assertThat(out.samples[0]).isEqualTo(0.toShort())
        assertThat(out.samples[479]).isEqualTo(0.toShort())
        assertThat(mode.lengths).containsExactly(480, 0).inOrder()
        assertWithMessage("an empty frame is below every threshold VadConfig can hold, and finite")
            .that(mode.scores[1]).isEqualTo(VoiceActivityDetector.NO_SIGNAL)
    }

    @Test
    fun `swapping the resampler releases the old one`() {
        val first = FakeResampler(3)
        val second = FakeResampler(1)
        val pipeline = CapturePipeline(first, FakePreprocessor(), ContinuousInputMode())

        pipeline.setResampler(second)

        assertThat(first.releases).isEqualTo(1)
        assertThat(second.releases).isEqualTo(0)
        assertThat(pipeline.process(constant(5), 480).samples[0]).isEqualTo(5.toShort())
    }

    /** The rate limit is once per reason, and a swapped resampler is a new reason. */
    @Test
    fun `a swapped resampler gets its own short-frame log`() {
        val logs = mutableListOf<String>()
        val pipeline = CapturePipeline(
            FakeResampler(1), FakePreprocessor(), ContinuousInputMode(), log = { logs += it },
        )

        pipeline.process(constant(7, 100), 100)
        pipeline.process(constant(7, 100), 100)
        assertThat(logs).hasSize(1)

        pipeline.setResampler(FakeResampler(2))
        pipeline.process(constant(7, 100), 100)
        pipeline.process(constant(7, 100), 100)

        assertThat(logs).hasSize(2)
        assertThat(logs[0]).contains("100 of 480")
        assertThat(logs[1]).contains("200 of 480")
    }

    @Test
    fun `setting the same resampler again does not release it`() {
        val only = FakeResampler(1)
        val pipeline = CapturePipeline(only, FakePreprocessor(), ContinuousInputMode())

        pipeline.setResampler(only)

        assertThat(only.releases).isEqualTo(0)
        assertThat(pipeline.process(constant(5), 480).samples[0]).isEqualTo(5.toShort())
    }

    @Test
    fun `boost is applied after preprocessing and only when transmitting`() {
        val seen = FakePreprocessor()
        val pipeline = CapturePipeline(null, seen, ContinuousInputMode(), amplitudeBoost = 2f)
        val out = pipeline.process(constant(1000), 480)
        assertThat(seen.frames.single()[0]).isEqualTo(1000.toShort())   // preprocessor saw the unboosted frame
        assertThat(out.samples[0]).isEqualTo(2000.toShort())

        val muted = CapturePipeline(null, FakePreprocessor(), ToggleInputMode(), amplitudeBoost = 2f)
        assertThat(muted.process(constant(1000), 480).samples[0]).isEqualTo(1000.toShort())
    }

    /**
     * The amplification slider must not act as a voice-activation gain: a boost of 8 applied before
     * the detector would lift 0.62157 over a 0.7 threshold.
     */
    @Test
    fun `the boost does not reach the voice detector`() {
        val mode = RecordingInputMode()
        val pipeline = CapturePipeline(null, NoopPreprocessor, mode, amplitudeBoost = 8f)

        val out = pipeline.process(constant(500), 480)

        assertThat(mode.scores.single()).isWithin(1e-4f).of(0.62157f)
        assertThat(out.samples[0]).isEqualTo(4000.toShort())
    }

    /** The premise of the `amplitudeBoost != 1f` fast path: a factor of one is the identity. */
    @Test
    fun `a boost of one changes no sample`() {
        val pipeline = CapturePipeline(null, NoopPreprocessor, ContinuousInputMode(), amplitudeBoost = 1f)
        val input = shortArrayOf(Short.MIN_VALUE, -32767, -1, 0, 1, 32766, Short.MAX_VALUE)
        val frame = ShortArray(480) { input[it % input.size] }

        val out = pipeline.process(frame, 480)

        for (i in 0 until 480) {
            assertWithMessage("diverges at sample %s", i).that(out.samples[i]).isEqualTo(frame[i])
        }
    }

    @Test
    fun `boost clamps to 16-bit range`() {
        val pipeline = CapturePipeline(null, NoopPreprocessor, ContinuousInputMode(), amplitudeBoost = 2f)
        val out = pipeline.process(ShortArray(480) { if (it % 2 == 0) 20000 else -20000 }, 480)
        assertThat(out.samples[0]).isEqualTo(Short.MAX_VALUE)
        assertThat(out.samples[1]).isEqualTo(Short.MIN_VALUE)
    }

    /**
     * The slider runs 0 to 200 %, so factors below one are real settings. The narrowing truncates
     * toward zero, so a sample of 1 attenuates to 0.
     */
    @Test
    fun `a boost below one attenuates and truncates toward zero`() {
        val pipeline = CapturePipeline(null, NoopPreprocessor, ContinuousInputMode(), amplitudeBoost = 0.5f)
        val input = ShortArray(480)
        shortArrayOf(1000, -1000, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE).copyInto(input)

        val out = pipeline.process(input, 480)

        assertThat(out.samples[0]).isEqualTo(500.toShort())
        assertThat(out.samples[1]).isEqualTo((-500).toShort())
        assertThat(out.samples[2]).isEqualTo(0.toShort())
        assertThat(out.samples[3]).isEqualTo(0.toShort())
        assertThat(out.samples[4]).isEqualTo(16383.toShort())    // 16383.5 truncated, not rounded
        assertThat(out.samples[5]).isEqualTo((-16384).toShort())
    }

    /** The bottom of the slider mutes rather than attenuates. */
    @Test
    fun `the bottom of the slider silences the frame`() {
        val pipeline = CapturePipeline(null, NoopPreprocessor, ContinuousInputMode(), amplitudeBoost = 0f)

        val out = pipeline.process(constant(20000), 480)

        assertThat(out.transmit).isTrue()
        for (i in 0 until 480) {
            assertWithMessage("diverges at sample %s", i).that(out.samples[i]).isEqualTo(0.toShort())
        }
    }

    /** Every other test uses the 480 default, which would hide a hardcoded 480. */
    @Test
    fun `the frame is the size it was given, not 480`() {
        val mode = RecordingInputMode()
        val logs = mutableListOf<String>()
        val pipeline = CapturePipeline(null, NoopPreprocessor, mode, frameSize = 160, log = { logs += it })

        val out = pipeline.process(ShortArray(480) { (it + 1).toShort() }, 480)

        assertThat(out.samples).hasLength(160)
        assertThat(out.length).isEqualTo(160)
        assertThat(out.samples[159]).isEqualTo(160.toShort())
        assertThat(mode.lengths).containsExactly(160)
        assertThat(logs).isEmpty()
    }

    @Test
    fun `an input of no samples is judged as no signal and logged`() {
        val mode = RecordingInputMode()
        val logs = mutableListOf<String>()
        val pipeline = CapturePipeline(null, NoopPreprocessor, mode, log = { logs += it })

        pipeline.process(constant(20000), 480)
        val out = pipeline.process(ShortArray(0), 0)

        assertThat(mode.lengths).containsExactly(480, 0).inOrder()
        assertThat(mode.scores[1]).isEqualTo(VoiceActivityDetector.NO_SIGNAL)
        assertThat(out.samples[0]).isEqualTo(0.toShort())
        assertThat(out.samples[479]).isEqualTo(0.toShort())
        assertThat(logs.single()).contains("0 of 480")
    }

    @Test
    fun `the returned frame reports the probability`() {
        val out = CapturePipeline(null, FakePreprocessor(0.42f), ContinuousInputMode()).process(constant(0), 480)
        assertThat(out.probability).isEqualTo(0.42f)
    }

    /**
     * The returned frame is reused to avoid a per-frame allocation on the audio thread: read it
     * before the next [CapturePipeline.process], never keep it.
     */
    @Test
    fun `the returned frame is the pipeline's own and is reused`() {
        val pipeline = CapturePipeline(null, FakePreprocessor(0.7f), ContinuousInputMode())

        val first = pipeline.process(constant(1000), 480)
        assertThat(first.transmit).isTrue()
        assertThat(first.probability).isEqualTo(0.7f)

        val second = CapturePipeline(null, FakePreprocessor(0.1f), ToggleInputMode()).let { other ->
            assertThat(other.process(constant(1000), 480)).isNotSameInstanceAs(first)
            pipeline.process(constant(2000), 480)
        }

        assertThat(second).isSameInstanceAs(first)
        assertThat(second.samples).isSameInstanceAs(first.samples)
        assertThat(second.samples[0]).isEqualTo(2000.toShort())
    }

    @Test
    fun `release releases resampler and preprocessor`() {
        val r = FakeResampler(1); val p = FakePreprocessor()
        CapturePipeline(r, p, ContinuousInputMode()).release()
        assertThat(r.releases).isEqualTo(1)
        assertThat(p.released).isTrue()
    }

    /**
     * After release the resampler field is cleared: a second release does not reach it, and a frame
     * still in flight falls through to the copy path.
     */
    @Test
    fun `release clears the resampler so it is neither released nor used twice`() {
        val r = FakeResampler(3)
        val pipeline = CapturePipeline(r, FakePreprocessor(), ContinuousInputMode())

        pipeline.release()
        pipeline.release()
        val out = pipeline.process(constant(5, 160), 160)

        assertThat(r.releases).isEqualTo(1)
        assertThat(out.samples[0]).isEqualTo(5.toShort())
        assertWithMessage("the released 3x resampler would have filled 480 samples; the copy path fills 160")
            .that(out.samples[160]).isEqualTo(0.toShort())
    }

    @Test
    fun `without a resampler a long input is truncated to one frame`() {
        val mode = RecordingInputMode()
        val pipeline = CapturePipeline(null, NoopPreprocessor, mode)

        val out = pipeline.process(ShortArray(960) { (it + 1).toShort() }, 960)

        assertThat(out.samples[0]).isEqualTo(1.toShort())
        assertThat(out.samples[479]).isEqualTo(480.toShort())
        assertThat(mode.lengths).containsExactly(480)
    }

    @Test
    fun `without a resampler a short input is padded and judged on its own length`() {
        val mode = RecordingInputMode()
        val pipeline = CapturePipeline(null, NoopPreprocessor, mode)

        val out = pipeline.process(constant(300, 300), 300)

        assertThat(out.samples[299]).isEqualTo(300.toShort())
        assertThat(out.samples[300]).isEqualTo(0.toShort())
        assertThat(mode.lengths).containsExactly(300)
    }

    /** Copies what it is given, leaving the rest of the output buffer untouched. */
    private class PassThroughResampler : Resampler {
        override fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int {
            val n = minOf(inputLength, output.size)
            System.arraycopy(input, 0, output, 0, n)
            return n
        }

        override fun close() = Unit
    }

    /** Always transmits and records the length, and the level, it was asked to judge. */
    private class RecordingInputMode : IInputMode {
        val lengths = mutableListOf<Int>()
        val scores = mutableListOf<Float>()

        override fun shouldTransmit(pcm: ShortArray, length: Int, vadProbability: Float?): Boolean {
            lengths += length
            scores += VoiceActivityDetector.amplitudeScore(pcm, length)
            return true
        }
    }
}
