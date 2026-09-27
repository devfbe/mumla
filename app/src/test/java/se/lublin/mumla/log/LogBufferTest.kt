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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.util.HumlaLog.Level
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class LogBufferTest {
    private var now = 0L
    private val buffer = LogBuffer(capacity = 3) { now }

    private fun messages(): List<String> = buffer.dump(ZoneOffset.UTC).lines()
        .filter { it.isNotEmpty() && !it.startsWith(" ") }
        .map { it.substringAfter("]: ") }

    @Test
    fun anEmptyBufferDumpsNothing() {
        assertThat(buffer.dump()).isEmpty()
    }

    @Test
    fun aLineCarriesTimeLevelTagThreadAndMessage() {
        now = 1_000L
        buffer.log(Level.WARN, "Tag", "hello", null)

        assertThat(buffer.dump(ZoneOffset.UTC))
            .isEqualTo("01-01 00:00:01.000 W/Tag [${Thread.currentThread().name}]: hello\n")
    }

    @Test
    fun onlyTheLastLinesAreKeptOldestFirst() {
        (1..5).forEach { buffer.log(Level.INFO, "T", "line $it", null) }

        assertThat(messages()).containsExactly("line 3", "line 4", "line 5").inOrder()
    }

    @Test
    fun anErrorsStackTraceIsIndentedBelowItsLine() {
        buffer.log(Level.ERROR, "T", "failed", IllegalStateException("boom"))

        val lines = buffer.dump().lines()
        assertThat(lines[0]).endsWith("]: failed")
        assertThat(lines[1]).isEqualTo("    java.lang.IllegalStateException: boom")
        assertThat(lines[2]).startsWith("    \tat ")
    }

    @Test
    fun concurrentWritersFillEverySlotWithWholeLines() {
        val big = LogBuffer(capacity = 100)
        val start = CountDownLatch(1)
        val writers = (0 until 4).map { w ->
            thread { start.await(); repeat(1_000) { big.log(Level.DEBUG, "T", "$w-$it", null) } }
        }
        start.countDown()
        writers.forEach { it.join() }

        val kept = big.dump().lines().filter { it.isNotEmpty() }
        assertThat(kept).hasSize(100)
        kept.forEach { assertThat(it).matches(".* D/T \\[.*]: [0-3]-\\d+") }
    }
}
