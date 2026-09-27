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

    @Test
    fun `a zero threshold is an amplitude detector that transmits all but digital silence`() {
        val mode = ActivityInputMode(0f)
        assertThat(mode.vadConfig).isEqualTo(VadConfig(VadMode.AMPLITUDE, 0f, 0f, 250L))
        // A zero threshold transmits on anything that is not digital silence.
        assertThat(mode.shouldTransmit(constant(1), 480, null)).isTrue()
    }

    @Test
    fun `the probability argument reaches the detector`() {
        val mode = ActivityInputMode(VoiceActivityDetector(VadConfig.probability()) { 0L })
        assertThat(mode.shouldTransmit(constant(0), 480, 0.9f)).isTrue()
    }

    /**
     * Both the divisor and the loop bound must use `length`, not the array's size. The tail is
     * non-zero so that each mistake is detectable: 0.79167 correct, 0.80600 with the whole array as
     * loop bound, 0.76031 with `pcm.size` as divisor, 0.77464 for `length = 960`.
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
     * The capture buffer is reused, so a short frame's tail holds the previous frame: 0.5753 read
     * correctly, 0.9322 read over the whole buffer.
     */
    @Test
    fun `a short frame is not measured against the previous frame's tail`() {
        val reused = ShortArray(480) { if (it < 300) 300 else 20000 }
        assertThat(ActivityInputMode(0.6f).shouldTransmit(reused, 300, null)).isFalse()
        // Read whole, the same buffer is far over the threshold.
        assertThat(ActivityInputMode(0.6f).shouldTransmit(reused, 480, null)).isTrue()
    }
}

/** Amplitude mode with a single start threshold. */
internal fun ActivityInputMode(detectionThreshold: Float): ActivityInputMode =
    ActivityInputMode(VoiceActivityDetector(VadConfig.amplitude(detectionThreshold)))
