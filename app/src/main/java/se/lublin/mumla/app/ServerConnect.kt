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
package se.lublin.mumla.app

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.session.SessionSettings
import se.lublin.mumla.util.ApplicationScope

/**
 * Connects to [server] with the user's settings in a new session. The work runs in the application
 * scope, so it completes even if the screen that asked goes away.
 */
fun startServerConnect(context: Context, server: Server): Job {
    val app = context.applicationContext
    val settings = Settings.getInstance(app)
    return ApplicationScope.of(app).launch {
        val config = MumlaRepository.get(app).io { SessionSettings.forServer(app, settings, this, server) }
        SessionManager.get(app).connect(config)
    }
}
