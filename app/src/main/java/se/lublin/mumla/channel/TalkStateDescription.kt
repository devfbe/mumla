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

package se.lublin.mumla.channel

import android.content.Context
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R

/**
 * What a user row's talk-state icon shows, for accessibility services, by the same priority as
 * the channel list's icon; null for a user who is just silent.
 */
fun talkStateDescription(context: Context, user: IUser): String? {
    val id = when {
        user.isSelfDeafened -> R.string.a11y_state_deafened
        user.isDeafened -> R.string.a11y_state_server_deafened
        user.isSelfMuted -> R.string.a11y_state_muted
        user.isMuted -> R.string.a11y_state_server_muted
        user.isSuppressed -> R.string.a11y_state_suppressed
        user.talkState != TalkState.PASSIVE -> R.string.a11y_state_talking
        else -> return null
    }
    return context.getString(id)
}
