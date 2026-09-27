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

import se.lublin.humla.model.Message
import se.lublin.humla.model.UserStats
import se.lublin.humla.util.VoiceTargetMode

/**
 * Something that happened in a session, as published by `IHumlaSession.events`. Whether the session
 * is connected is its `state`, and what the server holds is its `model`; events carry values only.
 */
sealed interface HumlaEvent {

    /** The server refused an operation. [reason] is the server's own text, if it sent one. */
    data class PermissionDenied(val type: DenyType, val reason: String?) : HumlaEvent

    /** Why the server refused an operation, for the refusals the client explains itself. */
    enum class DenyType {
        CHANNEL_NAME,
        TEXT_TOO_LONG,
        TEMPORARY_CHANNEL,
        MISSING_CERTIFICATE,
        USER_NAME,
        CHANNEL_FULL,
        NESTING_LIMIT,
        CHANNEL_COUNT_LIMIT,

        /** The channel has as many listeners as the server allows. */
        CHANNEL_LISTENER_LIMIT,

        /** The local user listens to as many channels as the server allows. */
        USER_LISTENER_LIMIT,

        /** Any other refusal; the server's reason, if any, says why. */
        OTHER,
    }

    /** The server's answer to `SessionActions.requestUserStats`. */
    data class UserStatsReceived(val stats: UserStats) : HumlaEvent

    /** A text message from the server or another user. */
    data class TextMessage(val message: Message) : HumlaEvent

    /** The local user sent [message]. */
    data class MessageSent(val message: Message) : HumlaEvent

    data class VoiceTargetChanged(val mode: VoiceTargetMode) : HumlaEvent

    /** A line for the chat log. */
    sealed interface Notice : HumlaEvent {
        val level: Level
    }

    enum class Level { INFO, WARNING, ERROR }

    /** A notice whose text is already final: server text or a message of the library's own. */
    data class LogMessage(override val level: Level, val text: String) : Notice

    data class UserJoinedServer(val user: String?) : Notice {
        override val level get() = Level.INFO
    }

    /** [user] left the server on their own, or was removed without a known actor. */
    data class UserLeftServer(val user: String?) : Notice {
        override val level get() = Level.INFO
    }

    /** [actor] kicked (or with [ban], banned) [user] from the server. */
    data class UserKicked(val user: String?, val actor: String?, val reason: String, val ban: Boolean) : Notice {
        override val level get() = Level.WARNING
    }

    /** [actor] kicked (or with [ban], banned) the local user from the server. */
    data class SelfKicked(val actor: String?, val reason: String, val ban: Boolean) : Notice {
        override val level get() = Level.WARNING
    }

    /** The local user's own mute and deafen state changed. */
    data class SelfMuteChanged(val muted: Boolean, val deafened: Boolean) : Notice {
        override val level get() = Level.INFO
    }

    /** [user], in the local user's channel, changed their own mute and deafen state. */
    data class UserMuteChanged(val user: String?, val muted: Boolean, val deafened: Boolean) : Notice {
        override val level get() = Level.INFO
    }

    /** The local user started or stopped recording. */
    data class SelfRecordingChanged(val recording: Boolean) : Notice {
        override val level get() = Level.INFO
    }

    /** [user], in the local user's channel, started or stopped recording. */
    data class UserRecordingChanged(val user: String?, val recording: Boolean) : Notice {
        override val level get() = Level.INFO
    }

    /**
     * [user] left the local user's channel for [channel]. [byThemselves] when they moved on their
     * own; otherwise [actor] moved them, or the server when [actor] is null.
     */
    data class UserLeftChannel(
        val user: String?,
        val channel: String?,
        val actor: String?,
        val byThemselves: Boolean,
    ) : Notice {
        override val level get() = Level.INFO
    }

    /**
     * [user] entered the local user's channel from [from]. [byThemselves] when they moved on their
     * own; otherwise [actor] moved them, or the server when [actor] is null.
     */
    data class UserEnteredChannel(
        val user: String?,
        val from: String?,
        val actor: String?,
        val byThemselves: Boolean,
    ) : Notice {
        override val level get() = Level.INFO
    }
}
