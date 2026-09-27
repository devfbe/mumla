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

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.MainScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.session.ConnectionConfig
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.collectOnMain
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.chat.IChatMessage
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.chat.SessionChat
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubState

/** One session per connect, the foreground service started for it, and the chat that follows it. */
@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {
    private val created = mutableListOf<IHumlaSession>()
    private var foregroundStarts = 0
    private var endAtOnce = false
    private val sessions = SessionManager(
        newSession = { config ->
            mockk<IHumlaSession>(relaxed = true).also { session ->
                session.stubEvents()
                val state = session.stubState(SessionState.Disconnected())
                every { session.config } returns config
                every { session.connect() } answers {
                    state.value = if (endAtOnce) SessionState.Disconnected(DisconnectReason.Failed("no", null))
                    else SessionState.Connecting
                }
                created += session
            }
        },
        startForeground = { foregroundStarts++ },
        chat = SessionChat(NoticeFormatter(RuntimeEnvironment.getApplication()), MainScope()),
    )
    private val config = SessionConfig(ConnectionConfig(server = Server(1, "a", "host", 64738, "me", null)))

    @Test
    fun beforeTheFirstConnectThereIsNoSession() {
        assertThat(sessions.session.value).isNull()
        assertThat(sessions.currentState).isEqualTo(SessionState.Disconnected())
        assertThat(sessions.isActive).isFalse()
        assertThat(sessions.connected).isNull()
    }

    @Test
    fun aConnectMakesAConnectingSessionCurrentAndStartsTheForegroundService() {
        sessions.connect(config)

        val session = created.single()
        assertThat(sessions.session.value).isSameInstanceAs(session)
        verify { session.connect() }
        assertThat(sessions.isActive).isTrue()
        assertThat(sessions.connected).isNull()
        assertThat(foregroundStarts).isEqualTo(1)
    }

    @Test
    fun aSessionThatEndsAtOnceNeedsNoForegroundService() {
        endAtOnce = true

        sessions.connect(config)

        assertThat(foregroundStarts).isEqualTo(0)
        assertThat(sessions.isActive).isFalse()
    }

    @Test
    fun onlyASynchronizedSessionCountsAsConnected() {
        sessions.connect(config)

        created.single().stubState(SessionState.Connected)

        assertThat(sessions.connected).isSameInstanceAs(created.single())
    }

    /** The next connect replaces the session and closes the old one, after observers moved on. */
    @Test
    fun theNextConnectClosesThePreviousSession() {
        sessions.connect(config)
        val first = created.single()

        sessions.connect(config)

        verify { first.close() }
        assertThat(sessions.session.value).isSameInstanceAs(created[1])
    }

    @Test
    fun reconnectConnectsANewSessionWithTheLastConfiguration() {
        sessions.connect(config)

        sessions.reconnect()

        assertThat(created).hasSize(2)
        verify { created[1].connect() }
        assertThat(created[1].config).isSameInstanceAs(config)
    }

    @Test
    fun reconnectWithoutASessionDoesNothing() {
        sessions.reconnect()

        assertThat(created).isEmpty()
    }

    @Test
    fun cancellingTheReconnectCountsTheReasonAsShownAndTheNextConnectForgetsIt() {
        sessions.connect(config)

        sessions.cancelReconnect()
        verify { created.single().cancelReconnect() }
        assertThat(sessions.errorShown.value).isTrue()

        sessions.connect(config)
        assertThat(sessions.errorShown.value).isFalse()
    }

    @Test
    fun theFlattenedStateFollowsTheCurrentSession() {
        val seen = mutableListOf<SessionState>()
        val job = collectOnMain(sessions.state) { seen += it }

        sessions.connect(config)
        idleMainLooper()
        created.single().stubState(SessionState.Connected)
        idleMainLooper()
        job.cancel()

        assertThat(seen.first()).isEqualTo(SessionState.Disconnected())
        assertThat(seen.last()).isEqualTo(SessionState.Connected)
        assertThat(seen).contains(SessionState.Connecting)
    }

    /** The chat follows the current session only, and starts empty with each. */
    @Test
    fun theChatFollowsTheCurrentSession() {
        sessions.connect(config)
        val first = created.single()
        first.stubEvents().tryEmit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "first"))
        idleMainLooper()
        assertThat(sessions.chat.messages.value.map { it.body }).containsExactly("first")

        sessions.connect(config)
        first.stubEvents().tryEmit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "stale"))
        idleMainLooper()

        assertThat(sessions.chat.messages.value).isEmpty()
    }

    /** The log survives a lost connection and goes with the end of the session. */
    @Test
    fun theChatIsClearedWhenTheSessionEndsButNotWhenItsConnectionIsLost() {
        sessions.connect(config)
        val session = created.single()
        session.stubEvents().tryEmit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "kept"))
        idleMainLooper()

        session.stubState(SessionState.ConnectionLost(1L, 1, null))
        idleMainLooper()
        assertThat(sessions.chat.messages.value).hasSize(1)

        session.stubState(SessionState.Disconnected())
        idleMainLooper()
        assertThat(sessions.chat.messages.value).isEmpty()
    }

    @Test
    fun theAppVisibilityIsWhatItWasLastSetTo() {
        assertThat(sessions.appVisible.value).isFalse()
        sessions.setAppVisible(true)
        assertThat(sessions.appVisible.value).isTrue()
    }

    @Test
    fun aRepeatedWarningOfTheAppsOwnIsLoggedOnce() {
        sessions.chat.warnOnce("refused")
        sessions.chat.warnOnce("refused")

        assertThat(sessions.chat.messages.value.map { (it as IChatMessage.InfoMessage).body })
            .containsExactly("refused")
    }
}
