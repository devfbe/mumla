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

package se.lublin.humla.protocol

import com.google.protobuf.MessageLite
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.MumbleVersion

/**
 * The client's requests to the server, built as protocol messages and handed to [send], which may
 * drop them silently once the connection is gone. Any thread.
 */
@Suppress("TooManyFunctions") // One function per request the protocol offers.
class ServerCommands(private val send: (MessageLite, HumlaTCPMessageType) -> Unit) {

    /** The first two messages of every connection. Opus is the only codec this client offers. */
    fun handshake(release: String, osVersion: String, username: String?, password: String?, tokens: List<String>) {
        send(MumbleVersion.clientVersion(release, "Android", osVersion), HumlaTCPMessageType.Version)
        val auth = Mumble.Authenticate.newBuilder().setOpus(true).addAllTokens(tokens)
        username?.let(auth::setUsername)
        password?.let(auth::setPassword)
        send(auth.build(), HumlaTCPMessageType.Authenticate)
    }

    /** Replaces the access tokens; the server re-evaluates channel access with them. */
    fun sendAccessTokens(tokens: Collection<String>) =
        send(Mumble.Authenticate.newBuilder().addAllTokens(tokens).build(), HumlaTCPMessageType.Authenticate)

    fun moveUser(session: Int, channel: Int) =
        userState(Mumble.UserState.newBuilder().setSession(session).setChannelId(channel))

    fun setListening(session: Int, channel: Int, listen: Boolean) {
        val state = Mumble.UserState.newBuilder().setSession(session)
        if (listen) state.addListeningChannelAdd(channel) else state.addListeningChannelRemove(channel)
        userState(state)
    }

    fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean) = send(
        Mumble.ChannelState.newBuilder()
            .setParent(parent)
            .setName(name)
            .setDescription(description)
            .setPosition(position)
            .setTemporary(temporary)
            .build(),
        HumlaTCPMessageType.ChannelState,
    )

    fun removeChannel(channel: Int) =
        send(Mumble.ChannelRemove.newBuilder().setChannelId(channel).build(), HumlaTCPMessageType.ChannelRemove)

    fun linkChannels(channel: Int, other: Int) =
        channelState(Mumble.ChannelState.newBuilder().setChannelId(channel).addLinksAdd(other))

    fun unlinkChannels(channel: Int, others: Iterable<Int>) =
        channelState(Mumble.ChannelState.newBuilder().setChannelId(channel).addAllLinksRemove(others))

    fun requestPermissions(channel: Int) =
        send(Mumble.PermissionQuery.newBuilder().setChannelId(channel).build(), HumlaTCPMessageType.PermissionQuery)

    fun requestComment(session: Int) = requestBlob(Mumble.RequestBlob.newBuilder().addSessionComment(session))

    fun requestAvatar(session: Int) = requestBlob(Mumble.RequestBlob.newBuilder().addSessionTexture(session))

    fun requestChannelDescription(channel: Int) =
        requestBlob(Mumble.RequestBlob.newBuilder().addChannelDescription(channel))

    fun requestUserStats(session: Int) = send(
        Mumble.UserStats.newBuilder().setSession(session).setStatsOnly(false).build(),
        HumlaTCPMessageType.UserStats,
    )

    /** User id 0 asks the server to register the user under their current name and certificate. */
    fun registerUser(session: Int) = userState(Mumble.UserState.newBuilder().setSession(session).setUserId(0))

    fun kickBanUser(session: Int, reason: String?, ban: Boolean) = send(
        Mumble.UserRemove.newBuilder().setSession(session).setReason(reason).setBan(ban).build(),
        HumlaTCPMessageType.UserRemove,
    )

    fun setComment(session: Int, comment: String?) =
        userState(Mumble.UserState.newBuilder().setSession(session).setComment(comment))

    fun setPrioritySpeaker(session: Int, priority: Boolean) =
        userState(Mumble.UserState.newBuilder().setSession(session).setPrioritySpeaker(priority))

    /** An admin's mute or deafen; unmuting lifts the channel's suppression too. */
    fun setMuteDeaf(session: Int, mute: Boolean, deaf: Boolean) {
        val state = Mumble.UserState.newBuilder().setSession(session).setMute(mute).setDeaf(deaf)
        if (!mute) state.setSuppress(false)
        userState(state)
    }

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) =
        userState(Mumble.UserState.newBuilder().setSelfMute(mute).setSelfDeaf(deaf))

    fun textToUser(session: Int, message: String) =
        textMessage(Mumble.TextMessage.newBuilder().addSession(session).setMessage(message))

    /** With [tree], the message reaches [channel] and every channel below it. */
    fun textToChannel(channel: Int, message: String, tree: Boolean) {
        val text = Mumble.TextMessage.newBuilder().setMessage(message)
        if (tree) text.addTreeId(channel) else text.addChannelId(channel)
        textMessage(text)
    }

    fun registerVoiceTarget(id: Int, target: Mumble.VoiceTarget.Target) = send(
        Mumble.VoiceTarget.newBuilder().setId(id).addTargets(target).build(),
        HumlaTCPMessageType.VoiceTarget,
    )

    private fun userState(builder: Mumble.UserState.Builder) = send(builder.build(), HumlaTCPMessageType.UserState)

    private fun channelState(builder: Mumble.ChannelState.Builder) =
        send(builder.build(), HumlaTCPMessageType.ChannelState)

    private fun requestBlob(builder: Mumble.RequestBlob.Builder) =
        send(builder.build(), HumlaTCPMessageType.RequestBlob)

    private fun textMessage(builder: Mumble.TextMessage.Builder) =
        send(builder.build(), HumlaTCPMessageType.TextMessage)
}
