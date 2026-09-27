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

package se.lublin.humla.testutil

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Runs everything queued on Robolectric's paused main looper. */
public fun idleMainLooper(): Unit = shadowOf(Looper.getMainLooper()).idle()

/** Moves the paused main looper's clock on by [duration], running everything that comes due. */
public fun idleMainLooperFor(duration: Duration): Unit = shadowOf(Looper.getMainLooper()).idleFor(duration)
