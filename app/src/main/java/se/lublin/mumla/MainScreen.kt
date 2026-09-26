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
package se.lublin.mumla

import android.content.Context
import android.content.Intent

/**
 * How other components, e.g. notifications, open a screen of the main activity. The activity is
 * named rather than referenced, so that the service does not depend on the UI.
 */
object MainScreen {
    const val ACTIVITY_CLASS = "se.lublin.mumla.app.MumlaActivity"

    /** The Int extra with the drawer id of the screen to show when the activity is created. */
    const val EXTRA_SCREEN = "drawer_fragment"

    /** The drawer id of the connected server's channel list. */
    const val CHANNELS = 1

    fun intent(context: Context, screen: Int): Intent =
        Intent().setClassName(context, ACTIVITY_CLASS).putExtra(EXTRA_SCREEN, screen)
}
