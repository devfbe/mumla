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

package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.AudioConfig
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.session.SessionConfig

/** The configured VAD settings reach the running microphone configuration. */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceVadConfigTest {
    private fun service(): HumlaService =
        Robolectric.buildService(HumlaService::class.java).create().get()

    private fun inputMode(service: HumlaService) =
        service.mActivityInputMode

    @Test
    fun `the whole vad configuration reaches the live detector`() {
        val service = service()
        val config = VadConfig.adaptive(
            snrFraction = 0.42f, holdTimeMs = 310L, onsetFrames = 3,
            hysteresisDb = 9f, adaptiveFloor = false, manualFloorDbfs = -52f,
        )

        service.configure(SessionConfig(vadConfig = config))

        assertThat(inputMode(service).vadConfig).isEqualTo(config)
    }

    @Test
    fun `every mode the settings screen can write arrives as that mode`() {
        for (mode in VadMode.entries) {
            val service = service()
            service.configure(SessionConfig(vadConfig = VadConfig(mode, 0.7f, 0.2f, 120L)))
            assertThat(inputMode(service).vadConfig.mode).isEqualTo(mode)
        }
    }

    @Test
    fun `the detector starts with the default configuration`() {
        assertThat(inputMode(service()).vadConfig).isEqualTo(SessionConfig().vadConfig)
    }

    /** The VAD config reaches a live object, which is what makes a change free of a rebuild. */
    @Test
    fun `the vad config is not part of the audio config`() {
        val service = service()

        service.configure(SessionConfig(vadConfig = VadConfig.probability(0.8f, 0.2f, 120L)))

        assertThat(service.getAudioConfigForTest()).isEqualTo(AudioConfig())
    }
}
