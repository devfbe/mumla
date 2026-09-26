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
package se.lublin.mumla.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** The scope for work that must outlive the screen that started it. */
object ApplicationScope {
    /** Implemented by the Application. */
    interface Owner {
        val scope: CoroutineScope
    }

    fun of(context: Context): CoroutineScope = (context.applicationContext as Owner).scope
}
