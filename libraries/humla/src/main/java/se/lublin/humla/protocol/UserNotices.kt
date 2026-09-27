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

import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.UserState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent

/** The chat log notices of the model's changes, and of the server's refusals. */
internal object UserNotices {

    /** Our own mute change, or that of a user in our channel; null for anyone else. */
    fun selfMute(user: UserState, self: UserState): HumlaEvent.Notice? = when {
        user.session == self.session -> HumlaEvent.SelfMuteChanged(user.isSelfMuted, user.isSelfDeafened)
        user.channel == self.channel -> HumlaEvent.UserMuteChanged(user.name, user.isSelfMuted, user.isSelfDeafened)
        else -> null
    }

    /**
     * Our own recording change, or that of a user in our channel or one linked to it, directly or
     * through other links, as desktop Mumble reports them; null for anyone else.
     */
    fun recording(user: UserState, self: UserState, channel: (Int) -> ChannelState?): HumlaEvent.Notice? = when {
        user.session == self.session -> HumlaEvent.SelfRecordingChanged(user.isRecording)
        self.channel in linkedTree(user.channel, channel) ->
            HumlaEvent.UserRecordingChanged(user.name, user.isRecording)
        else -> null
    }

    /** [start] and every channel linked to it, directly or through others. */
    private fun linkedTree(start: Int, channel: (Int) -> ChannelState?): Set<Int> {
        val seen = mutableSetOf(start)
        val pending = ArrayDeque(listOf(start))
        while (pending.isNotEmpty()) {
            for (link in channel(pending.removeFirst())?.links.orEmpty()) if (seen.add(link)) pending.add(link)
        }
        return seen
    }

    /** Somebody else moved from [old] to [new]: a notice if either is our channel. */
    fun move(
        user: UserState,
        actor: UserState?,
        old: ChannelState?,
        new: ChannelState,
        self: UserState,
    ): HumlaEvent.Notice? {
        val byThemselves = actor != null && actor.session == user.session
        return when {
            self.channel != new.id && self.channel == old?.id ->
                HumlaEvent.UserLeftChannel(user.name, new.name, actor?.name, byThemselves)
            self.channel == new.id -> HumlaEvent.UserEnteredChannel(user.name, old?.name, actor?.name, byThemselves)
            else -> null
        }
    }

    fun denial(msg: Mumble.PermissionDenied): HumlaEvent.PermissionDenied =
        HumlaEvent.PermissionDenied(denyType(msg.type), if (msg.hasReason()) msg.reason else null)

    private fun denyType(type: Mumble.PermissionDenied.DenyType): HumlaEvent.DenyType = when (type) {
        Mumble.PermissionDenied.DenyType.ChannelName -> HumlaEvent.DenyType.CHANNEL_NAME
        Mumble.PermissionDenied.DenyType.TextTooLong -> HumlaEvent.DenyType.TEXT_TOO_LONG
        Mumble.PermissionDenied.DenyType.TemporaryChannel -> HumlaEvent.DenyType.TEMPORARY_CHANNEL
        Mumble.PermissionDenied.DenyType.MissingCertificate -> HumlaEvent.DenyType.MISSING_CERTIFICATE
        Mumble.PermissionDenied.DenyType.UserName -> HumlaEvent.DenyType.USER_NAME
        Mumble.PermissionDenied.DenyType.ChannelFull -> HumlaEvent.DenyType.CHANNEL_FULL
        Mumble.PermissionDenied.DenyType.NestingLimit -> HumlaEvent.DenyType.NESTING_LIMIT
        Mumble.PermissionDenied.DenyType.ChannelCountLimit -> HumlaEvent.DenyType.CHANNEL_COUNT_LIMIT
        Mumble.PermissionDenied.DenyType.ChannelListenerLimit -> HumlaEvent.DenyType.CHANNEL_LISTENER_LIMIT
        Mumble.PermissionDenied.DenyType.UserListenerLimit -> HumlaEvent.DenyType.USER_LISTENER_LIMIT
        else -> HumlaEvent.DenyType.OTHER
    }
}
