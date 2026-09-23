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
 * Bluetooth SCO desired state (spec A4). [wanted] is what the user asked for and survives a headset
 * that walks away or a connection that drops; [isActive] is what the platform currently routes to.
 * [apply] reconciles the two: it selects the first `TYPE_BLUETOOTH_SCO` communication device when
 * wanted, and clears the route when not wanted and the route is SCO. Main thread only.
 *
 * **[wanted] is derived state and not a source of truth (spec 4.1, binding).** The user's wish has
 * exactly one carrier and it is the `pref_bluetooth_sco` preference on disk, which is what makes it
 * survive a reconnect - the complaint this whole line of work started from. This field is
 * initialised from that preference when a connection is made and is read by nothing in the UI. Give
 * it a second reader and the surface has two truths again.
 *
 * **No permission is consulted here, and none can be (spec 4.1, binding).** The wish alone decides.
 * The `SecurityException` an OEM may throw anyway is caught one layer down, in
 * [AndroidCommunicationDevices], where the call is; a refusal arrives here as `select` returning
 * false and is reported as [Listener.onScoUnavailable], which is the same thing a missing headset
 * is. The router cannot tell a denial from an absent headset and does not need to: both mean the
 * user asked for a headset and is not getting one.
 *
 * **[Listener.onScoUnavailable] fires per [apply], not once per state.** Two applies with no
 * headset report twice. That is deliberate at this layer - the router has no clock and no chat log
 * - and it makes de-duplication the consumer's decision; [ScoRouter] is not where a user-visible
 * line should be rate-limited.
 */
class ScoRouter(
    private val devices: CommunicationDevices,
    private val listener: Listener,
) {
    interface Listener {
        /** The actual SCO route changed, by us or by the system. */
        fun onScoActiveChanged(active: Boolean)

        /** SCO is wanted but no headset is available or the platform refused the route. */
        fun onScoUnavailable()
    }

    var wanted: Boolean = false

    /**
     * The last route this router reported. Not a cache of [isActive]: it is what makes
     * [Listener.onScoActiveChanged] fire once per *transition* where the platform raises its own
     * event once per change of any kind, including the ones that change nothing we care about.
     */
    private var lastActive = false

    val isActive: Boolean get() = devices.current()?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    init {
        devices.setOnChangedListener { notifyIfChanged() }
    }

    fun apply() {
        if (wanted) {
            if (!isActive) {
                val id = devices.available().firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.id
                if (id == null || !devices.select(id)) listener.onScoUnavailable()
            }
        } else if (isActive) {
            // Only an SCO route is ours to clear. Clearing whatever else the platform chose would
            // take the user off their own speaker or wired headset for no reason they asked for.
            devices.clear()
        }
        notifyIfChanged()
    }

    fun release() {
        devices.setOnChangedListener(null)
    }

    private fun notifyIfChanged() {
        val active = isActive
        if (active == lastActive) return
        lastActive = active
        listener.onScoActiveChanged(active)
    }
}
