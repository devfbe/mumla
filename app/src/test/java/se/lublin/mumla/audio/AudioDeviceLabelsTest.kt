/*
 * Copyright (C) 2026 The Mumla contributors
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

package se.lublin.mumla.audio

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.mumla.R

/**
 * What the chooser calls a device. The platform's product name is the phone's model for every
 * built-in device, so it is only used where it names something the user owns - a Bluetooth
 * headset, as the phone app shows one - and for types there is no better word for.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDeviceLabelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun label(type: Int, name: String) =
        AudioDeviceLabels.label(app.resources, CommunicationDevice(1, type, name))

    /**
     * Built-in devices are named for what they are, not for the phone; a cable or USB headset by
     * its kind; a Bluetooth one by its own name, or as a Bluetooth headset without one; anything
     * else by its name, or as an audio device.
     */
    @Test
    fun eachDeviceTypeIsLabelled() {
        val phone = "Pixel 9"
        val cases = listOf(
            Triple(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, phone, app.getString(R.string.audio_device_earpiece)),
            Triple(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, phone, app.getString(R.string.audio_device_speaker)),
            Triple(AudioDeviceInfo.TYPE_WIRED_HEADSET, phone, app.getString(R.string.audio_device_wired)),
            Triple(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, phone, app.getString(R.string.audio_device_wired)),
            Triple(AudioDeviceInfo.TYPE_USB_HEADSET, phone, app.getString(R.string.audio_device_usb)),
            Triple(AudioDeviceInfo.TYPE_USB_DEVICE, phone, app.getString(R.string.audio_device_usb)),
            Triple(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Jabra Evolve", "Jabra Evolve"),
            Triple(AudioDeviceInfo.TYPE_BLE_HEADSET, "Pixel Buds", "Pixel Buds"),
            Triple(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "", app.getString(R.string.audio_device_bluetooth)),
            Triple(AudioDeviceInfo.TYPE_HEARING_AID, "  ", app.getString(R.string.audio_device_bluetooth)),
            Triple(AudioDeviceInfo.TYPE_HDMI, "TV", "TV"),
            Triple(AudioDeviceInfo.TYPE_HDMI, "", app.getString(R.string.audio_device_other)),
        )
        for ((type, name, expected) in cases) {
            assertWithMessage("type $type named '$name'").that(label(type, name)).isEqualTo(expected)
        }
    }
}
