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

package se.lublin.humla.protocol

import com.google.protobuf.MessageLite
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.UserStats
import se.lublin.humla.net.TcpMessageHandler
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent

/**
 * The model's single writer: reduces the frames about channels, users and permissions into
 * [ServerState] snapshots, published through [publisher] once per burst (after the frames already
 * queued on the protocol thread when the first of them arrived), and hands what happens to
 * [events]: chat log notices, text messages, refusals and user statistics. Every avatar the server
 * announces only by its hash is asked for through [requestAvatar] at once, so the snapshots fill in
 * with the pictures the channel list shows.
 *
 * Confined to the "humla-protocol" thread: [onMessage] and [onLocal] run there.
 */
class ModelHandler(
    initial: ServerState,
    private val events: (HumlaEvent) -> Unit,
    private val publisher: Publisher,
    private val requestAvatar: (Int) -> Unit = {},
) : TcpMessageHandler {

    /** Where the snapshots go. */
    interface Publisher {
        /** Runs [block] on the protocol thread, after what is queued there already. */
        fun post(block: () -> Unit)

        /** Called on the protocol thread. */
        fun publish(state: ServerState)
    }

    private val writer = ServerWriter(initial)
    private var publishScheduled = false

    override fun onMessage(msg: MessageLite) {
        when (msg) {
            is Mumble.ChannelState, is Mumble.ChannelRemove, is Mumble.PermissionQuery,
            is Mumble.ServerSync, is Mumble.ServerConfig,
            -> reduce(msg)
            is Mumble.UserRemove -> {
                val removesUs = msg.session == writer.selfSession
                reduce(msg)
                // The connection ends over it before a scheduled publication could run.
                if (removesUs) publisher.publish(writer.snapshot())
            }
            is Mumble.UserState -> {
                reduce(msg)
                if (msg.hasTextureHash() && writer.user(msg.session)?.texture == null) requestAvatar(msg.session)
            }
            is Mumble.PermissionDenied -> events(UserNotices.denial(msg))
            is Mumble.TextMessage -> textMessage(msg)
            is Mumble.UserStats -> events(HumlaEvent.UserStatsReceived(UserStats.from(msg)))
        }
    }

    /** Applies a local mute, ignore or volume. */
    internal fun onLocal(input: LocalInput) {
        writer.onLocal(input)
        schedulePublish()
    }

    private fun reduce(msg: MessageLite) {
        writer.onMessage(msg, events)
        schedulePublish()
    }

    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        publisher.post {
            publishScheduled = false
            publisher.publish(writer.snapshot())
        }
    }

    /** A message from an ignored sender is dropped; targets the model does not know are left out. */
    private fun textMessage(msg: Mumble.TextMessage) {
        val sender = writer.user(msg.actor)
        if (sender != null && sender.isLocalIgnored) return
        val message = Message(
            msg.actor,
            sender?.name,
            msg.channelIdList.mapNotNull(writer::channel),
            msg.treeIdList.mapNotNull(writer::channel),
            msg.sessionList.mapNotNull(writer::user),
            msg.message,
        )
        events(HumlaEvent.TextMessage(message))
    }
}
