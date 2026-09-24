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

import android.content.res.Resources
import android.media.AudioDeviceInfo
import se.lublin.humla.session.CommunicationDevice
import se.lublin.mumla.R

/**
 * What the audio chooser calls a device, the way the phone app does. The platform's product name
 * is the phone's own model for every built-in device, so it is used only where it names something
 * the user owns - a Bluetooth headset - and for types there is no better word for.
 */
object AudioDeviceLabels {
    fun label(resources: Resources, device: CommunicationDevice): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> resources.getString(R.string.audio_device_earpiece)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> resources.getString(R.string.audio_device_speaker)
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> resources.getString(R.string.audio_device_wired)
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> resources.getString(R.string.audio_device_usb)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> device.name.ifBlank { resources.getString(R.string.audio_device_bluetooth) }
        else -> device.name.ifBlank { resources.getString(R.string.audio_device_other) }
    }
}
