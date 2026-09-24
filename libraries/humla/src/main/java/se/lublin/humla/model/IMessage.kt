/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.model

/** A text message from the server or another user. */
interface IMessage {
    /** The sender's session. Prefer [actorName]: the sender may have left the server. */
    val actor: Int

    /** The sender's name, or null for a message from the server itself (or a nameless sender). */
    val actorName: String?

    val targetChannels: List<Channel>
    val targetTrees: List<Channel>
    val targetUsers: List<User>
    val message: String

    /** When the message arrived, in milliseconds since the epoch. */
    val receivedTime: Long
}
