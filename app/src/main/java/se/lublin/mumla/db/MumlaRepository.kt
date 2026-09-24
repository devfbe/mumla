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
package se.lublin.mumla.db

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.lublin.mumla.app.MumlaApplication

/**
 * The app's one [MumlaDatabase]. UI code goes through [io] so that queries run off the main thread;
 * [database] is for callers that already are off it, or not yet migrated.
 */
class MumlaRepository(
    val database: MumlaDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Runs [block] against the database on the I/O dispatcher. */
    suspend fun <T> io(block: MumlaDatabase.() -> T): T = withContext(dispatcher) { database.block() }

    companion object {
        /** The application's repository. */
        @JvmStatic
        fun get(context: Context): MumlaRepository = (context.applicationContext as MumlaApplication).repository
    }
}
