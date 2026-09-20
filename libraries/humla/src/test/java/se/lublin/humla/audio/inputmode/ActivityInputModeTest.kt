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
     * The length, not the array's size. `AudioInput` hands the capture buffer down whole with the
     * count of valid samples beside it, so reading `pcm.size` instead of `length` averages the
     * frame against a tail of zeros and reports a level that is too low -- quietly, and only for
     * the last frame of a burst.
     */
    @Test
    fun `only the first length samples are measured`() {
        // 480 loud samples then 480 zeros: score 0.7917 over the first half, 0.7603 over both.
        val padded = ShortArray(960)
        for (i in 0 until 480) padded[i] = 3277
        assertThat(ActivityInputMode(0.78f).shouldTransmit(padded, 480, null)).isTrue()
        assertThat(ActivityInputMode(0.78f).shouldTransmit(padded, 960, null)).isFalse()
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
