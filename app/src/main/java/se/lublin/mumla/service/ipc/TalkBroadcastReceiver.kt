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
package se.lublin.mumla.service.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import se.lublin.mumla.session.SessionManager

/**
 * Lets other apps (Tasker and the like) start, stop or toggle transmission through
 * [BROADCAST_TALK], while [allowed] says so and a session is connected.
 */
class TalkBroadcastReceiver(
    private val sessions: SessionManager,
    private val allowed: () -> Boolean,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BROADCAST_TALK) throw UnsupportedOperationException()
        if (!allowed()) return
        val audio = sessions.connected?.audio ?: return
        when (intent.getStringExtra(EXTRA_TALK_STATUS) ?: TALK_STATUS_TOGGLE) {
            TALK_STATUS_ON -> audio.setTalking(true)
            TALK_STATUS_OFF -> audio.setTalking(false)
            TALK_STATUS_TOGGLE -> audio.setTalking(!audio.isTalking)
        }
    }

    companion object {
        const val BROADCAST_TALK = "se.lublin.mumla.action.TALK"
        const val EXTRA_TALK_STATUS = "status"
        const val TALK_STATUS_ON = "on"
        const val TALK_STATUS_OFF = "off"
        const val TALK_STATUS_TOGGLE = "toggle"
    }
}
