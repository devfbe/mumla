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
 * `MumlaService` re-reads it on every synchronization rather than remembering anything.
 *
 * Turning it on raises the `BLUETOOTH_CONNECT` dialog first: [Result.PermissionNeeded] tells the
 * caller to ask, and the wish is written once the dialog has been answered, by
 * [onPermissionAnswered] -- whatever the answer was. That is the ruling in spec 4.1, *keep
 * asking, stop gating*. The permission is requested because spec P3 requires it before SCO is
 * used and because the store listing has carried the Nearby-devices entry since task 2; it is not
 * a precondition of the routing, because the platform's own annotation database does not make it
 * one: of 26 annotated `AudioManager` members exactly four carry a `RequiresPermission` and
 * `startBluetoothSco()` is not among them, and of the 136 members annotated with
 * `BLUETOOTH_CONNECT` none is in `android.media`. The same holds for `setCommunicationDevice`,
 * which stream A's task 8 migrates to. Refusing to route without it would take a working headset
 * away from everyone who taps "deny" -- and before this task the item asked for nothing at all.
 *
 * What the permission is still good for is the devices where the platform is stricter than its
 * own annotations. "Absent from the annotation database" is not "never throws anywhere", so the
 * caller of `enableBluetoothSco()` wraps it and reports a `SecurityException` in the chat log
 * rather than trusting this.
 *
 * Turning it off never needs anything -- a user who revoked the permission must still be able to
 * switch the wish off.
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
     * The permission dialog has been answered, so store the wish that raised it.
     *
     * It takes no answer, and that is the point: nothing about the wish depends on one. A
     * parameter here would be a gate waiting to be written back in, and the gate is what the
     * ruling removed. The caller still has the answer from the launcher and uses it to say what
     * a denial may cost.
     */
    fun onPermissionAnswered() {
        settings.setBluetoothScoEnabled(true)
    }

    companion object {
        @JvmStatic
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}
