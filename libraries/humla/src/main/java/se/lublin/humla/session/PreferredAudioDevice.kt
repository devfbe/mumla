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

import android.media.AudioDeviceInfo

/**
 * A device the user saved, by what outlives a connection: its [AudioDeviceInfo] [type] and, for
 * Bluetooth and USB, where several of a type exist, its [address]. Platform ids are not kept: they
 * change every time a device reconnects.
 */
data class PreferredAudioDevice(val type: Int, val address: String? = null) {
    /** Same type, and same address if one was saved. */
    fun matches(device: CommunicationDevice): Boolean =
        device.type == type && (address == null || device.address == address)

    companion object {
        private val ADDRESSED: Set<Int> = AudioRouter.BLUETOOTH + setOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        fun of(device: CommunicationDevice) = PreferredAudioDevice(
            device.type,
            device.address.takeIf { it.isNotEmpty() && device.type in ADDRESSED },
        )
    }
}
