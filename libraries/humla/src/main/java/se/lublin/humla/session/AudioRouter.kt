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
 * Decides which device voice goes to, the way the phone app does. Main thread only.
 *
 * Default: a Bluetooth headset if [bluetoothAutomatic], then a wired headset, then the speaker (or
 * earpiece if [earpieceByDefault]). Everything is routed explicitly, because in communication mode
 * the platform default is the earpiece. [choice] overrides the default until its device goes away
 * or a newly connected headset takes over (newest wins). Only a change of the device set triggers a
 * new decision, so the router doesn't fight the dialler during a call. Nothing touches the platform
 * before [engage], so no SCO link is held open without a voice session.
 */
class AudioRouter(
    private val devices: CommunicationDevices,
    private val listener: Listener,
) {
    interface Listener {
        /** The routed device's type, or null for the platform's own route; once per transition. */
        fun onRouteChanged(type: Int?)

        /** The platform refused to route to the device the router picked. */
        fun onRouteRefused()
    }

    var bluetoothAutomatic: Boolean = false

    var earpieceByDefault: Boolean = false

    /** The user's explicit pick, or null for the default. */
    var choice: Int? = null
        private set

    var isEngaged: Boolean = false
        private set

    /** Whether the current route is one this router selected and may give back. */
    private var claimed = false

    private var modeHeld = false

    /** Device ids present at the last decision; anything else has just arrived. */
    private var known: Set<Int> = emptySet()

    private var lastRouted: Int? = null

    private var applying = false

    /** True while the platform routes voice to a Bluetooth headset, whoever routed it. */
    val isBluetoothActive: Boolean get() = devices.current()?.type in BLUETOOTH

    init {
        devices.setOnChangedListener { onPlatformChanged() }
    }

    fun engage() {
        isEngaged = true
        if (!modeHeld) {
            devices.setCommunicationMode(true)
            modeHeld = true
        }
        known = devices.available().mapTo(HashSet()) { it.id }
        apply()
    }

    /** Gives the route and the mode back; [choice] stays for the next session. */
    fun disengage() {
        isEngaged = false
        apply()
        if (modeHeld) {
            devices.setCommunicationMode(false)
            modeHeld = false
        }
    }

    fun choose(id: Int) {
        choice = id
        apply()
    }

    fun forgetChoice() {
        choice = null
        apply()
    }

    /** Every device the platform can route voice to, while engaged. */
    fun availableDevices(): List<CommunicationDevice> =
        if (isEngaged) devices.available() else emptyList()

    /** The device voice goes to, or null without a session. */
    fun activeDevice(): CommunicationDevice? = if (isEngaged) devices.current() else null

    fun apply() {
        if (applying) return
        applying = true
        try {
            decide()
        } finally {
            applying = false
        }
        notifyIfChanged()
    }

    fun release() {
        devices.setOnChangedListener(null)
    }

    private fun decide() {
        if (!isEngaged) {
            giveBack()
            return
        }
        val available = devices.available()
        val arrived = available.filter { it.id !in known && takesOver(it) }
        known = available.mapTo(HashSet()) { it.id }
        arrived.lastOrNull()?.let { choice = it.id }
        val chosen = available.firstOrNull { it.id == choice }
        val automatic = automatic(available)
        if (chosen == null || chosen.id == automatic?.id) choice = null
        val target = if (choice != null) chosen else automatic
        if (target == null) {
            giveBack()
        } else if (!claimed || devices.current()?.id != target.id) {
            if (devices.select(target.id)) claimed = true else listener.onRouteRefused()
        }
    }

    private fun automatic(available: List<CommunicationDevice>): CommunicationDevice? {
        if (bluetoothAutomatic) available.firstOrNull { it.type in BLUETOOTH }?.let { return it }
        available.firstOrNull { it.type in WIRED }?.let { return it }
        val (preferred, other) = if (earpieceByDefault) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        return available.firstOrNull { it.type == preferred } ?: available.firstOrNull { it.type == other }
    }

    private fun takesOver(device: CommunicationDevice): Boolean =
        device.type in WIRED || (bluetoothAutomatic && device.type in BLUETOOTH)

    /** Only a route this router took is its to give back. */
    private fun giveBack() {
        if (!claimed) return
        devices.clear()
        claimed = false
    }

    private fun onPlatformChanged() {
        if (isEngaged && devices.available().mapTo(HashSet()) { it.id } != known) {
            apply()
        } else {
            notifyIfChanged()
        }
    }

    private fun notifyIfChanged() {
        val routed = if (claimed) devices.current()?.type else null
        if (routed == lastRouted) return
        lastRouted = routed
        listener.onRouteChanged(routed)
    }

    companion object {
        /** SCO or LE Audio. */
        val BLUETOOTH: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        val WIRED: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }
}
