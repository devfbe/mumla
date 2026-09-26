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
package se.lublin.mumla.app

import android.media.AudioManager
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.core.view.MenuProvider
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.audio.routing.listCommunicationDevices
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.AudioDeviceLabels

/**
 * The toolbar's audio chooser: "Automatic", the devices on offer and the echo switch. [session] is
 * the session while connected, else null; without one a choice is only saved.
 */
class AudioDeviceMenu(
    private val activity: ComponentActivity,
    private val settings: Settings,
    private val session: () -> IHumlaSession?,
) : MenuProvider {

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.audio_device, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.menu_audio_device)?.let(::fill)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when {
        menuItem.itemId == R.id.menu_audio_device -> {
            fill(menuItem)
            // Not consumed, so the platform goes on to open the submenu just refilled.
            false
        }
        menuItem.itemId == R.id.menu_audio_echo -> {
            toggleEchoCancellation()
            true
        }
        menuItem.groupId == R.id.menu_audio_device_group -> {
            choose(menuItem.itemId)
            true
        }
        else -> false
    }

    /**
     * Fills the chooser: "Automatic", then the session's devices, or without a session the ones the
     * platform offers for calls. The saved device is ticked while voice goes to it (or, without a
     * session, while it is there), otherwise "Automatic". Called on menu preparation and again when
     * the chooser opens, so a headset switched on in between shows up.
     */
    private fun fill(chooser: MenuItem) {
        val sub = chooser.subMenu ?: return
        sub.removeGroup(R.id.menu_audio_device_group)
        val session = session()
        val devices = devices(session)
        val active = session?.activeAudioDevice
        val saved = settings.preferredAudioDevice
        val ticked = devices.firstOrNull { saved?.matches(it) == true && (session == null || it.id == active?.id) }
        val resources = activity.resources
        val automatic = if (ticked == null && active != null) {
            resources.getString(R.string.audio_device_automatic_current, AudioDeviceLabels.label(resources, active))
        } else {
            resources.getString(R.string.audio_device_automatic)
        }
        sub.add(R.id.menu_audio_device_group, R.id.menu_audio_device_automatic, Menu.NONE, automatic)
            .setChecked(ticked == null)
        for (device in devices) {
            sub.add(R.id.menu_audio_device_group, device.id, Menu.NONE, AudioDeviceLabels.label(resources, device))
                .setChecked(device.id == ticked?.id)
        }
        sub.setGroupCheckable(R.id.menu_audio_device_group, true, true)
        // The echo canceller for the active device's kind; nothing runs without a session.
        sub.findItem(R.id.menu_audio_echo)?.let { echo ->
            echo.isVisible = active != null
            echo.isChecked = session?.isEchoCancellationEnabled == true
        }
    }

    /** The session's devices, or without one what the platform offers, read without routing. */
    private fun devices(session: IHumlaSession?): List<CommunicationDevice> =
        session?.audioDevices ?: listCommunicationDevices(activity.getSystemService(AudioManager::class.java))

    /**
     * Saves the tapped entry; with a session it also takes effect now. Saved first, so the service
     * already routes by the new preference when the session call arrives. Without a session nothing
     * is routed: that would put the phone in call mode and duck other apps.
     */
    private fun choose(itemId: Int) {
        val session = session()
        if (itemId == R.id.menu_audio_device_automatic) {
            settings.preferredAudioDevice = null
            session?.selectAutomaticAudioDevice()
        } else {
            // Gone since the menu was filled: nothing to save.
            val device = devices(session).firstOrNull { it.id == itemId } ?: return
            settings.preferredAudioDevice = PreferredAudioDevice.of(device)
            session?.selectAudioDevice(device.id)
        }
        activity.invalidateMenu()
    }

    /** Flips the echo canceller of the active device's kind; the service applies it live and on every routing. */
    private fun toggleEchoCancellation() {
        val session = session() ?: return
        val active = session.activeAudioDevice ?: return
        settings.setEchoCancellationOverride(AudioDeviceCategory.of(active.type), !session.isEchoCancellationEnabled)
        activity.invalidateMenu()
    }
}
