/*
 * Copyright (C) 2026 The Mumla authors
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import se.lublin.humla.model.Server

/** Whether applying one extra makes `configureExtras` ask for a reconnect. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class HumlaServiceExtrasReconnectTest(private val key: String, private val reconnect: Boolean) {
    @Test
    fun configureExtrasReportsWhetherTheExtraNeedsAReconnect() {
        val controller = Robolectric.buildService(HumlaService::class.java).create()
        try {
            assertThat(controller.get().configureExtras(bundleFor(key))).isEqualTo(reconnect)
        } finally {
            controller.destroy()
        }
    }

    companion object {
        private val server = Server(-1, "test", "127.0.0.1", 64738, "me", "")

        /** Complete: [HumlaServiceCharacterizationTest] checks it against the declared constants. */
        val RECONNECT_NEEDED: Map<String, Boolean> = mapOf(
            HumlaService.EXTRAS_SERVER to true,
            HumlaService.EXTRAS_AUTO_RECONNECT to false,
            HumlaService.EXTRAS_CERTIFICATE to true,
            HumlaService.EXTRAS_CERTIFICATE_PASSWORD to true,
            HumlaService.EXTRAS_DETECTION_THRESHOLD to false,
            HumlaService.EXTRAS_AMPLITUDE_BOOST to false,
            HumlaService.EXTRAS_TRANSMIT_MODE to false,
            HumlaService.EXTRAS_INPUT_RATE to false,
            HumlaService.EXTRAS_INPUT_QUALITY to false,
            HumlaService.EXTRAS_USE_TOR to true,
            HumlaService.EXTRAS_FORCE_TCP to true,
            HumlaService.EXTRAS_CLIENT_NAME to true,
            HumlaService.EXTRAS_ACCESS_TOKENS to false,
            HumlaService.EXTRAS_AUDIO_SOURCE to false,
            HumlaService.EXTRAS_AUDIO_STREAM to false,
            HumlaService.EXTRAS_FRAMES_PER_PACKET to false,
            HumlaService.EXTRAS_TRUST_STORE to true,
            HumlaService.EXTRAS_TRUST_STORE_PASSWORD to true,
            HumlaService.EXTRAS_TRUST_STORE_FORMAT to true,
            HumlaService.EXTRAS_HALF_DUPLEX to false,
            HumlaService.EXTRAS_LOCAL_MUTE_HISTORY to true,
            HumlaService.EXTRAS_LOCAL_IGNORE_HISTORY to true,
            HumlaService.EXTRAS_ENABLE_PREPROCESSOR to false,
            HumlaService.EXTRAS_ECHO_CANCELLATION_BY_DEVICE to false,
            HumlaService.EXTRAS_NOISE_SUPPRESSION_METHOD to false,
            HumlaService.EXTRAS_SPEEX_NOISE_SUPPRESS_DB to false,
            HumlaService.EXTRAS_ANDROID_NOISE_SUPPRESSOR to false,
            HumlaService.EXTRAS_ANDROID_AGC to false,
            HumlaService.EXTRAS_VAD_CONFIG to false,
            HumlaService.EXTRAS_BLUETOOTH_WANTED to false,
            HumlaService.EXTRAS_EARPIECE_BY_DEFAULT to false,
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} -> reconnect {1}")
        fun cases(): List<Array<Any>> = RECONNECT_NEEDED.map { (key, reconnect) -> arrayOf(key, reconnect) }

        /** One bundle carrying one key, with a value of the type `configureExtras` reads it back as. */
        private fun bundleFor(key: String): Bundle = Bundle().apply {
            when (key) {
                HumlaService.EXTRAS_SERVER -> putParcelable(key, server)
                HumlaService.EXTRAS_CERTIFICATE -> putByteArray(key, byteArrayOf(1, 2, 3))
                HumlaService.EXTRAS_ACCESS_TOKENS -> putStringArrayList(key, arrayListOf("token"))
                HumlaService.EXTRAS_LOCAL_MUTE_HISTORY -> putIntegerArrayList(key, arrayListOf(7))
                HumlaService.EXTRAS_LOCAL_IGNORE_HISTORY -> putIntegerArrayList(key, arrayListOf(8))
                HumlaService.EXTRAS_DETECTION_THRESHOLD -> putFloat(key, 0.25f)
                HumlaService.EXTRAS_AMPLITUDE_BOOST -> putFloat(key, 1.5f)
                HumlaService.EXTRAS_TRANSMIT_MODE -> putInt(key, Constants.TRANSMIT_CONTINUOUS)
                HumlaService.EXTRAS_INPUT_RATE -> putInt(key, 48000)
                HumlaService.EXTRAS_INPUT_QUALITY -> putInt(key, 40000)
                HumlaService.EXTRAS_AUDIO_SOURCE -> putInt(key, 7)
                HumlaService.EXTRAS_AUDIO_STREAM -> putInt(key, 3)
                HumlaService.EXTRAS_FRAMES_PER_PACKET -> putInt(key, 4)
                HumlaService.EXTRAS_AUTO_RECONNECT,
                HumlaService.EXTRAS_USE_TOR,
                HumlaService.EXTRAS_FORCE_TCP,
                HumlaService.EXTRAS_HALF_DUPLEX,
                HumlaService.EXTRAS_BLUETOOTH_WANTED,
                HumlaService.EXTRAS_EARPIECE_BY_DEFAULT,
                HumlaService.EXTRAS_ENABLE_PREPROCESSOR -> putBoolean(key, true)
                HumlaService.EXTRAS_ECHO_CANCELLATION_BY_DEVICE ->
                    putBundle(key, Bundle().apply { putBoolean("SPEAKER", false) })
                else -> putString(key, "value-for-$key")
            }
        }
    }
}
