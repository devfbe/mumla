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

package se.lublin.humla.util

import android.util.Log
import se.lublin.humla.util.HumlaLog.Level

/** Sends [HumlaLog] lines to logcat. */
public object AndroidLogSink : HumlaLog.Sink {
    override fun log(level: Level, tag: String, message: String, error: Throwable?) {
        when (level) {
            Level.VERBOSE -> Log.v(tag, message, error)
            Level.DEBUG -> Log.d(tag, message, error)
            Level.INFO -> Log.i(tag, message, error)
            Level.WARN -> Log.w(tag, message, error)
            Level.ERROR -> Log.e(tag, message, error)
        }
    }
}
