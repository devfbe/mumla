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

package se.lublin.mumla.channel

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.session.CommunicationDevice
import se.lublin.mumla.R

/**
 * What the chooser calls a device. The platform's product name is the phone's model for every
 * built-in device, so it is only used where it names something the user owns - a Bluetooth
 * headset, as the phone app shows one - and for types there is no better word for.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDeviceLabelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun label(type: Int, name: String = "Pixel 9") =
        AudioDeviceLabels.label(app.resources, CommunicationDevice(1, type, name))

    @Test
    fun builtInDevicesAreNamedForWhatTheyAreAndNotForThePhone() {
        assertThat(label(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
            .isEqualTo(app.getString(R.string.audio_device_earpiece))
        assertThat(label(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            .isEqualTo(app.getString(R.string.audio_device_speaker))
    }

    @Test
    fun aHeadsetOnACableIsAWiredHeadset() {
        assertThat(label(AudioDeviceInfo.TYPE_WIRED_HEADSET))
            .isEqualTo(app.getString(R.string.audio_device_wired))
        assertThat(label(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
            .isEqualTo(app.getString(R.string.audio_device_wired))
    }

    @Test
    fun aUsbDeviceIsAUsbHeadset() {
        assertThat(label(AudioDeviceInfo.TYPE_USB_HEADSET))
            .isEqualTo(app.getString(R.string.audio_device_usb))
        assertThat(label(AudioDeviceInfo.TYPE_USB_DEVICE))
            .isEqualTo(app.getString(R.string.audio_device_usb))
    }

    @Test
    fun aBluetoothHeadsetIsShownByItsOwnName() {
        assertThat(label(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Jabra Evolve")).isEqualTo("Jabra Evolve")
        assertThat(label(AudioDeviceInfo.TYPE_BLE_HEADSET, "Pixel Buds")).isEqualTo("Pixel Buds")
    }

    @Test
    fun anUnnamedBluetoothHeadsetIsABluetoothHeadset() {
        assertThat(label(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, ""))
            .isEqualTo(app.getString(R.string.audio_device_bluetooth))
        assertThat(label(AudioDeviceInfo.TYPE_HEARING_AID, "  "))
            .isEqualTo(app.getString(R.string.audio_device_bluetooth))
    }

    @Test
    fun anythingElseIsShownByItsNameOrAsAnAudioDevice() {
        assertThat(label(AudioDeviceInfo.TYPE_HDMI, "TV")).isEqualTo("TV")
        assertThat(label(AudioDeviceInfo.TYPE_HDMI, ""))
            .isEqualTo(app.getString(R.string.audio_device_other))
    }
}
