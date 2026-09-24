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
package se.lublin.mumla.chat

import android.content.Context
import se.lublin.humla.model.IMessage
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R

/**
 * Phrases session notices for the chat log (HTML, names highlighted) and permission refusals for a
 * dialog (plain text).
 */
class NoticeFormatter(private val context: Context) {

    fun format(notice: HumlaEvent.Notice): String = when (notice) {
        is HumlaEvent.LogMessage -> notice.text
        is HumlaEvent.UserJoinedServer -> string(R.string.chat_notify_connected, highlight(notice.user))
        is HumlaEvent.UserLeftServer ->
            string(R.string.chat_notify_disconnected, highlight(notice.user ?: UNKNOWN))
        is HumlaEvent.UserKicked -> kicked(notice)
        is HumlaEvent.SelfKicked -> kicked(notice)
        is HumlaEvent.SelfMuteChanged -> string(muteState(notice.muted, notice.deafened, self = true))
        is HumlaEvent.UserMuteChanged ->
            string(muteState(notice.muted, notice.deafened, self = false), highlight(notice.user))
        is HumlaEvent.SelfRecordingChanged -> string(recording(notice.recording, self = true))
        is HumlaEvent.UserRecordingChanged -> string(recording(notice.recording, self = false), highlight(notice.user))
        is HumlaEvent.UserLeftChannel -> leftChannel(notice)
        is HumlaEvent.UserEnteredChannel -> enteredChannel(notice)
    }

    private fun kicked(notice: HumlaEvent.UserKicked): String = string(
        if (notice.ban) R.string.chat_notify_kick_ban else R.string.chat_notify_kick,
        highlight(notice.actor ?: UNKNOWN), notice.reason, highlight(notice.user ?: UNKNOWN),
    )

    private fun kicked(notice: HumlaEvent.SelfKicked): String = string(
        if (notice.ban) R.string.chat_notify_kick_ban_self else R.string.chat_notify_kick_self,
        highlight(notice.actor ?: UNKNOWN), notice.reason,
    )

    private fun leftChannel(notice: HumlaEvent.UserLeftChannel): String =
        if (notice.byThemselves) {
            string(R.string.chat_notify_user_left_channel, highlight(notice.user), highlight(notice.channel))
        } else {
            string(
                R.string.chat_notify_user_left_channel_by,
                highlight(notice.user), highlight(notice.channel), mover(notice.actor),
            )
        }

    private fun enteredChannel(notice: HumlaEvent.UserEnteredChannel): String =
        if (notice.byThemselves) {
            string(R.string.chat_notify_user_joined_channel, highlight(notice.user))
        } else {
            string(
                R.string.chat_notify_user_joined_channel_by,
                highlight(notice.user), highlight(notice.from), mover(notice.actor),
            )
        }

    /** What to tell the user about a refused operation. */
    fun denial(event: HumlaEvent.PermissionDenied): String = when (event.type) {
        HumlaEvent.DenyType.CHANNEL_NAME -> string(R.string.deny_reason_channel_name)
        HumlaEvent.DenyType.TEXT_TOO_LONG -> string(R.string.deny_reason_text_too_long)
        HumlaEvent.DenyType.TEMPORARY_CHANNEL -> string(R.string.deny_reason_no_operation_temp)
        HumlaEvent.DenyType.MISSING_CERTIFICATE -> string(R.string.deny_reason_no_certificate)
        HumlaEvent.DenyType.USER_NAME -> string(R.string.deny_reason_invalid_username)
        HumlaEvent.DenyType.CHANNEL_FULL -> string(R.string.deny_reason_channel_full)
        HumlaEvent.DenyType.NESTING_LIMIT -> string(R.string.deny_reason_channel_nesting)
        HumlaEvent.DenyType.CHANNEL_COUNT_LIMIT -> string(R.string.deny_reason_channel_count)
        HumlaEvent.DenyType.CHANNEL_LISTENER_LIMIT -> string(R.string.deny_reason_channel_listener_limit)
        HumlaEvent.DenyType.USER_LISTENER_LIMIT -> string(R.string.deny_reason_user_listener_limit)
        HumlaEvent.DenyType.OTHER ->
            event.reason?.let { string(R.string.deny_reason_other, it) } ?: string(R.string.perm_denied)
    }

    /** Who sent [message]; the server when it names no sender. */
    fun senderName(message: IMessage): String = message.actorName ?: string(R.string.server)

    /** Whoever moved a user: [actor], or the server when null. */
    private fun mover(actor: String?): String = actor?.let(::highlight) ?: string(R.string.the_server)

    private fun string(id: Int, vararg args: Any?): String = context.getString(id, *args)

    companion object {
        /** The color names are highlighted in, as RRGGBB. */
        const val HIGHLIGHT_COLOR = "33b5e5"

        /** A name the model does not know. Not translated: it stands in for a name. */
        private const val UNKNOWN = "unknown"

        /** [text] in HTML font tags of [HIGHLIGHT_COLOR]. */
        fun highlight(text: String?): String = "<font color=\"#$HIGHLIGHT_COLOR\">$text</font>"
    }
}

/** Deafened without muted does not happen; it reads as unmuted. */
private fun muteState(muted: Boolean, deafened: Boolean, self: Boolean): Int = when {
    muted && deafened -> if (self) R.string.chat_notify_muted_deafened else R.string.chat_notify_now_muted_deafened
    muted -> if (self) R.string.chat_notify_muted else R.string.chat_notify_now_muted
    else -> if (self) R.string.chat_notify_unmuted else R.string.chat_notify_now_unmuted
}

private fun recording(recording: Boolean, self: Boolean): Int = when {
    self && recording -> R.string.chat_notify_self_recording_started
    self -> R.string.chat_notify_self_recording_stopped
    recording -> R.string.chat_notify_user_recording_started
    else -> R.string.chat_notify_user_recording_stopped
}
