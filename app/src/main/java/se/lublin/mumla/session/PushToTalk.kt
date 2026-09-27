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
import se.lublin.mumla.Settings

/**
 * The two halves of a talk key press, for every surface that has one: a hardware key, the talk
 * button, the hot corner. They only act in push-to-talk mode while connected.
 */
class PushToTalk(private val settings: Settings, private val sessions: SessionManager) {
    constructor(context: Context) : this(Settings.getInstance(context), SessionManager.get(context))

    /** A no-op in toggle mode, which acts on the release. */
    fun onKeyDown() {
        val session = sessions.connected ?: return
        if (isPushToTalk && !settings.isPushToTalkToggle) session.setTalkingState(true)
    }

    /** Toggles talking in toggle mode, otherwise stops talking. */
    fun onKeyUp() {
        val session = sessions.connected ?: return
        if (!isPushToTalk) return
        session.setTalkingState(settings.isPushToTalkToggle && !session.isTalking)
    }

    private val isPushToTalk: Boolean get() = settings.inputMethod == Settings.ARRAY_INPUT_METHOD_PTT
}
