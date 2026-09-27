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
package se.lublin.humla.net

/**
 * Running count, mean and population variance of ping round trips in milliseconds, what the
 * client's `Ping` reports to the server. Not thread-safe.
 */
internal class PingStats {
    var count = 0
        private set
    private var mean = 0.0
    private var sumOfSquares = 0.0

    fun add(millis: Double) {
        count++
        val delta = millis - mean
        mean += delta / count
        sumOfSquares += delta * (millis - mean)
    }

    val average: Float get() = mean.toFloat()
    val variance: Float get() = if (count == 0) 0f else (sumOfSquares / count).toFloat()
}
