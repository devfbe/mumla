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

package se.lublin.humla.net

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import se.lublin.humla.protobuf.Mumble

/**
 * TCP message types of the Mumble protocol. The ordinal is the wire id, so entries are never
 * reordered. [parser] is null for types the server never sends.
 */
internal enum class HumlaTCPMessageType(private val parser: Parser<out MessageLite>?) {
    Version(Mumble.Version.parser()),
    UDPTunnel(Mumble.UDPTunnel.parser()),
    Authenticate(Mumble.Authenticate.parser()),
    Ping(Mumble.Ping.parser()),
    Reject(Mumble.Reject.parser()),
    ServerSync(Mumble.ServerSync.parser()),
    ChannelRemove(Mumble.ChannelRemove.parser()),
    ChannelState(Mumble.ChannelState.parser()),
    UserRemove(Mumble.UserRemove.parser()),
    UserState(Mumble.UserState.parser()),
    BanList(Mumble.BanList.parser()),
    TextMessage(Mumble.TextMessage.parser()),
    PermissionDenied(Mumble.PermissionDenied.parser()),
    ACL(Mumble.ACL.parser()),
    QueryUsers(Mumble.QueryUsers.parser()),
    CryptSetup(Mumble.CryptSetup.parser()),
    ContextActionModify(Mumble.ContextActionModify.parser()),
    ContextAction(Mumble.ContextAction.parser()),
    UserList(Mumble.UserList.parser()),
    VoiceTarget(null),
    PermissionQuery(Mumble.PermissionQuery.parser()),
    CodecVersion(Mumble.CodecVersion.parser()),
    UserStats(Mumble.UserStats.parser()),
    RequestBlob(Mumble.RequestBlob.parser()),
    ServerConfig(Mumble.ServerConfig.parser()),
    SuggestConfig(Mumble.SuggestConfig.parser()),
    PluginDataTransmission(Mumble.PluginDataTransmission.parser());

    /** Whether traffic of this type is logged; the frequent voice and ping frames are not. */
    val isLogged: Boolean get() = this != UDPTunnel && this != Ping

    fun parse(data: ByteArray): MessageLite =
        parser?.parseFrom(data) ?: throw InvalidProtocolBufferException("$name is never sent by a server")
}
