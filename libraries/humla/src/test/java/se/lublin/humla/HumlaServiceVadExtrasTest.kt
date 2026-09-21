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
     * The 110 ms the rebuild costs, spent only when it buys something. Before this, dragging the
     * detection-threshold slider tore the capture chain down and built it again per step, to
     * deliver a value `setThreshold` writes into a live object.
     */
    @Test
    fun `only the extras that cannot reach a live object rebuild the audio chain`() {
        assertThat(HumlaService.requiresAudioRebuild(setOf(HumlaService.EXTRAS_DETECTION_THRESHOLD))).isFalse()
        assertThat(HumlaService.requiresAudioRebuild(setOf(HumlaService.EXTRAS_VAD_CONFIG))).isFalse()
        assertThat(
            HumlaService.requiresAudioRebuild(
                setOf(HumlaService.EXTRAS_DETECTION_THRESHOLD, HumlaService.EXTRAS_VAD_CONFIG)
            )
        ).isFalse()

        assertThat(HumlaService.requiresAudioRebuild(setOf(HumlaService.EXTRAS_NOISE_SUPPRESSION_METHOD))).isTrue()
        assertThat(HumlaService.requiresAudioRebuild(setOf(HumlaService.EXTRAS_ANDROID_AGC))).isTrue()
        // One live key beside one that is not is still a rebuild: the answer is about the bundle.
        assertThat(
            HumlaService.requiresAudioRebuild(
                setOf(HumlaService.EXTRAS_VAD_CONFIG, HumlaService.EXTRAS_ECHO_CANCELLATION_METHOD)
            )
        ).isTrue()
    }

    /**
     * Pin the set (spec 4.04): every extra that is *not* in the live set must be one this service
     * really cannot apply without a rebuild. A key added to the live set by mistake is a setting
     * the user changes and nothing happens, which no other test here would see.
     */
    @Test
    fun `the live extras are exactly the two that write into objects a rebuild keeps`() {
        assertThat(HumlaService.LIVE_AUDIO_EXTRAS).containsExactly(
            HumlaService.EXTRAS_DETECTION_THRESHOLD,
            HumlaService.EXTRAS_VAD_CONFIG,
        )
    }
}
