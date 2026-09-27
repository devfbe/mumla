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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PingStatsTest {
    @Test
    fun emptyStatsHaveNoSamples() {
        val stats = PingStats()
        assertThat(stats.count).isEqualTo(0)
        assertThat(stats.average).isEqualTo(0f)
        assertThat(stats.variance).isEqualTo(0f)
    }

    @Test
    fun averageAndPopulationVarianceOfTheSamples() {
        val stats = PingStats()
        for (ms in listOf(10.0, 20.0, 30.0, 40.0)) stats.add(ms)

        assertThat(stats.count).isEqualTo(4)
        assertThat(stats.average).isWithin(1e-4f).of(25f)
        // ((15^2 + 5^2) * 2) / 4, as desktop Mumble's accumulators compute it.
        assertThat(stats.variance).isWithin(1e-4f).of(125f)
    }
}
