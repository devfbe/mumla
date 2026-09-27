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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.session.inMainThreadSlices

/**
 * The chat history of the session [follow] was last given: its notices and its received and sent
 * messages, emptied when a session begins and when it ends. Main thread.
 */
class SessionChat(
    private val notices: NoticeFormatter,
    private val scope: CoroutineScope,
    capacity: Int = ChatMessageLog.MAX_ENTRIES,
) {
    private val log = ChatMessageLog(capacity)
    private var following: Job? = null

    /** The history, newest last; a new list per change. */
    val messages: StateFlow<List<IChatMessage>> get() = log.messages

    /** Starts over with [session]; subscribed before this returns, so nothing it emits is missed. */
    fun follow(session: IHumlaSession) {
        following?.cancel()
        log.clear()
        following = scope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                // Not on a lost connection: the log survives automatic reconnects.
                session.state.collect { if (it is SessionState.Disconnected) log.clear() }
            }
            session.events.inMainThreadSlices().collect(::onEvent)
        }
    }

    fun clear() = log.clear()

    /** Adds a warning of the app's own, unless it repeats the last line. */
    fun warnOnce(text: String) {
        val last = log.messages.value.lastOrNull() as? IChatMessage.InfoMessage
        if (last?.type == IChatMessage.InfoMessage.Type.WARNING && last.body == text) return
        log.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, text))
    }

    private fun onEvent(event: HumlaEvent) {
        when (event) {
            is HumlaEvent.Notice -> log.add(IChatMessage.InfoMessage(infoType(event.level), notices.format(event)))
            is HumlaEvent.TextMessage -> log.add(IChatMessage.TextMessage(event.message))
            is HumlaEvent.MessageSent -> log.add(IChatMessage.TextMessage(event.message))
            else -> Unit
        }
    }

    private fun infoType(level: HumlaEvent.Level) = when (level) {
        HumlaEvent.Level.INFO -> IChatMessage.InfoMessage.Type.INFO
        HumlaEvent.Level.WARNING -> IChatMessage.InfoMessage.Type.WARNING
        HumlaEvent.Level.ERROR -> IChatMessage.InfoMessage.Type.ERROR
    }
}
