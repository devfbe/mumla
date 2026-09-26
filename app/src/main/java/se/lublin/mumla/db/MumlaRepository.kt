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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's one [MumlaDatabase]. UI code goes through [io] so that queries run off the main thread;
 * [database] is for callers that already are off it, or not yet migrated.
 */
class MumlaRepository(
    val database: MumlaDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** For writes that must finish even when the screen that asked for them is gone. */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Runs [block] against the database on the I/O dispatcher. */
    suspend fun <T> io(block: MumlaDatabase.() -> T): T = withContext(dispatcher) { database.block() }

    /** Runs [block] against the database in the background, without waiting for it. */
    fun launchIo(block: MumlaDatabase.() -> Unit): Job = scope.launch { io(block) }

    val pinnedChannels = PinnedChannels(this, scope)

    /** Implemented by the Application, which owns the process's repository. */
    interface Owner {
        val repository: MumlaRepository
    }

    companion object {
        fun get(context: Context): MumlaRepository = (context.applicationContext as Owner).repository
    }
}
