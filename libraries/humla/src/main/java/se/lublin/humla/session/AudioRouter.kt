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
 * Which device voice goes to, decided the way the phone app decides it. Replaces `ScoRouter`
 * (contract 9b, point 15): one router, one wish.
 *
 * **The wish is [choice], and it is the only one.** An explicit pick from the chooser; null means
 * "the default". It replaces `ScoRouter.wanted` rather than sitting beside it, so the route has
 * one truth. It survives a lost connection (the route does not, see [disengage]) and is dropped
 * when the device it names goes away, when the user picks what the default would have given
 * anyway, when a newly connected headset takes over, and by [forgetChoice] when the session ends.
 *
 * **The default** is, in order: the first Bluetooth headset when [bluetoothAutomatic] allows it,
 * then a plugged-in headset, then the speaker - or the earpiece when [earpieceByDefault], which is
 * what the handset mode was - and the other built-in one when that is missing (a tablet). Every one
 * of them is routed explicitly: the router holds the communication mode for the session, and in
 * that mode the platform's own default is the earpiece whatever the app would have wanted.
 *
 * **Plugging in and out.** A headset that appears during the session takes over, as in the phone
 * app - a Bluetooth one only when [bluetoothAutomatic] allows it - and the newest one wins. A chosen
 * device that disappears hands back to the default. Both come from the device callback, and only a
 * change of the device *set* triggers a new decision: a route change on its own is reported and
 * not fought, because a phone call takes the communication device and a router that re-selected on
 * every route event would argue with the dialler.
 *
 * **[bluetoothAutomatic] is derived state and not a source of truth (spec 4.1, binding).** The
 * user's standing wish for a headset has exactly one carrier, the `pref_bluetooth_sco` preference,
 * which reaches this field through `EXTRAS_BLUETOOTH_WANTED`/`enableBluetoothSco()`. Nothing in the
 * UI reads it here.
 *
 * **No permission is consulted here, and none can be (spec 4.1, binding).** A `SecurityException`
 * an OEM may throw anyway is caught one layer down, in [AndroidCommunicationDevices]; a refusal
 * arrives here as `select` returning false and is reported as [Listener.onRouteRefused], once per
 * [apply] - de-duplicating the chat line is the consumer's job.
 *
 * **The communication mode is the session's.** [engage] takes it before the first route and
 * [disengage] gives it back after the last, so a disconnected app no longer leaves the phone in
 * call mode. Without it `setCommunicationDevice` does not decide where a voice-call track plays.
 *
 * Main thread only. Nothing touches the platform before [engage]: routing voice with no voice
 * session holds an SCO link open for nothing.
 */
class AudioRouter(
    private val devices: CommunicationDevices,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * The device this router routes voice to changed: its type, or null when the route is the
         * platform's own. Reported once per transition, whoever caused it.
         */
        fun onRouteChanged(type: Int?)

        /** The platform refused to route to the device the router picked. */
        fun onRouteRefused()
    }

    /** Whether a connected Bluetooth headset is part of the default; see the class doc. */
    var bluetoothAutomatic: Boolean = false

    /** The built-in default is the earpiece rather than the speaker (the old handset mode). */
    var earpieceByDefault: Boolean = false

    /** The user's explicit pick, or null for the default. */
    var choice: Int? = null
        private set

    var isEngaged: Boolean = false
        private set

    /** Whether the current route is one this router selected, and so one it may give back. */
    private var claimed = false

    /** Whether this router holds the communication mode, and so has to give it back. */
    private var modeHeld = false

    /** The device ids present at the last decision; what is not in here has just arrived. */
    private var known: Set<Int> = emptySet()

    /** The last route reported; what makes [Listener.onRouteChanged] fire once per transition. */
    private var lastRouted: Int? = null

    private var applying = false

    /** True while the platform routes voice to a Bluetooth headset, whoever routed it. */
    val isBluetoothActive: Boolean get() = devices.current()?.type in BLUETOOTH

    init {
        devices.setOnChangedListener { onPlatformChanged() }
    }

    /** A session is up: take the route the wish and the default ask for. */
    fun engage() {
        isEngaged = true
        if (!modeHeld) {
            devices.setCommunicationMode(true)
            modeHeld = true
        }
        known = devices.available().mapTo(HashSet()) { it.id }
        apply()
    }

    /** The session is down: give the route and the mode back. The wish stays for the next session. */
    fun disengage() {
        isEngaged = false
        apply()
        if (modeHeld) {
            devices.setCommunicationMode(false)
            modeHeld = false
        }
    }

    /** The user picked a device from the chooser. */
    fun choose(id: Int) {
        choice = id
        apply()
    }

    /** Back to the default, for good: the session this choice was made in has ended. */
    fun forgetChoice() {
        choice = null
        apply()
    }

    /** What the chooser offers: every device the platform can route voice to, while engaged. */
    fun availableDevices(): List<CommunicationDevice> =
        if (isEngaged) devices.available() else emptyList()

    /** The device voice goes to, or null without a session. */
    fun activeDevice(): CommunicationDevice? = if (isEngaged) devices.current() else null

    /** Reconciles the platform's route with the wish and the default. */
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

    /** The default: a Bluetooth headset if one may be taken, a plugged-in one, the phone itself. */
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

    /**
     * Only a route this router took is its to give back. Clearing whatever else the platform chose
     * would take the user off their own speaker or wired headset for no reason they asked for.
     */
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
        /** What the user calls a Bluetooth headset, whether the platform says SCO or LE Audio. */
        val BLUETOOTH: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        /** A headset on a cable or on USB, which comes before the phone's own speaker. */
        val WIRED: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }
}
