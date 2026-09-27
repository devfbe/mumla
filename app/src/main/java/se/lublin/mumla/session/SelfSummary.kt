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

package se.lublin.mumla.session

import android.content.Context
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.ServerState
import se.lublin.mumla.R

/** Where we are and whether we muted or deafened ourselves, as the app bar and the notification show it. */
data class SelfSummary(val channel: String?, val isSelfMuted: Boolean, val isSelfDeafened: Boolean) {

    /** "Lobby · Muted", or whichever half is known; null if neither is. */
    fun text(context: Context): String? {
        val status = when {
            isSelfMuted && isSelfDeafened -> context.getString(R.string.self_status_muted_deafened)
            isSelfDeafened -> context.getString(R.string.self_status_deafened)
            isSelfMuted -> context.getString(R.string.self_status_muted)
            else -> null
        }
        val channel = channel?.takeIf { it.isNotEmpty() }
        return when {
            channel != null && status != null -> context.getString(R.string.channel_and_status, channel, status)
            else -> channel ?: status
        }
    }

    companion object {
        /** Our own summary in [model]; null before we are in it. */
        fun of(model: ServerState?): SelfSummary? {
            val self = model?.self ?: return null
            return SelfSummary(model.selfChannel?.name, self.isSelfMuted, self.isSelfDeafened)
        }
    }
}

/** The name the user gave the server, or its host. */
val IHumlaSession.serverName: String?
    get() = targetServer?.let { it.name.ifEmpty { it.host } }
