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
package se.lublin.mumla.session

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.MainScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.chat.SessionChat
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubState

/** [bindClient] feeds a client the current session, its state and its events, and nothing else. */
@RunWith(RobolectricTestRunner::class)
class SessionClientTest {
    private val sessions = SessionManager(
        newSession = { mockk(relaxed = true) },
        startForeground = {},
        chat = SessionChat(NoticeFormatter(org.robolectric.RuntimeEnvironment.getApplication()), MainScope()),
    )
    private val calls = mutableListOf<String>()

    private val client = object : SessionClient {
        override fun onSessionAttached(session: IHumlaSession) {
            calls += "attached ${names[session]}"
        }

        override fun onSessionDetached() {
            calls += "detached"
        }

        override fun onSessionState(state: SessionState) {
            calls += "state $state"
        }

        override fun onSessionEvent(event: HumlaEvent) {
            calls += "event $event"
        }
    }

    private val first: IHumlaSession = mockk(relaxed = true)
    private val second: IHumlaSession = mockk(relaxed = true)
    private val names = mapOf(first to "first", second to "second")
    private val firstState = first.stubState(SessionState.Connecting)
    private val firstEvents = first.stubEvents()
    private val secondEvents = second.stubEvents().also { second.stubState(SessionState.Connecting) }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private val event = HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "hi")

    @Test
    fun aClientIsAttachedAtOnceToTheCurrentSessionAndGetsItsState() {
        sessions.adopt(first)

        sessions.bindClient(owner, client)

        assertThat(calls).containsExactly("attached first", "state Connecting").inOrder()
    }

    @Test
    fun theStateFollowsTheSession() {
        sessions.bindClient(owner, client)
        sessions.adopt(first)

        firstState.value = SessionState.Connected
        idleMainLooper()

        assertThat(calls).containsExactly("attached first", "state Connecting", "state Connected").inOrder()
    }

    @Test
    fun aNewSessionDetachesTheFirstBeforeAttachingIt() {
        sessions.bindClient(owner, client)

        sessions.adopt(first)
        sessions.adopt(second)

        assertThat(calls).containsExactly(
            "attached first", "state Connecting", "detached", "attached second", "state Connecting",
        ).inOrder()
    }

    @Test
    fun onlyTheCurrentSessionsEventsAreDelivered() {
        sessions.bindClient(owner, client)
        sessions.adopt(first)
        firstEvents.tryEmit(event)
        idleMainLooper()
        sessions.adopt(second)
        firstEvents.tryEmit(HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, "stale"))
        secondEvents.tryEmit(event)
        idleMainLooper()

        assertThat(calls.filter { it.startsWith("event") }).containsExactly("event $event", "event $event")
        assertThat(calls.indexOf("detached")).isLessThan(calls.lastIndexOf("event $event"))
    }

    @Test
    fun eventsEmittedWhileTheClientAttachesAreNotMissed() {
        val emitting = object : SessionClient {
            override fun onSessionAttached(session: IHumlaSession) {
                firstEvents.tryEmit(event)
            }

            override fun onSessionEvent(event: HumlaEvent) {
                calls += "event $event"
            }
        }
        sessions.bindClient(owner, emitting)

        sessions.adopt(first)
        idleMainLooper()

        assertThat(calls).containsExactly("event $event")
    }

    @Test
    fun destroyingTheOwnerDetachesTheClientAndStopsItsEvents() {
        sessions.bindClient(owner, client)
        sessions.adopt(first)
        val chatListening = firstEvents.subscriptionCount.value - 1

        owner.registry.currentState = Lifecycle.State.DESTROYED
        firstEvents.tryEmit(event)
        idleMainLooper()

        assertThat(calls).containsExactly("attached first", "state Connecting", "detached").inOrder()
        assertThat(firstEvents.subscriptionCount.value).isEqualTo(chatListening)
    }
}
