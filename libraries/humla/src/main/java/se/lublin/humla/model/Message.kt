/*
 * Copyright (C) 2014 Andrew Comminos
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

import java.util.Collections

/** A text message, immutable once built, so it can be read from any thread. */
class Message(
    override val actor: Int,
    override val actorName: String?,
    channels: List<Channel>,
    trees: List<Channel>,
    users: List<User>,
    override val message: String,
) : IMessage {
    override val targetChannels: List<Channel> = Collections.unmodifiableList(channels)
    override val targetTrees: List<Channel> = Collections.unmodifiableList(trees)
    override val targetUsers: List<User> = Collections.unmodifiableList(users)
    override val receivedTime: Long = System.currentTimeMillis()

    /** A message with no sender and no targets. */
    constructor(message: String) : this(-1, null, emptyList(), emptyList(), emptyList(), message)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return actor == other.actor && receivedTime == other.receivedTime && actorName == other.actorName &&
            targetChannels == other.targetChannels && targetTrees == other.targetTrees &&
            targetUsers == other.targetUsers && message == other.message
    }

    override fun hashCode(): Int =
        listOf(actor, actorName, targetChannels, targetTrees, targetUsers, message, receivedTime).hashCode()
}
