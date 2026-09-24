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

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.testutil.awaitUntil

/** Runs everything queued on the paused main looper. */
fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

/**
 * Drains the main looper until [condition] holds, for work that hops to a background thread and
 * back, where one [idleMainLooper] is not enough.
 */
fun drainMainUntil(timeoutMillis: Long = 10_000L, description: String = "condition", condition: () -> Boolean) =
    awaitUntil(timeoutMillis, description) {
        idleMainLooper()
        condition()
    }
