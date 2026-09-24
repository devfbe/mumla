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
package se.lublin.mumla.service

/**
 * The service's in-memory chat history, bounded at [capacity] entries (oldest dropped).
 * [snapshot] copies the list, never the messages: ChatAdapter's diff compares by identity.
 *
 * Main thread only, without a lock: observer callbacks arrive on the main looper and the send
 * calls are UI calls. Add a lock together with any writer on another thread.
 */
class ChatMessageLog(private val capacity: Int = MAX_ENTRIES) {
    private val entries = ArrayDeque<IChatMessage>()

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    val size: Int get() = entries.size

    fun add(message: IChatMessage) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(message)
    }

    /** A copy of the list; later additions do not change it. */
    fun snapshot(): List<IChatMessage> = ArrayList(entries)

    fun clear() {
        entries.clear()
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
