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

import se.lublin.humla.model.Channel
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent

/** How [ModelHandler] applies a UserState to a user, and which chat log notices that makes. */
internal object UserNotices {

    /** The user id and hash, restoring a local mute or ignore remembered for the user id. */
    fun applyIdentity(user: User, msg: Mumble.UserState, muteHistory: List<Int>?, ignoreHistory: List<Int>?) {
        if (msg.hasUserId()) {
            user.setUserId(msg.userId)
            if (muteHistory?.contains(user.getUserId()) == true) user.setLocalMuted(true)
            if (ignoreHistory?.contains(user.getUserId()) == true) user.setLocalIgnored(true)
        }
        if (msg.hasHash()) user.setHash(msg.hash)
    }

    /** The user's own mute and deafen flags; false if the frame carries neither. */
    fun applySelfMute(user: User, msg: Mumble.UserState): Boolean {
        if (msg.hasSelfMute()) user.setSelfMuted(msg.selfMute)
        if (msg.hasSelfDeaf()) user.setSelfDeafened(msg.selfDeaf)
        return msg.hasSelfMute() || msg.hasSelfDeaf()
    }

    /** The mute, deafen, suppress and priority speaker flags the server sets. */
    fun applyServerFlags(user: User, msg: Mumble.UserState) {
        if (msg.hasDeaf()) user.setDeafened(msg.deaf)
        if (msg.hasMute()) user.setMuted(msg.mute)
        if (msg.hasSuppress()) user.setSuppressed(msg.suppress)
        if (msg.hasPrioritySpeaker()) user.setPrioritySpeaker(msg.prioritySpeaker)
    }

    /** Name, avatar and comment; a new hash drops the cached blob, a new blob its hash. */
    fun applyProfile(user: User, msg: Mumble.UserState) {
        if (msg.hasName()) user.setName(msg.name)
        if (msg.hasTextureHash()) {
            user.setTextureHash(msg.textureHash)
            user.setTexture(null)
        }
        if (msg.hasTexture()) {
            user.setTexture(msg.texture)
            user.setTextureHash(null)
        }
        if (msg.hasCommentHash()) {
            user.setCommentHash(msg.commentHash)
            user.setComment(null)
        }
        if (msg.hasComment()) {
            user.setComment(msg.comment)
            user.setCommentHash(null)
        }
    }

    /** Our own mute change, or that of a user in our channel; null for anyone else. */
    fun selfMute(user: User, self: User): HumlaEvent.Notice? {
        val userChannel = user.getChannel()
        return when {
            user.getSession() == self.getSession() ->
                HumlaEvent.SelfMuteChanged(user.isSelfMuted(), user.isSelfDeafened())
            userChannel != null && userChannel == self.getChannel() ->
                HumlaEvent.UserMuteChanged(user.getName(), user.isSelfMuted(), user.isSelfDeafened())
            else -> null
        }
    }

    /** Our own recording change, or that of a user in our channel; null for anyone else. */
    fun recording(user: User, self: User): HumlaEvent.Notice? {
        if (user.getSession() == self.getSession()) return HumlaEvent.SelfRecordingChanged(user.isRecording())
        val selfChannel = self.getChannel()
        val nearby = selfChannel != null &&
            (selfChannel.getLinks().contains(selfChannel) || selfChannel == user.getChannel())
        return if (nearby) HumlaEvent.UserRecordingChanged(user.getName(), user.isRecording()) else null
    }

    /** Somebody else moved from [old] to [channel]: a notice if either is our channel. */
    fun move(user: User, actor: User?, old: Channel, channel: Channel, self: User): HumlaEvent.Notice? {
        val selfChannel = self.getChannel() ?: return null
        val byThemselves = actor != null && actor.getSession() == user.getSession()
        val actorName = actor?.getName()
        return when {
            selfChannel != channel && selfChannel == old ->
                HumlaEvent.UserLeftChannel(user.getName(), channel.getName(), actorName, byThemselves)
            selfChannel == channel ->
                HumlaEvent.UserEnteredChannel(user.getName(), old.getName(), actorName, byThemselves)
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
        else -> HumlaEvent.DenyType.OTHER
    }
}
