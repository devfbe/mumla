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
import se.lublin.humla.testutil.AllocationMeter

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
        var sink = 0f
        var user = 0
        val perCall = AllocationMeter.bytesPerCall(10_000, 100_000) {
            val id = user++
            sink += params.volume(id) + if (params.isMuted(id)) 1 else 0
        }

        assertThat(sink).isGreaterThan(0f)
        assertThat(perCall).isLessThan(1_024.0 / 100_000)
    }
}
