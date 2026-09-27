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

package se.lublin.mumla.servers

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository

@RunWith(RobolectricTestRunner::class)
class FavouriteServersViewModelTest {
    private val home = Server(3, "Home", "home.example", 0, "me", null)
    private val work = Server(4, "Work", "work.example", 0, "me", null)
    private val database = mockk<MumlaDatabase>(relaxed = true) { every { getServers() } returns listOf(home, work) }
    private val favourites = FavouriteServersViewModel(MumlaRepository(database, Dispatchers.Unconfined), { false })

    @Test
    fun theServersAreUnknownUntilReadAndThenWhatTheDatabaseHolds() {
        assertThat(favourites.servers.value).isNull()

        favourites.reload()
        idleMainLooper()

        assertThat(favourites.servers.value).containsExactly(home, work).inOrder()
    }

    @Test
    fun aDeletedServerLeavesTheListAtOnceAndTheDatabaseInTheBackground() {
        favourites.reload()
        idleMainLooper()

        favourites.delete(home)

        assertThat(favourites.servers.value).containsExactly(work)
        verify { database.removeServer(home) }
    }

    @Test
    fun withPingsDisallowedNothingIsPinged() {
        favourites.pings.request(home)

        assertThat(favourites.pings.replies.value).isEmpty()
    }
}
