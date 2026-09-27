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
package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.model.UserState
import java.lang.management.ManagementFactory

class PlaybackParamsTest {

    private val users = listOf(
        UserState(300, "loud", 0, localVolume = 2f),
        UserState(5, "plain", 0),
        UserState(1_000, "muted", 0, isLocalMuted = true),
    )

    @Test
    fun eachUserIsPlayedAsThisDeviceSays() {
        val params = PlaybackParams.of(users)

        assertThat(params.volume(300)).isEqualTo(2f)
        assertThat(params.volume(5)).isEqualTo(1f)
        assertThat(params.isMuted(1_000)).isTrue()
        assertThat(params.isMuted(300)).isFalse()
        assertThat(params.volume(42)).isEqualTo(1f)
        assertThat(params.isMuted(42)).isFalse()
    }

    @Test
    fun usersWithoutAnyLocalChoiceShareTheDefault() {
        assertThat(PlaybackParams.of(listOf(UserState(5, "plain", 0)))).isSameInstanceAs(PlaybackParams.DEFAULT)
    }

    @Test
    fun aLookupAllocatesNothing() {
        val params = PlaybackParams.of(users)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        var sink = 0f
        repeat(10_000) { sink += params.volume(it) }
        val before = threads.getThreadAllocatedBytes(id)
        repeat(100_000) { sink += params.volume(it) + if (params.isMuted(it)) 1 else 0 }
        val allocated = threads.getThreadAllocatedBytes(id) - before

        assertThat(sink).isGreaterThan(0f)
        assertThat(allocated).isLessThan(1_024L)
    }
}
