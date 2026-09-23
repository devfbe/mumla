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
 * The service's in-memory chat history, bounded at [capacity] entries: adding entry
 * [capacity] + 1 drops the oldest one (spec D5: 500 entries, oldest dropped; stream A owns the log
 * because it owns MumlaService).
 *
 * The instances handed in are the instances handed out: [snapshot] copies the list, never the
 * messages, because ChatAdapter's diff compares by identity.
 *
 * **Confined to the main thread, deliberately without a lock.** Every writer runs there -- the
 * observer callbacks arrive through HumlaCallbacks, which delivers on the main looper whichever
 * thread raised them, and `sendUserTextMessage`/`sendChannelTextMessage` are UI calls -- and the one
 * reader is the chat fragment binding to the service. A lock here would have no observable a test
 * could name (spec 4.04). Whoever adds a writer on another thread adds the lock with it.
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
        /** Spec D5: 500 entries, oldest dropped. */
        const val MAX_ENTRIES = 500
    }
}
