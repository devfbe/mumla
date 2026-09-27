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
package se.lublin.mumla.service

import se.lublin.humla.audio.TransmitMode
import se.lublin.mumla.session.SessionManager

/** [MediaKeyTarget] backed by the connected session of [sessions]. */
class SessionMediaKeyTarget(private val sessions: SessionManager) : MediaKeyTarget {
    override val isConnected: Boolean
        get() = sessions.connected != null

    override val transmitMode: TransmitMode
        get() = checkNotNull(sessions.connected).transmitMode

    override val isTalking: Boolean
        get() = sessions.connected?.isTalking == true

    override fun setTalking(talking: Boolean) {
        sessions.connected?.setTalkingState(talking)
    }

    override fun stopTalking() {
        sessions.connected?.setTalkingState(false)
    }

    override fun toggleSelfMute() {
        sessions.connected?.let(::toggleSelfMute)
    }
}
