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

package se.lublin.mumla.testing

import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.preference.SettingsActivity

/** The settings screen shown now. */
fun SettingsActivity.currentScreen(): PreferenceFragmentCompat =
    supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat

/** Taps the index entry of [screen], as the user does, and returns the screen that opened. */
fun <T : PreferenceFragmentCompat> SettingsActivity.openScreen(screen: Class<T>): T {
    val root = currentScreen()
    val entry = (0 until root.preferenceScreen.preferenceCount)
        .map { root.preferenceScreen.getPreference(it) }
        .single { it.fragment == screen.name }
    root.onPreferenceTreeClick(entry)
    idleMainLooper()
    return screen.cast(currentScreen())
}

/** The row [key] is shown in, with the list laid out tall enough to show every row. */
fun PreferenceFragmentCompat.rowOf(key: String): View {
    val preference = requireNotNull(findPreference<Preference>(key)) { "no preference $key" }
    listView.laidOutRows()
    val position = (listView.adapter as PreferenceGroup.PreferencePositionCallback)
        .getPreferenceAdapterPosition(preference)
    return requireNotNull(listView.findViewHolderForAdapterPosition(position)) { "$key is not shown" }.itemView
}
