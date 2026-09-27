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

package se.lublin.mumla.channel

import se.lublin.humla.model.Bytes
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.ServerState
import se.lublin.mumla.util.UserStatus

/** One row of the channel list, as immutable data; [id] is stable across rebuilds. */
sealed interface ChannelRow {
    val id: Long

    /** How far the row is indented. */
    val depth: Int

    data class Channel(
        val channel: Int,
        val name: String?,
        override val depth: Int,
        /** The users in this channel and every channel below it; null while the count is hidden. */
        val userCount: Int?,
        val expanded: Boolean,
        /** Whether there is anything to expand: subchannels, users or listeners. */
        val expandable: Boolean,
        val isOwn: Boolean,
        /** Linked with our channel, or our channel with links of its own. */
        val isLinked: Boolean,
        val lock: Lock,
    ) : ChannelRow {
        override val id: Long get() = CHANNEL_ID_MASK or channel.toLong()
    }

    data class User(
        val session: Int,
        val name: String?,
        override val depth: Int,
        val isSelf: Boolean,
        val status: UserStatus,
        val avatar: Bytes?,
    ) : ChannelRow {
        override val id: Long get() = USER_ID_MASK or session.toLong()
    }

    /** [session] listens to [channel] from elsewhere; only the own listener can be stopped here. */
    data class Listener(
        val channel: Int,
        val session: Int,
        val name: String?,
        override val depth: Int,
        val isOwn: Boolean,
    ) : ChannelRow {
        override val id: Long get() = listenerId(channel, session)
    }

    /** Whether the channel's ACL restricts entering, and whether we may enter anyway. */
    enum class Lock { NONE, OPEN, CLOSED }

    companion object {
        // Set bits that keep the ids of channels, users and listeners apart.
        const val CHANNEL_ID_MASK = 0x1L shl 32
        const val USER_ID_MASK = 0x1L shl 33
        private const val LISTENER_ID_MASK = 0x1L shl 62
        private const val ID_BITS = 31
        private const val ID_MASK = (1L shl ID_BITS) - 1

        /** A listener row's id: channel ids and sessions below 2^31 never collide. */
        fun listenerId(channel: Int, session: Int): Long =
            LISTENER_ID_MASK or ((channel.toLong() and ID_MASK) shl ID_BITS) or (session.toLong() and ID_MASK)
    }
}

/**
 * Flattens the trees below [roots] of [model] into rows: each channel, then its users, its
 * listeners and its subchannels. A channel is expanded as [expanded] says, else while anybody is
 * in or listens to its subtree; a collapsed subtree is still walked, since its users are counted.
 * One pass, O(channels + users); the depth is bounded by the model.
 */
fun channelRows(
    model: ServerState,
    roots: List<Int>,
    expanded: Map<Int, Boolean>,
    showUserCount: Boolean,
): List<ChannelRow> {
    val self = model.self
    val ownChannel = self?.channel
    val rows = ArrayList<ChannelRow>(model.channels.size + model.users.size)

    /** Appends [channel]'s rows and returns its subtree's user count, and its listener count shifted up. */
    fun visit(channel: ChannelState, depth: Int): Long {
        val index = rows.size
        rows.add(ChannelRow.Channel(channel.id, null, depth, null, true, false, false, false, ChannelRow.Lock.NONE))
        val users = model.usersIn(channel.id)
        users.mapTo(rows) {
            ChannelRow.User(it.session, it.name, depth + 1, it.session == self?.session, UserStatus.of(it), it.texture)
        }
        val listeners = model.listenersOf(channel.id)
        listeners.mapTo(rows) { ChannelRow.Listener(channel.id, it.session, it.name, depth + 1, it == self) }
        var userCount = users.size
        var listenerCount = listeners.size
        val subchannels = model.subchannels(channel.id)
        for (subchannel in subchannels) {
            val counts = visit(subchannel, depth + 1)
            userCount += counts.toInt()
            listenerCount += (counts ushr Int.SIZE_BITS).toInt()
        }
        val isExpanded = expanded[channel.id] ?: (userCount != 0 || listenerCount != 0)
        if (!isExpanded) rows.subList(index + 1, rows.size).clear()
        val isOwn = channel.id == ownChannel
        rows[index] = ChannelRow.Channel(
            channel = channel.id,
            name = channel.name,
            depth = depth,
            userCount = userCount.takeIf { showUserCount },
            expanded = isExpanded,
            expandable = subchannels.isNotEmpty() || userCount > 0 || listenerCount > 0,
            isOwn = isOwn,
            isLinked = ownChannel != null && (ownChannel in channel.links || (isOwn && channel.links.isNotEmpty())),
            lock = lockOf(channel),
        )
        return (listenerCount.toLong() shl Int.SIZE_BITS) or userCount.toLong()
    }

    for (root in roots) model.channel(root)?.let { visit(it, 0) }
    return rows
}

private fun lockOf(channel: ChannelState): ChannelRow.Lock = when {
    !channel.isEnterRestricted && channel.canEnter -> ChannelRow.Lock.NONE
    channel.canEnter -> ChannelRow.Lock.OPEN
    else -> ChannelRow.Lock.CLOSED
}
