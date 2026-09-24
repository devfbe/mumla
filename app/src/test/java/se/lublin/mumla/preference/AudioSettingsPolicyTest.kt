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

package se.lublin.mumla.preference

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadMode

class AudioSettingsPolicyTest {
    /**
     * Hidden rather than disabled. A greyed-out slider still reads as "this exists and applies to
     * me"; the three modes measure three incomparable quantities, and showing a probability
     * threshold next to a level threshold is how a user calibrates the wrong one.
     */
    @Test
    fun `each mode shows only the controls that mean something in it`() {
        assertThat(AudioSettingsPolicy.vadDependents(VadMode.ADAPTIVE)).isEqualTo(
            VadDependents(adaptive = true, amplitude = false, probability = false)
        )
        assertThat(AudioSettingsPolicy.vadDependents(VadMode.AMPLITUDE)).isEqualTo(
            VadDependents(adaptive = false, amplitude = true, probability = false)
        )
        assertThat(AudioSettingsPolicy.vadDependents(VadMode.PROBABILITY)).isEqualTo(
            VadDependents(adaptive = false, amplitude = false, probability = true)
        )
    }

    /** Pin the set: a mode added later has to be given an answer here rather than defaulting. */
    @Test
    fun `every mode shows exactly one group of controls`() {
        for (mode in VadMode.entries) {
            val d = AudioSettingsPolicy.vadDependents(mode)
            assertThat(listOf(d.adaptive, d.amplitude, d.probability).count { it }).isEqualTo(1)
        }
    }

    @Test
    fun `the hand-set background level is only offered while the tracking is off`() {
        assertThat(AudioSettingsPolicy.manualFloorVisible(VadMode.ADAPTIVE, adaptiveFloor = false)).isTrue()
        assertThat(AudioSettingsPolicy.manualFloorVisible(VadMode.ADAPTIVE, adaptiveFloor = true)).isFalse()
        assertThat(AudioSettingsPolicy.manualFloorVisible(VadMode.AMPLITUDE, adaptiveFloor = false)).isFalse()
        assertThat(AudioSettingsPolicy.manualFloorVisible(VadMode.PROBABILITY, adaptiveFloor = false)).isFalse()
    }

    /** The Speex depth does nothing unless Speex is the denoiser, so it is not shown otherwise. */
    @Test
    fun `the speex suppression depth is only offered while speex is the denoiser`() {
        assertThat(AudioSettingsPolicy.speexDepthVisible(NoiseSuppressionMode.SPEEX)).isTrue()
        assertThat(AudioSettingsPolicy.speexDepthVisible(NoiseSuppressionMode.RNNOISE)).isFalse()
        assertThat(AudioSettingsPolicy.speexDepthVisible(NoiseSuppressionMode.NONE)).isFalse()
    }
}
