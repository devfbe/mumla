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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import se.lublin.mumla.Settings

/**
 * The decision behind the "Bluetooth" action-bar item and the "Bluetooth headset" settings
 * checkbox (spec P2/P3). Both write the persistent preference [Settings.PREF_BLUETOOTH_SCO] and
 * nothing else; the service reads it and does the routing.
 *
 * The split this class exists for is *wanted* against *active*. An SCO link is torn down by
 * `HumlaService.onConnectionDisconnected` on every drop, auto-reconnect included, and was never
 * re-established -- so a state that lives only in the audio stack cannot answer "did the user ask
 * for a headset?". The preference can, which is why it is the single source of truth and why
 * [shouldRouteToBluetooth] is re-evaluated on every synchronization rather than remembered.
 *
 * Turning it on needs `BLUETOOTH_CONNECT`; [Result.PermissionNeeded] tells the caller to ask, and
 * nothing is persisted until [onPermissionResult] says it was granted. Turning it off never needs
 * anything -- a user who revoked the permission must still be able to switch the wish off.
 */
class BluetoothScoToggle(
    private val context: Context,
    private val settings: Settings,
) {
    sealed interface Result {
        data object Enabled : Result
        data object Disabled : Result
        data object PermissionNeeded : Result
    }

    val isEnabled: Boolean
        get() = settings.isBluetoothScoEnabled()

    /**
     * Move the preference to [enabled]. Turning it off always succeeds; turning it on persists
     * nothing unless `BLUETOOTH_CONNECT` is already granted.
     */
    fun request(enabled: Boolean): Result {
        if (!enabled) {
            settings.setBluetoothScoEnabled(false)
            return Result.Disabled
        }
        if (!hasPermission(context)) {
            return Result.PermissionNeeded
        }
        settings.setBluetoothScoEnabled(true)
        return Result.Enabled
    }

    fun toggle(): Result = request(!settings.isBluetoothScoEnabled())

    /**
     * Fold the outcome of the permission dialog into the preference.
     *
     * @return the wish as it stands afterwards. A denial changes nothing: it is an answer about
     *   the permission, not about what the user wants, and [shouldRouteToBluetooth] already keeps
     *   an unpermitted wish from reaching the audio stack.
     */
    fun onPermissionResult(granted: Boolean): Boolean {
        if (granted) {
            settings.setBluetoothScoEnabled(true)
        }
        return settings.isBluetoothScoEnabled()
    }

    companion object {
        @JvmStatic
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Whether the service should route voice through a Bluetooth headset right now: the user
         * wants it *and* the permission is still there. Called by `MumlaService` on connection
         * synchronization and on every change of the preference while connected.
         */
        @JvmStatic
        fun shouldRouteToBluetooth(context: Context, settings: Settings): Boolean =
            settings.isBluetoothScoEnabled() && hasPermission(context)
    }
}
