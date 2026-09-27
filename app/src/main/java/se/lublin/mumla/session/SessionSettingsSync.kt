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
package se.lublin.mumla.session

import android.content.Context
import android.widget.Toast
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.util.changes

/**
 * Carries changed preferences to the current session: audio settings live, and a note when a
 * setting only takes effect on the next connection.
 */
class SessionSettingsSync(private val context: Context, private val sessions: SessionManager) {
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
        PreferenceManager.getDefaultSharedPreferences(context).changes(OBSERVED_KEYS).collect(::onPreferenceChanged)
    }

    internal fun onPreferenceChanged(key: String) {
        val session = sessions.session.value
        if (key in SessionSettings.AUDIO_KEYS && session != null) {
            // The result is ignored: audio settings never require a reconnect.
            session.configure(SessionSettings.withAudioSettings(session.config, Settings.getInstance(context)))
        }
        if (key in RECONNECT_KEYS && sessions.connected != null) {
            Toast.makeText(context, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    internal companion object {
        /** The settings a connection is made with; a change applies from the next one. */
        val RECONNECT_KEYS = setOf(Settings.CERT_ID.key, Settings.FORCE_TCP.key, Settings.USE_TOR.key)

        val OBSERVED_KEYS = SessionSettings.AUDIO_KEYS + RECONNECT_KEYS
    }
}
