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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubEvents

@RunWith(RobolectricTestRunner::class)
class ServiceViewModelTest {

    private val model = ServiceViewModel()
    private val calls = mutableListOf<String>()

    private val client = object : ServiceClient {
        override fun onServiceBound(service: IMumlaService) {
            calls += "bound ${names[service]}"
        }

        override fun onServiceUnbound() {
            calls += "unbound"
        }

        override fun onServiceEvent(event: HumlaEvent) {
            calls += "event $event"
        }
    }

    private val first: IMumlaService = mockk(relaxed = true)
    private val second: IMumlaService = mockk(relaxed = true)
    private val names = mapOf(first to "first", second to "second")
    private val firstEvents = first.stubEvents()
    private val secondEvents = second.stubEvents()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()

    @Test
    fun aClientIsBoundAtOnceToTheServiceAlreadyAttached() {
        model.attach(first)

        model.bindClient(owner, client)

        assertThat(calls).containsExactly("bound first")
    }

    @Test
    fun attachingAndDetachingBindsAndUnbindsTheClient() {
        model.bindClient(owner, client)

        model.attach(first)
        model.attach(null)

        assertThat(calls).containsExactly("bound first", "unbound").inOrder()
    }

    @Test
    fun anotherServiceUnbindsTheFirstBeforeBindingIt() {
        model.bindClient(owner, client)

        model.attach(first)
        model.attach(second)

        assertThat(calls).containsExactly("bound first", "unbound", "bound second").inOrder()
    }

    @Test
    fun onlyTheBoundServicesEventsAreDelivered() {
        model.bindClient(owner, client)
        model.attach(first)
        firstEvents.tryEmit(HumlaEvent.Connecting)
        idleMainLooper()
        model.attach(second)
        firstEvents.tryEmit(HumlaEvent.Connected)
        secondEvents.tryEmit(HumlaEvent.Connected)
        idleMainLooper()

        assertThat(calls).containsExactly(
            "bound first",
            "event Connecting",
            "unbound",
            "bound second",
            "event Connected",
        ).inOrder()
    }

    @Test
    fun eventsEmittedWhileTheClientBindsAreNotMissed() {
        val emitting = object : ServiceClient {
            override fun onServiceBound(service: IMumlaService) {
                firstEvents.tryEmit(HumlaEvent.Connected)
            }

            override fun onServiceEvent(event: HumlaEvent) {
                calls += "event $event"
            }
        }
        model.bindClient(owner, emitting)

        model.attach(first)
        idleMainLooper()

        assertThat(calls).containsExactly("event Connected")
    }

    @Test
    fun destroyingTheOwnerUnbindsTheClientAndStopsItsEvents() {
        model.bindClient(owner, client)
        model.attach(first)

        owner.registry.currentState = Lifecycle.State.DESTROYED
        firstEvents.tryEmit(HumlaEvent.Connected)
        idleMainLooper()

        assertThat(calls).containsExactly("bound first", "unbound").inOrder()
        assertThat(firstEvents.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun isConnectedFollowsTheBoundServicesSessionState() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<SessionState>(SessionState.Connecting)
        every { first.sessionState } returns state
        val seen = mutableListOf<Boolean>()
        val collecting = launch { model.isConnected.toList(seen) }

        model.attach(first)
        state.value = SessionState.Connected
        state.value = SessionState.Disconnected()
        state.value = SessionState.Connected
        model.attach(null)
        collecting.cancel()

        assertThat(seen).containsExactly(false, true, false, true, false).inOrder()
    }

    @Test
    fun connectRequestsWaitForTheirCollectorAndArriveOnce() = runTest(UnconfinedTestDispatcher()) {
        val server = Server(1, "a", "host", 64738, "me", null)
        model.requestConnect(ServerRequest.Favourite(server))
        val seen = mutableListOf<ServerRequest>()

        val collecting = launch { model.connectRequests.toList(seen) }
        collecting.cancel()
        val again = launch { model.connectRequests.toList(seen) }
        again.cancel()

        assertThat(seen).containsExactly(ServerRequest.Favourite(server))
    }
}
