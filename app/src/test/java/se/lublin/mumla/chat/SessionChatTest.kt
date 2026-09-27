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
package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Channel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.User
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubState

/** What of a session's events lands in its chat log, and how. */
@RunWith(RobolectricTestRunner::class)
class SessionChatTest {
    private val app = RuntimeEnvironment.getApplication()
    private val chat = SessionChat(NoticeFormatter(app), MainScope(), capacity = 3)
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val state = session.stubState(SessionState.Connected)
    private val events: MutableSharedFlow<HumlaEvent> = session.stubEvents()

    init {
        chat.follow(session)
    }

    private fun emit(vararg emitted: HumlaEvent) {
        emitted.forEach { events.tryEmit(it) }
        idleMainLooper()
    }

    private fun message(body: String): IMessage = object : IMessage {
        override val actor: Int = 1
        override val actorName: String? = "alice"
        override val targetChannels: List<Channel> = emptyList()
        override val targetTrees: List<Channel> = emptyList()
        override val targetUsers: List<User> = emptyList()
        override val message: String = body
        override val receivedTime: Long = 0L
    }

    @Test
    fun itStartsEmpty() {
        assertThat(chat.messages.value).isEmpty()
    }

    @Test
    fun noticesLandPhrasedAndWithTheirLevel() {
        val kick = HumlaEvent.UserKicked("Ann", "Mod", "spam", ban = false)

        emit(
            HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "careful"),
            HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, "broken"),
            kick,
        )

        assertThat(chat.messages.value.map { (it as IChatMessage.InfoMessage).type to it.body }).containsExactly(
            IChatMessage.InfoMessage.Type.WARNING to "careful",
            IChatMessage.InfoMessage.Type.ERROR to "broken",
            IChatMessage.InfoMessage.Type.WARNING to NoticeFormatter(app).format(kick),
        ).inOrder()
    }

    @Test
    fun receivedAndSentMessagesLandAsTheSameMessages() {
        val received = message("hi there")
        val sent = message("hello")

        emit(HumlaEvent.TextMessage(received), HumlaEvent.MessageSent(sent))

        assertThat(chat.messages.value.map { (it as IChatMessage.TextMessage).message })
            .containsExactly(received, sent).inOrder()
    }

    @Test
    fun theOtherEventsLeaveNoLine() {
        emit(HumlaEvent.PermissionDenied(HumlaEvent.DenyType.OTHER, "no"))

        assertThat(chat.messages.value).isEmpty()
    }

    @Test
    fun theLogIsBoundedAndDropsTheOldestFirst() {
        emit(*Array(4) { HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "m$it") })

        assertThat(chat.messages.value.map { it.body }).containsExactly("m1", "m2", "m3").inOrder()
    }

    @Test
    fun theReturnedLogCannotBeWrittenThrough() {
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "one"))

        @Suppress("UNCHECKED_CAST")
        val log = chat.messages.value as MutableList<IChatMessage>
        assertThrows(UnsupportedOperationException::class.java) {
            log.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, "sneaked in"))
        }
    }

    @Test
    fun clearEmptiesIt() {
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "one"))

        chat.clear()

        assertThat(chat.messages.value).isEmpty()
    }

    /** The log survives an automatic reconnect; the end of the session takes it along. */
    @Test
    fun onlyTheEndOfTheSessionClearsIt() {
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "kept"))

        state.value = SessionState.ConnectionLost(1L, 1, null)
        state.value = SessionState.Reconnecting(null)
        idleMainLooper()
        assertThat(chat.messages.value).hasSize(1)

        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(chat.messages.value).isEmpty()
    }
}
