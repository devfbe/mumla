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
package se.lublin.mumla.preference

import android.content.res.Resources
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.mumla.R
import se.lublin.mumla.channel.AudioDeviceLabels

/**
 * The audio device setting's entries: "Automatic" (a null device), the [available] devices, and the
 * [saved] one after them if it is not among them. [selected] is the index of the saved one, or 0.
 */
internal class AudioDeviceChoices private constructor(
    val labels: List<String>,
    val devices: List<PreferredAudioDevice?>,
    val selected: Int,
) {
    companion object {
        fun of(
            resources: Resources,
            available: List<CommunicationDevice>,
            saved: PreferredAudioDevice?,
        ): AudioDeviceChoices {
            val labels = mutableListOf(resources.getString(R.string.audio_device_automatic))
            val devices = mutableListOf<PreferredAudioDevice?>(null)
            var selected = 0
            for (device in available) {
                val isSaved = selected == 0 && saved?.matches(device) == true
                labels += AudioDeviceLabels.label(resources, device)
                devices += if (isSaved) saved else PreferredAudioDevice.of(device)
                if (isSaved) selected = devices.lastIndex
            }
            if (saved != null && selected == 0) {
                // Only type and address are saved, so it is named by its type.
                val away = CommunicationDevice(0, saved.type, "", saved.address.orEmpty())
                val name = AudioDeviceLabels.label(resources, away)
                labels += resources.getString(R.string.audio_device_not_connected, name)
                devices += saved
                selected = devices.lastIndex
            }
            return AudioDeviceChoices(labels, devices, selected)
        }
    }
}
