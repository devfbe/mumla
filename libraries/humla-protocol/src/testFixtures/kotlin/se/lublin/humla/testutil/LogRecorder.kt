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

import org.junit.rules.ExternalResource
import se.lublin.humla.util.HumlaLog
import java.util.concurrent.CopyOnWriteArrayList

/** A rule that records every [HumlaLog] line written while a test runs. */
public class LogRecorder : ExternalResource() {
    public data class Line(val level: HumlaLog.Level, val tag: String, val message: String)

    private val recorded = CopyOnWriteArrayList<Line>()
    private val sink = HumlaLog.Sink { level, tag, message, _ -> recorded += Line(level, tag, message) }

    public val lines: List<Line> get() = recorded

    public fun messages(tag: String): List<String> = recorded.filter { it.tag == tag }.map { it.message }

    public fun clear(): Unit = recorded.clear()

    override fun before(): Unit = HumlaLog.addSink(sink)

    override fun after(): Unit = HumlaLog.removeSink(sink)
}
