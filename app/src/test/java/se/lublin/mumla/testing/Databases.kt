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
package se.lublin.mumla.testing

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.MumlaSQLiteDatabase

/**
 * Makes [database] the app's database, queried inline so that UI code reading it off the main
 * thread finishes before the test looks.
 */
fun installDatabase(
    database: MumlaDatabase = MumlaSQLiteDatabase(ApplicationProvider.getApplicationContext()),
): MumlaDatabase {
    ApplicationProvider.getApplicationContext<MumlaApplication>()
        .installRepository(MumlaRepository(database, Dispatchers.Unconfined))
    return database
}
