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

package se.lublin.humla.audio.inputmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.capture.VoiceActivityDetector

class ActivityInputModeTest {
    private fun constant(value: Int, size: Int = 480) = ShortArray(size) { value.toShort() }

    @Test
    fun `legacy float constructor is amplitude mode with the slider as start threshold`() {
        val mode = ActivityInputMode(0.7f)
        assertThat(mode.vadConfig).isEqualTo(VadConfig.amplitude(0.7f))
        assertThat(mode.shouldTransmit(constant(3277), 480, null)).isTrue()
    }

    /**
     * `HumlaService:270` writes exactly `new ActivityInputMode(0)`, an `int` literal against a
     * `Float` parameter, and `:547` follows it with `setThreshold(float)`. Both are Java call
     * sites this task must not break, and neither is covered by the Kotlin cases above --
     * `ActivityInputMode(0)` in Kotlin would not even compile.
     */
    @Test
    fun `the call HumlaService makes is a zero-threshold amplitude detector`() {
        val mode = ActivityInputMode(0f)
        assertThat(mode.vadConfig).isEqualTo(VadConfig(VadMode.AMPLITUDE, 0f, 0f, 250L))
        // A zero threshold transmits on anything that is not digital silence, as it did before.
        assertThat(mode.shouldTransmit(constant(1), 480, null)).isTrue()
    }

    @Test
    fun `the probability argument reaches the detector`() {
        val mode = ActivityInputMode(VoiceActivityDetector(VadConfig.probability()) { 0L })
        assertThat(mode.shouldTransmit(constant(0), 480, 0.9f)).isTrue()
    }

    /**
     * The length, not the array's size -- **both** the divisor and the loop bound. `AudioInput`
     * hands the capture buffer down whole with the count of valid samples beside it, so reading
     * `pcm.size` instead of `length` averages the frame against whatever is past the valid samples
     * and reports a level that is wrong -- quietly, and only for a short frame.
     *
     * **The tail must not be zeros, and this test used to have zeros.** With a zero tail the two
     * sums are bit-identical, so the assertion held for the wrong reason: it pinned the *divisor*
     * and left the *loop bound* free, and a mutation of `0 until length` to `pcm.indices` survived
     * the whole suite. The bracket below is two-sided on purpose -- one assertion cannot separate
     * a reading that is too low from one that is too high.
     *
     * Computed for 480 samples of 3277 followed by 480 of 2000:
     * - correct, `length = 480`: **0.79167**
     * - loop bound over the whole array, `length = 480`: **0.80600** (too high; the upper bracket)
     * - divisor `pcm.size`, `length = 480`: **0.76031** (too low; the lower bracket)
     * - correct, `length = 960`: **0.77464**
     */
    @Test
    fun `only the first length samples are measured`() {
        val padded = ShortArray(960)
        for (i in 0 until 480) padded[i] = 3277
        for (i in 480 until 960) padded[i] = 2000
        assertThat(ActivityInputMode(0.78f).shouldTransmit(padded, 480, null)).isTrue()
        assertThat(ActivityInputMode(0.80f).shouldTransmit(padded, 480, null)).isFalse()
        assertThat(ActivityInputMode(0.78f).shouldTransmit(padded, 960, null)).isFalse()
    }

    /**
     * The same defect in the units it will be met in, and it is not hypothetical: `AudioInput.loop`
     * allocates its capture buffer **once, outside the loop**, so every
     * short frame arrives in a buffer whose tail still holds the previous frame. Task 8's
     * `CapturePipeline` opens exactly this dimension -- `a short resampler output is zero-padded`
     * hands `length = 300` into a 480-sample buffer.
     *
     * 300 quiet samples (value 300) in a buffer whose remaining 180 still carry a loud tail
     * (20000) score **0.5753** read correctly and **0.9322** read over the whole buffer. Against
     * the same threshold that is silence against shouting: **the microphone opens on a quiet frame
     * because of audio that is already gone.** `AudioInput.loop` shields this twice over since
     * task 9: it zero-pads the buffer from the read count to the end, and it still hands over the
     * whole frame size. From task 8 on, the pipeline is what has to keep doing both.
     */
    @Test
    fun `a short frame is not measured against the previous frame's tail`() {
        val reused = ShortArray(480) { if (it < 300) 300 else 20000 }
        assertThat(ActivityInputMode(0.6f).shouldTransmit(reused, 300, null)).isFalse()
        // And the loud leftovers really are loud: read whole, the same buffer is far over.
        assertThat(ActivityInputMode(0.6f).shouldTransmit(reused, 480, null)).isTrue()
    }

    @Test
    fun `setThreshold updates amplitude mode but does not clobber probability mode`() {
        val mode = ActivityInputMode(0.5f)
        mode.setThreshold(0.9f)
        assertThat(mode.vadConfig).isEqualTo(VadConfig.amplitude(0.9f))

        mode.setVadConfig(VadConfig.probability())
        mode.setThreshold(0.2f)
        assertThat(mode.vadConfig.mode).isEqualTo(VadMode.PROBABILITY)
        assertThat(mode.vadConfig.startThreshold).isEqualTo(0.6f)
    }

    /**
     * The slider carries no hold time, so `setThreshold` has to keep the one already configured.
     * Dropping it silently resets a user's hold to the 250 ms default every time they move the
     * slider -- and the only visible symptom is that speech starts clipping again.
     */
    @Test
    fun `setThreshold keeps the configured hold time`() {
        val mode = ActivityInputMode(0.5f)
        mode.setVadConfig(VadConfig.amplitude(0.5f, holdTimeMs = 40L))
        mode.setThreshold(0.9f)
        assertThat(mode.vadConfig).isEqualTo(VadConfig(VadMode.AMPLITUDE, 0.9f, 0.75f, 40L))
    }

    @Test
    fun `waitForInput returns immediately`() {
        ActivityInputMode(0.5f).waitForInput()
    }
}
