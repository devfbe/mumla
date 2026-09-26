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

package se.lublin.humla.session

import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreferredAudioDeviceTest {

    @Test
    fun aBluetoothHeadsetIsSavedWithItsAddress() {
        val saved = PreferredAudioDevice.of(CommunicationDevice(7, TYPE_BLUETOOTH_SCO, "Jabra", "AA"))

        assertThat(saved).isEqualTo(PreferredAudioDevice(TYPE_BLUETOOTH_SCO, "AA"))
    }

    @Test
    fun aUsbHeadsetIsSavedWithItsAddress() {
        assertThat(PreferredAudioDevice.of(CommunicationDevice(3, TYPE_USB_HEADSET, "", "card=1")))
            .isEqualTo(PreferredAudioDevice(TYPE_USB_HEADSET, "card=1"))
    }

    /** Other devices are one of a kind; whatever address the platform reports is not kept. */
    @Test
    fun otherDevicesAreSavedByTypeAlone() {
        assertThat(PreferredAudioDevice.of(CommunicationDevice(2, TYPE_BUILTIN_SPEAKER, "Pixel", "x")))
            .isEqualTo(PreferredAudioDevice(TYPE_BUILTIN_SPEAKER))
        assertThat(PreferredAudioDevice.of(CommunicationDevice(4, TYPE_WIRED_HEADSET, "", "")))
            .isEqualTo(PreferredAudioDevice(TYPE_WIRED_HEADSET))
    }

    @Test
    fun anEmptyAddressIsNoAddress() {
        assertThat(PreferredAudioDevice.of(CommunicationDevice(7, TYPE_BLUETOOTH_SCO, "", "")).address).isNull()
    }

    /** The id is the platform's for this connection only and plays no part. */
    @Test
    fun matchingIgnoresTheId() {
        val saved = PreferredAudioDevice(TYPE_BLUETOOTH_SCO, "AA")

        assertThat(saved.matches(CommunicationDevice(99, TYPE_BLUETOOTH_SCO, "", "AA"))).isTrue()
        assertThat(saved.matches(CommunicationDevice(99, TYPE_BLUETOOTH_SCO, "", "BB"))).isFalse()
        assertThat(saved.matches(CommunicationDevice(99, TYPE_WIRED_HEADSET, "", "AA"))).isFalse()
    }

    @Test
    fun withoutAnAddressAnyDeviceOfTheTypeMatches() {
        val anyHeadset = PreferredAudioDevice(TYPE_BLUETOOTH_SCO)

        assertThat(anyHeadset.matches(CommunicationDevice(1, TYPE_BLUETOOTH_SCO, "", "AA"))).isTrue()
    }
}
