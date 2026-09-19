/*
 * Copyright (C) 2015 Andrew Comminos
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

import se.lublin.humla.model.IMessage

/** A general chat message, either a text message from a user or an informational notice. */
interface IChatMessage {
    /** The (HTML) body of the message. */
    val body: String

    /** Unix timestamp in milliseconds when the message was received. */
    val receivedTime: Long

    /** Calls the visitor with the concrete message type. */
    fun accept(visitor: Visitor)

    /** A text message from a user. */
    class TextMessage(val message: IMessage) : IChatMessage {
        override val body: String get() = message.message
        override val receivedTime: Long get() = message.receivedTime
        override fun accept(visitor: Visitor) = visitor.visit(this)
    }

    /** An informational message about the server or client state. */
    class InfoMessage(val type: Type, override val body: String) : IChatMessage {
        override val receivedTime: Long = System.currentTimeMillis()
        override fun accept(visitor: Visitor) = visitor.visit(this)

        enum class Type { INFO, WARNING, ERROR }
    }

    interface Visitor {
        fun visit(message: TextMessage)
        fun visit(message: InfoMessage)
    }
}
