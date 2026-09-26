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

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * The service's in-memory chat history, bounded at [capacity] entries (oldest dropped).
 * [snapshot] copies the list, never the messages: ChatAdapter's diff compares by identity.
 *
 * Main thread only, without a lock: events arrive on the main thread and the send calls are UI
 * calls. Add a lock together with any writer on another thread.
 */
class ChatMessageLog(private val capacity: Int = MAX_ENTRIES) {
    private val entries = ArrayDeque<IChatMessage>()
    private val published = MutableStateFlow<List<IChatMessage>>(emptyList())

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** The current history as a read-only snapshot per change. */
    val messages: StateFlow<List<IChatMessage>> = published.asStateFlow()

    val size: Int get() = entries.size

    fun add(message: IChatMessage) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(message)
        publish()
    }

    /** A read-only copy of the list; later additions do not change it. */
    fun snapshot(): List<IChatMessage> = published.value

    fun clear() {
        entries.clear()
        publish()
    }

    private fun publish() {
        published.value = Collections.unmodifiableList(ArrayList(entries))
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
