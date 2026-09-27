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
package se.lublin.mumla.audio

import android.content.Context
import android.media.AudioManager
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.audio.routing.listCommunicationDevices
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/** One entry of the audio device chooser; a null [id] is "Automatic". */
data class AudioDeviceChoice(val id: Int?, val label: String, val selected: Boolean)

/**
 * The audio device chooser and the echo switch. [session] is the session while connected, else
 * null; without one a choice is only saved.
 */
class AudioDeviceControls(
    private val context: Context,
    private val settings: Settings,
    private val session: () -> IHumlaSession?,
) {
    /**
     * "Automatic", then the session's devices, or without a session the ones the platform offers for
     * calls. The saved device is selected while voice goes to it (or, without a session, while it is
     * there), otherwise "Automatic". Read anew on every call, so a headset switched on shows up.
     */
    fun choices(): List<AudioDeviceChoice> {
        val session = session()
        val devices = devices(session)
        val active = session?.audio?.activeDevice
        val saved = settings.preferredAudioDevice
        val selected = devices.firstOrNull { saved?.matches(it) == true && (session == null || it.id == active?.id) }
        val resources = context.resources
        val automatic = if (selected == null && active != null) {
            resources.getString(R.string.audio_device_automatic_current, AudioDeviceLabels.label(resources, active))
        } else {
            resources.getString(R.string.audio_device_automatic)
        }
        return listOf(AudioDeviceChoice(null, automatic, selected == null)) +
            devices.map { AudioDeviceChoice(it.id, AudioDeviceLabels.label(resources, it), it.id == selected?.id) }
    }

    /** The session's devices, or without one what the platform offers, read without routing. */
    private fun devices(session: IHumlaSession?): List<CommunicationDevice> =
        session?.audio?.devices ?: listCommunicationDevices(context.getSystemService(AudioManager::class.java))

    /**
     * Saves the device with [id], or "Automatic" for null; with a session it also takes effect now.
     * Saved first, so the session already routes by the new preference when the explicit choice
     * arrives. Without a session nothing is routed: that would put the phone in call mode and duck
     * other apps.
     */
    fun choose(id: Int?) {
        val session = session()
        if (id == null) {
            settings.preferredAudioDevice = null
            session?.audio?.selectAutomaticDevice()
        } else {
            // Gone since the choices were read: nothing to save.
            val device = devices(session).firstOrNull { it.id == id } ?: return
            settings.preferredAudioDevice = PreferredAudioDevice.of(device)
            session?.audio?.selectDevice(device.id)
        }
    }

    /**
     * The kind of device the echo switch is for: the active one's with a session (null while the
     * session has none), else the saved device's, else the speaker's, as the microphone test plays.
     */
    val echoCategory: AudioDeviceCategory?
        get() {
            val session = session()
            if (session != null) return session.audio.activeDevice?.let { AudioDeviceCategory.of(it.type) }
            return settings.preferredAudioDevice?.let { AudioDeviceCategory.of(it.type) } ?: AudioDeviceCategory.SPEAKER
        }

    /** What runs, or will run, on a device of [echoCategory]. */
    val isEchoCancellationEnabled: Boolean
        get() = session()?.audio?.isEchoCancellationEnabled
            ?: echoCategory?.let(settings::isEchoCancellationEnabled) ?: false

    /** Remembers the choice for [echoCategory]'s kind; a session applies it live and on every routing. */
    fun setEchoCancellationEnabled(enabled: Boolean) {
        val category = echoCategory ?: return
        settings.setEchoCancellationOverride(category, enabled)
    }
}
