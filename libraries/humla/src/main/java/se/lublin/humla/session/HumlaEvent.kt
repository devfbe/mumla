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
package se.lublin.humla.session

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate

/**
 * Something that happened in a session, as published by `IHumlaService.getEvents()`.
 *
 * Model events carry the live model object, which may have changed again by the time the event is
 * collected: treat them as "re-read this", never as a delta.
 */
sealed interface HumlaEvent {

    /** A connection attempt started. */
    data object Connecting : HumlaEvent

    /** The session is synchronized with the server. */
    data object Connected : HumlaEvent

    /** The connection ended; [error] is null for a requested disconnect. */
    data class Disconnected(val error: HumlaException?) : HumlaEvent

    /** The server's certificate chain is not trusted. */
    class TlsHandshakeFailed(val chain: List<X509Certificate>) : HumlaEvent

    /**
     * The server presented a certificate that differs from the one pinned for its host, and the
     * system does not trust it either. Possibly an attack; never accept it silently.
     */
    class TlsCertificateChanged(val chain: List<X509Certificate>) : HumlaEvent

    data class ChannelAdded(val channel: IChannel) : HumlaEvent

    data class ChannelStateUpdated(val channel: IChannel) : HumlaEvent

    data class ChannelRemoved(val channel: IChannel) : HumlaEvent

    data class ChannelPermissionsUpdated(val channel: IChannel) : HumlaEvent

    data class UserConnected(val user: IUser) : HumlaEvent

    data class UserStateUpdated(val user: IUser) : HumlaEvent

    data class UserTalkStateUpdated(val user: IUser) : HumlaEvent

    data class UserJoinedChannel(val user: IUser, val newChannel: IChannel, val oldChannel: IChannel?) : HumlaEvent

    /** [user] is null when the server removed a session the model never knew. */
    data class UserRemoved(val user: IUser?, val reason: String?) : HumlaEvent

    /** The server refused an operation; [reason] is ready to show. */
    data class PermissionDenied(val reason: String) : HumlaEvent

    /** A text message from the server or another user. */
    data class TextMessage(val message: IMessage) : HumlaEvent

    data class VoiceTargetChanged(val mode: VoiceTargetMode) : HumlaEvent

    /** A line for the chat log. */
    sealed interface Notice : HumlaEvent {
        val level: Level
    }

    enum class Level { INFO, WARNING, ERROR }

    /** A notice whose text is already final: server text or a message of the library's own. */
    data class LogMessage(override val level: Level, val text: String) : Notice
}
