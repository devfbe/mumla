/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.mumla.util

import android.content.Context
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.mumla.R

/** The state a user's icon shows ahead of talking, by priority; [NONE] leaves it to the talk state. */
enum class UserStatus {
    SELF_DEAFENED, DEAFENED, SELF_MUTED, MUTED, SUPPRESSED, NONE;

    companion object {
        fun of(user: UserState): UserStatus = when {
            user.isSelfDeafened -> SELF_DEAFENED
            user.isDeafened -> DEAFENED
            user.isSelfMuted -> SELF_MUTED
            user.isMuted -> MUTED
            user.isSuppressed -> SUPPRESSED
            else -> NONE
        }
    }
}

/**
 * What a user's state icon shows, for accessibility services, by the same priority as the channel
 * list's icon; null for a user who is just silent.
 */
fun talkStateDescription(context: Context, status: UserStatus, talkState: TalkState?): String? {
    val id = when (status) {
        UserStatus.SELF_DEAFENED -> R.string.a11y_state_deafened
        UserStatus.DEAFENED -> R.string.a11y_state_server_deafened
        UserStatus.SELF_MUTED -> R.string.a11y_state_muted
        UserStatus.MUTED -> R.string.a11y_state_server_muted
        UserStatus.SUPPRESSED -> R.string.a11y_state_suppressed
        UserStatus.NONE -> if (talkState != null && talkState != TalkState.PASSIVE) {
            R.string.a11y_state_talking
        } else {
            return null
        }
    }
    return context.getString(id)
}
