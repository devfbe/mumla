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

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadConfigBundle
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.session.AudioConfig

/**
 * The settings screen writes preferences; this is where they stop being preferences and become the
 * behaviour of the running microphone. A key that does not arrive here is a switch that lies, which
 * is the failure class this whole project exists to remove.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceVadExtrasTest {
    private fun service(): HumlaService =
        Robolectric.buildService(HumlaService::class.java).create().get()

    private fun field(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("no field $name on ${target.javaClass}")
    }

    private fun inputMode(service: HumlaService) =
        field(service, "mActivityInputMode") as ActivityInputMode

    @Test
    fun `the whole vad configuration reaches the live detector`() {
        val service = service()
        val config = VadConfig.adaptive(
            snrFraction = 0.42f, holdTimeMs = 310L, onsetFrames = 3,
            hysteresisDb = 9f, adaptiveFloor = false, manualFloorDbfs = -52f,
        )

        service.configureExtras(
            Bundle().apply { putBundle(HumlaService.EXTRAS_VAD_CONFIG, VadConfigBundle.toBundle(config)) }
        )

        assertThat(inputMode(service).vadConfig).isEqualTo(config)
    }

    @Test
    fun `every mode the settings screen can write arrives as that mode`() {
        for (mode in VadMode.entries) {
            val service = service()
            service.configureExtras(
                Bundle().apply {
                    putBundle(
                        HumlaService.EXTRAS_VAD_CONFIG,
                        VadConfigBundle.toBundle(VadConfig(mode, 0.7f, 0.2f, 120L)),
                    )
                }
            )
            assertThat(inputMode(service).vadConfig.mode).isEqualTo(mode)
        }
    }

    @Test
    fun `a bundle with no vad config leaves the detector alone`() {
        val service = service()
        val before = inputMode(service).vadConfig
        service.configureExtras(Bundle().apply { putFloat(HumlaService.EXTRAS_AMPLITUDE_BOOST, 2f) })
        assertThat(inputMode(service).vadConfig).isEqualTo(before)
    }

    /**
     * **The two `requiresAudioRebuild` tests that lived here are gone with the method (task A9b).**
     * It answered by *key*: a bundle carrying only the detection threshold or the VAD config did
     * not rebuild, anything else did - including a write that set a value the pipeline already had,
     * which is 110 ms with the microphone dead for no change at all. `AudioController.reconfigure`
     * now answers by *value*, which is strictly finer: the live extras never change [AudioConfig],
     * so they still never rebuild, and a no-op write no longer does either.
     *
     * What replaced them, and why they are not in this file: the property is now about the running
     * pipeline rather than about a pure function, so it needs a session -
     * `HumlaServiceAudioTest.aLiveExtraDoesNotRebuildThePipeline` and
     * `anExtraWrittenWithTheSameValueDoesNotRebuildThePipeline`.
     */
    @Test
    fun `the detection threshold and the vad config are the two extras that reach a live object`() {
        val service = service()
        val before = inputMode(service).vadConfig

        service.configureExtras(Bundle().apply { putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD, 0.25f) })
        assertThat(inputMode(service).vadConfig.startThreshold).isEqualTo(0.25f)
        assertThat(service.getAudioConfigForTest()).isEqualTo(AudioConfig())

        service.configureExtras(
            Bundle().apply {
                putBundle(
                    HumlaService.EXTRAS_VAD_CONFIG,
                    VadConfigBundle.toBundle(VadConfig.probability(0.8f, 0.2f, 120L)),
                )
            }
        )
        assertThat(inputMode(service).vadConfig).isNotEqualTo(before)
        // Neither key is carried by AudioConfig, which is what makes them free of a rebuild.
        assertThat(service.getAudioConfigForTest()).isEqualTo(AudioConfig())
    }
}
