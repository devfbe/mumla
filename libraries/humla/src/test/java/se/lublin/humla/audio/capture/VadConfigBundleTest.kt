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

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
class VadConfigBundleTest {
    /**
     * Pin the set, not the member (spec 4.04): a field added to [VadConfig] that this codec does
     * not carry would otherwise be a setting the settings screen writes and the running detector
     * never sees -- silently, because a `Bundle` answers a missing key with a default.
     */
    @Test
    fun `every field of the config survives the round trip`() {
        val config = VadConfig(
            VadMode.PROBABILITY,
            startThreshold = 0.83f,
            stopThreshold = 0.41f,
            holdTimeMs = 137L,
            snrFraction = 0.37f,
            hysteresisDb = 4.5f,
            onsetFrames = 3,
            adaptiveFloor = false,
            manualFloorDbfs = -57f,
        )
        assertThat(VadConfigBundle.fromBundle(VadConfigBundle.toBundle(config))).isEqualTo(config)
        // Every value above differs from the field's default, so a codec that dropped one would
        // hand back the default and fail the comparison rather than agreeing by luck.
        val declared = VadConfig::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
        assertThat(declared).containsExactlyElementsIn(VadConfigBundle.CARRIED_FIELDS)
    }

    @Test
    fun `every mode survives the round trip`() {
        for (mode in VadMode.entries) {
            val config = VadConfig(mode, 0.6f, 0.3f, 250L)
            assertThat(VadConfigBundle.fromBundle(VadConfigBundle.toBundle(config)).mode).isEqualTo(mode)
        }
    }

    @Test
    fun `a bundle from an older version reads as the default rather than throwing`() {
        assertThat(VadConfigBundle.fromBundle(Bundle())).isEqualTo(VadConfig.DEFAULT)
    }

    @Test
    fun `a bundle carrying values out of range is clamped instead of crashing the service`() {
        val bundle = VadConfigBundle.toBundle(VadConfig.DEFAULT).apply {
            putFloat("vad_start", 4f)
            putFloat("vad_stop", -1f)
            putInt("vad_onset", 0)
            putLong("vad_hold", -5L)
        }
        val config = VadConfigBundle.fromBundle(bundle)
        assertThat(config.startThreshold).isEqualTo(1f)
        assertThat(config.stopThreshold).isEqualTo(0f)
        assertThat(config.onsetFrames).isEqualTo(1)
        assertThat(config.holdTimeMs).isEqualTo(0L)
    }
}
