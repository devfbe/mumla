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

/**
 * The developer log of the library and the app. Each line goes to every installed [Sink]; with
 * none installed it is dropped. Thread-safe: a line costs a volatile read plus the sinks' own work,
 * but its message is built by the caller, so real-time audio loops must not log.
 */
public object HumlaLog {
    public enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    public fun interface Sink {
        /** Called on the logging thread; must not block. */
        public fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    private var sinks: Array<Sink> = emptyArray()

    /** Installing the same sink twice has no effect. */
    @Synchronized
    public fun addSink(sink: Sink) {
        if (sink !in sinks) sinks += sink
    }

    @Synchronized
    public fun removeSink(sink: Sink) {
        sinks = sinks.filterNot { it === sink }.toTypedArray()
    }

    public fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        for (sink in sinks) sink.log(level, tag, message, error)
    }

    public fun v(tag: String, message: String): Unit = log(Level.VERBOSE, tag, message)

    public fun d(tag: String, message: String, error: Throwable? = null): Unit = log(Level.DEBUG, tag, message, error)

    public fun i(tag: String, message: String, error: Throwable? = null): Unit = log(Level.INFO, tag, message, error)

    public fun w(tag: String, message: String, error: Throwable? = null): Unit = log(Level.WARN, tag, message, error)

    public fun e(tag: String, message: String, error: Throwable? = null): Unit = log(Level.ERROR, tag, message, error)
}
