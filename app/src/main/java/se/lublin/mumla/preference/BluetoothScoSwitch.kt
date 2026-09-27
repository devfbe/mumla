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

import android.Manifest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.BluetoothScoToggle
import se.lublin.mumla.ui.showPermissionDeniedSnackbar

/**
 * The "use Bluetooth headset automatically" switch of [fragment], which asks for
 * `BLUETOOTH_CONNECT` when turned on without it. Created with the fragment: a fragment may not
 * register a launcher once created.
 */
class BluetoothScoSwitch(private val fragment: PreferenceFragmentCompat) {
    private lateinit var toggle: BluetoothScoToggle

    private val permissionRequester: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The answer does not decide the wish (see BluetoothScoToggle); the switch follows it.
            toggle.onPermissionAnswered()
            preference().isChecked = toggle.isEnabled
            if (!granted) {
                fragment.requireActivity().showPermissionDeniedSnackbar(R.string.bluetooth_perm_denied)
            }
        }

    private fun preference(): SwitchPreferenceCompat =
        requireNotNull(fragment.findPreference(Settings.BLUETOOTH_SCO.key))

    /** Wires the switch; called once its preferences are created. */
    fun bind() {
        val context = fragment.requireContext()
        toggle = BluetoothScoToggle(context.applicationContext, Settings.getInstance(context))
        preference().setOnPreferenceChangeListener { _, newValue ->
            when (toggle.request(newValue as Boolean)) {
                BluetoothScoToggle.Result.Enabled, BluetoothScoToggle.Result.Disabled -> true
                BluetoothScoToggle.Result.PermissionNeeded -> {
                    permissionRequester.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    // Keep the switch off until the dialog is answered; the callback writes the wish.
                    false
                }
            }
        }
    }
}
