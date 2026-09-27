/*
 * Copyright (C) 2026 The Mumla Authors
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
package se.lublin.mumla.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import se.lublin.humla.model.Server

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectRequestsTest {
    @Test
    fun requestsWaitForTheirCollectorAndArriveOnce() = runTest(UnconfinedTestDispatcher()) {
        val requests = ConnectRequests()
        val server = Server(1, "a", "host", 64738, "me", null)
        requests.request(ServerRequest.Favourite(server))
        val seen = mutableListOf<ServerRequest>()

        val collecting = launch { requests.requested.toList(seen) }
        collecting.cancel()
        val again = launch { requests.requested.toList(seen) }
        again.cancel()

        assertThat(seen).containsExactly(ServerRequest.Favourite(server))
    }
}
