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

package se.lublin.mumla.testing

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.robolectric.Shadows.shadowOf

/** A device as the platform lists it; `AudioDeviceInfoBuilder` can set neither an id nor an address. */
fun platformDevice(id: Int, type: Int, address: String = "", name: String = "Robolectric"): AudioDeviceInfo =
    mockk {
        every { this@mockk.id } returns id
        every { this@mockk.type } returns type
        every { this@mockk.address } returns address
        every { productName } returns name
    }

/** The platform offers [devices] for communication, or else earpiece 11, speaker 12 and headset 17. */
fun AudioManager.offerCommunicationDevices(
    devices: List<AudioDeviceInfo> = listOf(
        platformDevice(11, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        platformDevice(12, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        platformDevice(17, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA", "Sony WH"),
    ),
) {
    shadowOf(this).setAvailableCommunicationDevices(devices)
}

/** Neither the audio mode nor the communication route was touched. */
fun AudioManager.assertUntouched() {
    assertThat(mode).isEqualTo(AudioManager.MODE_NORMAL)
    assertThat(communicationDevice).isNull()
}
