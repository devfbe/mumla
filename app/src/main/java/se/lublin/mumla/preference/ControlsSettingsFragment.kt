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

import androidx.preference.PreferenceCategory
import se.lublin.humla.audio.TransmitMode
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/** The buttons, keys and screen controls that transmit, and how they behave. */
class ControlsSettingsFragment : MumlaPreferenceFragment(R.xml.settings_controls) {
    override fun onResume() {
        super.onResume()
        // The transmit mode is chosen on the audio screen, so it may have changed meanwhile.
        requireNotNull(findPreference<PreferenceCategory>(KEY_PTT)).isEnabled =
            Settings.getInstance(requireContext()).transmitMode == TransmitMode.PUSH_TO_TALK
    }

    private companion object {
        const val KEY_PTT = "ptt_settings"
    }
}
