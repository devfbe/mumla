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

/** The library's developer log, so that only this file knows the platform's logger. */
internal object HumlaLog {
    fun interface Sink {
        /** [priority] is one of the `android.util.Log` levels. */
        fun log(priority: Int, tag: String, message: String, error: Throwable?)
    }

    private val platform = Sink { priority, tag, message, error ->
        when (priority) {
            Log.VERBOSE -> Log.v(tag, message, error)
            Log.DEBUG -> Log.d(tag, message, error)
            Log.INFO -> Log.i(tag, message, error)
            Log.WARN -> Log.w(tag, message, error)
            else -> Log.e(tag, message, error)
        }
    }

    /** Where every line goes; tests that assert on the log replace it. */
    @Volatile
    var sink: Sink = platform

    fun resetSink() {
        sink = platform
    }

    fun v(tag: String, message: String) = sink.log(Log.VERBOSE, tag, message, null)

    fun d(tag: String, message: String, error: Throwable? = null) = sink.log(Log.DEBUG, tag, message, error)

    fun i(tag: String, message: String) = sink.log(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = sink.log(Log.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = sink.log(Log.ERROR, tag, message, error)
}
