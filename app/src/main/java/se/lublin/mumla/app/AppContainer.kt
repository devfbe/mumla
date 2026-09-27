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

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import se.lublin.humla.HumlaSession
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionConfig
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.chat.SessionChat
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.session.SessionSettingsSync
import se.lublin.mumla.ui.AppMessages

/** The objects the whole process shares, wired by hand once per process. Main thread. */
class AppContainer(
    app: Application,
    scope: CoroutineScope,
    newSession: (SessionConfig) -> IHumlaSession = { config -> HumlaSession(app, config) },
) {
    val appMessages = AppMessages()

    val sessionManager = SessionManager(
        newSession = newSession,
        startForeground = { MumlaService.start(app) },
        chat = SessionChat(NoticeFormatter(app), scope),
    )

    init {
        SessionSettingsSync(app, sessionManager, appMessages).start(scope)
    }
}
