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

import android.util.Log
import com.google.protobuf.MessageLite
import se.lublin.humla.model.Channel
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles network messages related to the user-channel tree model: channels, users, messages and
 * permissions. What happens is published through [events], chat log lines as typed
 * [HumlaEvent.Notice]s for the client to phrase.
 *
 * Threading: [onMessage] runs on the "humla-protocol" thread, the only writer. Getters are called
 * from the main thread and binder threads. Compound read-check-write accesses are safe only
 * because of that single writer; the concurrent maps and volatile fields just keep readers from
 * seeing half-rehashed tables or half-built objects.
 */
class ModelHandler(
    private val events: (HumlaEvent) -> Unit,
    private val localMuteHistory: List<Int>?,
    private val localIgnoreHistory: List<Int>?,
) : TcpMessageHandler {

    private val channels = ChannelTree()

    // ConcurrentHashMap: getUser() is called from the main thread while the protocol thread
    // writes; a HashMap read during a rehash can miss a present key.
    private val users = ConcurrentHashMap<Int, User>()

    @Volatile
    var serverSettings: ServerSettings? = null
        private set

    /** The server-wide permissions: those of the root channel. */
    @Volatile
    var permissions: Int = 0
        private set

    @Volatile
    private var session: Int = 0

    fun getChannel(id: Int): Channel? = channels[id]

    fun getUser(session: Int): User? = users[session]

    override fun onMessage(msg: MessageLite) {
        when (msg) {
            is Mumble.ChannelState -> channels.apply(msg)?.let(events)
            is Mumble.ChannelRemove -> channels.remove(msg.channelId)?.let { events(HumlaEvent.ChannelRemoved(it)) }
            is Mumble.PermissionQuery -> permissionQuery(msg)
            is Mumble.UserState -> userState(msg)
            is Mumble.UserRemove -> userRemove(msg)
            is Mumble.PermissionDenied -> events(UserNotices.denial(msg))
            is Mumble.TextMessage -> textMessage(msg)
            is Mumble.ServerSync -> {
                session = msg.session
                events(HumlaEvent.LogMessage(HumlaEvent.Level.INFO, msg.welcomeText))
            }
            is Mumble.ServerConfig -> serverSettings = ServerSettings(msg)
            else -> Unit
        }
    }

    private fun permissionQuery(msg: Mumble.PermissionQuery) {
        if (msg.flush) {
            for (channel in channels.all) channel.permissions = 0
        }

        val channel = channels[msg.channelId] ?: return
        channel.permissions = msg.permissions
        // The root channel's permissions are the server-wide ones.
        if (msg.channelId == ROOT_CHANNEL_ID) permissions = channel.permissions
        events(HumlaEvent.ChannelPermissionsUpdated(channel))
    }

    private fun userState(msg: Mumble.UserState) {
        val self = users[session]
        val known = users[msg.session]
        val user = known ?: newUser(msg) ?: return
        val actor = msg.takeIf { it.hasActor() }?.let { users[it.actor] }

        UserNotices.applyIdentity(user, msg, localMuteHistory, localIgnoreHistory)
        if (known == null) events(HumlaEvent.UserJoinedServer(user.name))

        if (UserNotices.applySelfMute(user, msg)) self?.let { UserNotices.selfMute(user, it) }?.let(events)

        if (msg.hasRecording()) {
            user.isRecording = msg.recording
            self?.let { UserNotices.recording(user, it) }?.let(events)
        }

        UserNotices.applyServerFlags(user, msg)

        // A frame naming an unknown channel is dropped from here on.
        if (msg.hasChannelId() && !moveUser(user, msg.channelId, known == null, actor, self)) return

        UserNotices.applyProfile(user, msg)
        events(if (known == null) HumlaEvent.UserConnected(user) else HumlaEvent.UserStateUpdated(user))
    }

    /** The user a frame introduces, in the root channel; null for a frame without a name. */
    private fun newUser(msg: Mumble.UserState): User? {
        if (!msg.hasName()) return null
        // Joining into the root carries no channel ID, so a new user starts there.
        return User(msg.session, msg.name).also {
            users[msg.session] = it
            it.channel = channels.getOrStub(ROOT_CHANNEL_ID)
        }
    }

    /** Moves [user] into [channelId]; false if that channel is unknown. */
    private fun moveUser(user: User, channelId: Int, isNew: Boolean, actor: User?, self: User?): Boolean {
        val channel = channels[channelId]
        if (channel == null) {
            Log.e(TAG, "Invalid channel for user!")
            return false
        }
        val old = user.channel
        user.channel = channel
        if (!isNew) events(HumlaEvent.UserJoinedChannel(user, channel, old))
        if (self != null && old != null && self != user) UserNotices.move(user, actor, old, channel, self)?.let(events)
        return true
    }

    private fun userRemove(msg: Mumble.UserRemove) {
        val user = users[msg.session]
        val actor = users[msg.actor]
        val reason = msg.reason

        events(
            when {
                msg.session == session -> HumlaEvent.SelfKicked(actor?.name, reason, msg.ban)
                actor != null -> HumlaEvent.UserKicked(user?.name, actor.name, reason, msg.ban)
                else -> HumlaEvent.UserLeftServer(user?.name)
            }
        )

        user?.channel = null
        events(HumlaEvent.UserRemoved(user, reason))
    }

    private fun textMessage(msg: Mumble.TextMessage) {
        val sender = users[msg.actor]
        if (sender != null && sender.isLocalIgnored) return

        val message = Message(
            msg.actor,
            sender?.name,
            msg.channelIdList.mapNotNull { channels[it] },
            msg.treeIdList.mapNotNull { channels[it] },
            msg.sessionList.mapNotNull { users[it] },
            msg.message,
        )
        events(HumlaEvent.TextMessage(message))
    }

    companion object {
        private val TAG: String = ModelHandler::class.java.name

        /**
         * How far below the root a channel may be placed. The server controls the tree depth and
         * the UI walks it recursively on the main thread, so an unbounded chain would end in a
         * `StackOverflowError`. 256 is far above Mumble's default nesting limit (10) and far below
         * the depth that overflows the stack.
         */
        const val MAX_CHANNEL_DEPTH = 256

        /** The id Mumble gives the root channel. */
        const val ROOT_CHANNEL_ID = 0
    }
}
