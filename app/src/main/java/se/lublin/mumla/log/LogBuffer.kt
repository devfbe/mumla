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

package se.lublin.mumla.log

import se.lublin.humla.util.HumlaLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

/**
 * The last [capacity] log lines, kept in memory for the user to share. Lock-free: a line costs an
 * index increment and one small object; formatting waits for [dump]. Under heavy contention a line
 * may overwrite one written concurrently for the same slot a full lap earlier.
 */
class LogBuffer(
    private val capacity: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : HumlaLog.Sink {
    private class Line(
        val millis: Long,
        val level: HumlaLog.Level,
        val tag: String,
        val thread: String,
        val message: String,
        val error: Throwable?,
    )

    private val slots = AtomicReferenceArray<Line?>(capacity)
    private val next = AtomicLong()

    init {
        require(capacity > 0)
    }

    override fun log(level: HumlaLog.Level, tag: String, message: String, error: Throwable?) {
        val line = Line(clock(), level, tag, Thread.currentThread().name, message, error)
        slots.set((next.getAndIncrement() % capacity).toInt(), line)
    }

    /**
     * The kept lines, oldest first, one per line with stack traces indented below; not redacted.
     * A line written meanwhile may take the place of the oldest.
     */
    fun dump(zone: ZoneId = ZoneId.systemDefault()): String {
        val end = next.get()
        val time = TIME.withZone(zone)
        return buildString {
            for (index in maxOf(0L, end - capacity) until end) {
                val line = slots.get((index % capacity).toInt()) ?: continue
                append(time.format(Instant.ofEpochMilli(line.millis))).append(' ')
                append(line.level.name[0]).append('/').append(line.tag)
                append(" [").append(line.thread).append("]: ").append(line.message).append('\n')
                line.error?.let { append(it.stackTraceToString().prependIndent("    ")).append('\n') }
            }
        }
    }
}
