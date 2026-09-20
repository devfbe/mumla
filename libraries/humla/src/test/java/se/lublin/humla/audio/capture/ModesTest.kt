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

class ModesTest {
    @Test
    fun `legacy echo value system maps to android effect`() {
        assertThat(EchoCancellationMode.fromPreferenceValue("system")).isEqualTo(EchoCancellationMode.ANDROID)
    }

    @Test
    fun `webrtc echo value is recognised`() {
        assertThat(EchoCancellationMode.fromPreferenceValue("webrtc")).isEqualTo(EchoCancellationMode.WEBRTC)
    }

    /** Spec §4 spells the EXTRAS_ECHO_CANCELLATION values "none"/"android"/"webrtc". */
    @Test
    fun `the spec spelling android is an alias for the legacy value system`() {
        assertThat(EchoCancellationMode.fromPreferenceValue("android")).isEqualTo(EchoCancellationMode.ANDROID)
        assertThat(EchoCancellationMode.ANDROID.preferenceValue).isEqualTo("system")
    }

    @Test
    fun `unknown or missing echo value falls back to none`() {
        assertThat(EchoCancellationMode.fromPreferenceValue("bogus")).isEqualTo(EchoCancellationMode.NONE)
        assertThat(EchoCancellationMode.fromPreferenceValue(null)).isEqualTo(EchoCancellationMode.NONE)
    }

    @Test
    fun `noise suppression values map and default to speex`() {
        assertThat(NoiseSuppressionMode.fromPreferenceValue("rnnoise")).isEqualTo(NoiseSuppressionMode.RNNOISE)
        assertThat(NoiseSuppressionMode.fromPreferenceValue("none")).isEqualTo(NoiseSuppressionMode.NONE)
        assertThat(NoiseSuppressionMode.fromPreferenceValue(null)).isEqualTo(NoiseSuppressionMode.SPEEX)
    }

    /**
     * Both enums round-trip through their own on-disk value. This is the "pin the set, not the
     * member" half: a constant added later without a mapping fails here instead of silently
     * reading back as the default the first time a user selects it.
     */
    @Test
    fun `every mode round-trips through its preference value`() {
        for (mode in NoiseSuppressionMode.entries) {
            assertThat(NoiseSuppressionMode.fromPreferenceValue(mode.preferenceValue)).isEqualTo(mode)
        }
        for (mode in EchoCancellationMode.entries) {
            assertThat(EchoCancellationMode.fromPreferenceValue(mode.preferenceValue)).isEqualTo(mode)
        }
    }

    /** Two constants sharing an on-disk value would make one of them unreachable from settings. */
    @Test
    fun `preference values are distinct within each mode`() {
        assertThat(NoiseSuppressionMode.entries.map { it.preferenceValue })
            .containsNoDuplicates()
        assertThat(EchoCancellationMode.entries.map { it.preferenceValue })
            .containsNoDuplicates()
    }

    @Test
    fun `android effects any is true when either effect is on`() {
        assertThat(AndroidAudioEffects().any).isFalse()
        assertThat(AndroidAudioEffects(noiseSuppressor = true).any).isTrue()
        assertThat(AndroidAudioEffects(automaticGainControl = true).any).isTrue()
    }
}
