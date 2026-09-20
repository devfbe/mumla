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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** Spec B11: capture at the SCO link rate and resample to 48 kHz. */
@RunWith(RobolectricTestRunner::class)
class ScoSampleRatePolicyTest {
    private val audioManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    // ------------------------------------------------------------------ choose

    /** `AudioDeviceInfo.getSampleRates()` documents an empty array as "any rate is supported". */
    @Test
    fun `no advertised rates means wideband sco`() {
        assertThat(ScoSampleRatePolicy.choose(intArrayOf())).isEqualTo(16000)
    }

    @Test
    fun `picks the highest rate at or below the wideband link rate`() {
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(8000, 16000, 44100, 48000))).isEqualTo(16000)
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(8000))).isEqualTo(8000)
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(8000, 16000))).isEqualTo(16000)
    }

    /**
     * `AudioDeviceInfo.getSampleRates()` is not documented as sorted, and on a device that returns
     * it in enumeration order a `last()` or a `first()` reads a different element than a `max()`.
     * Same array as the case above, shuffled: the answer may not change.
     */
    @Test
    fun `the rate list is not assumed to be sorted`() {
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(44100, 8000, 48000, 16000))).isEqualTo(16000)
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(16000, 8000))).isEqualTo(16000)
    }

    /**
     * Nothing at or below the link rate is a device that is not really an SCO link, and then the
     * cheapest correct answer is the one that needs **no resampler at all**: `AudioHandler:267`
     * only builds a `ResamplingEncoder` when the input rate differs from 48 kHz, and task 8's
     * `CapturePipeline` takes a null resampler for the same reason. The plan's listing said "the
     * lowest rate" unconditionally, which for `{48000, 44100}` picks 44100 -- a resampling stage
     * bought for nothing, on the one branch that exists because the device is unusual already.
     */
    @Test
    fun `above the link rate it prefers the rate that needs no resampling`() {
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(48000, 44100))).isEqualTo(48000)
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(44100, 48000))).isEqualTo(48000)
    }

    /** Without 48 kHz on offer the lowest is the cheapest to resample from. */
    @Test
    fun `above the link rate and without 48 kHz it falls back to the lowest`() {
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(44100, 96000))).isEqualTo(44100)
        assertThat(ScoSampleRatePolicy.choose(intArrayOf(96000, 44100))).isEqualTo(44100)
    }

    // ------------------------------------------------------------------ findScoInput

    @Test
    fun `no input devices at all means no sco input`() {
        assertThat(ScoSampleRatePolicy.findScoInput(audioManager)).isNull()
    }

    @Test
    fun `a built-in microphone is not an sco input`() {
        shadowOf(audioManager).setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC)))

        assertThat(ScoSampleRatePolicy.findScoInput(audioManager)).isNull()
    }

    @Test
    fun `the sco input is found among other inputs`() {
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(audioManager).setInputDevices(
            listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC), sco, device(AudioDeviceInfo.TYPE_WIRED_HEADSET)),
        )

        assertThat(ScoSampleRatePolicy.findScoInput(audioManager)).isSameInstanceAs(sco)
    }

    /**
     * Output devices are a separate list and a headset appears in both; asking for inputs is what
     * makes the answer a thing we can record from. `getDevices(GET_DEVICES_OUTPUTS)` would answer
     * with the A2DP sink here.
     */
    @Test
    fun `an sco output alone is not an sco input`() {
        shadowOf(audioManager).setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))

        assertThat(ScoSampleRatePolicy.findScoInput(audioManager)).isNull()
    }
}
