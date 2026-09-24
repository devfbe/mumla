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
 * The decision behind the "Use Bluetooth headset automatically" setting. It writes only
 * [Settings.PREF_BLUETOOTH_SCO]; the service reads it and does the routing.
 *
 * The preference is the single source of truth for the user's wish, because the SCO link itself is
 * torn down on every disconnect (auto-reconnect included); `MumlaService` re-reads it on every
 * synchronization.
 *
 * Turning it on asks for `BLUETOOTH_CONNECT` first ([Result.PermissionNeeded]), and the wish is
 * stored by [onPermissionAnswered] whatever the answer was: the platform does not annotate
 * `startBluetoothSco()`/`setCommunicationDevice` as requiring it, so a denial must not take a working
 * headset away. Devices that are stricter than the annotations are handled by the caller of
 * `enableBluetoothSco()`, which reports a `SecurityException` in the chat log. Turning it off never
 * needs anything.
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
        get() = settings.isBluetoothScoEnabled

    /**
     * Move the preference to [enabled]. Turning it off always succeeds; turning it on persists
     * nothing unless `BLUETOOTH_CONNECT` is already granted.
     */
    fun request(enabled: Boolean): Result {
        if (!enabled) {
            settings.isBluetoothScoEnabled = false
            return Result.Disabled
        }
        if (!hasPermission(context)) {
            return Result.PermissionNeeded
        }
        settings.isBluetoothScoEnabled = true
        return Result.Enabled
    }

    fun toggle(): Result = request(!settings.isBluetoothScoEnabled)

    /**
     * The permission dialog has been answered, so store the wish that raised it. Deliberately takes
     * no answer: the wish does not depend on it.
     */
    fun onPermissionAnswered() {
        settings.isBluetoothScoEnabled = true
    }

    companion object {
        @JvmStatic
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}
