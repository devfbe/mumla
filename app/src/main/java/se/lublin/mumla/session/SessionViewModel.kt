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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.service.toggleSelfDeafen
import se.lublin.mumla.service.toggleSelfMute

/** The own user of a synchronized session, as the talk button and the mute menu show them. */
data class SelfState(
    val isSelfMuted: Boolean,
    val isSelfDeafened: Boolean,
    /** Muted or suppressed by the server, or muted by ourselves: nothing we say gets through. */
    val cannotTalk: Boolean,
    val isTalking: Boolean,
)

/**
 * The session as the channel screens act on it: our own state, the whisper target, and the
 * talking, muting and whispering they do. Main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(private val sessions: SessionManager) : ViewModel() {

    /** Our own user while the session is synchronized; null otherwise. */
    val self: StateFlow<SelfState?> = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            combine(session.state, session.model, session.talkStates) { state, model, talkStates ->
                val user = model?.self?.takeIf { state == SessionState.Connected } ?: return@combine null
                SelfState(
                    isSelfMuted = user.isSelfMuted,
                    isSelfDeafened = user.isSelfDeafened,
                    cannotTalk = user.isMuted || user.isSuppressed || user.isSelfMuted,
                    isTalking = (talkStates[user.session] ?: TalkState.PASSIVE) != TalkState.PASSIVE,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The name of the whisper target while whispering in a synchronized session; null otherwise. */
    val whisperTarget: StateFlow<String?> = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            val targetChanges = session.events.filterIsInstance<HumlaEvent.VoiceTargetChanged>()
                .map { }.onStart { emit(Unit) }
            combine(session.state, targetChanges) { state, _ ->
                session.actions.whisperTarget?.name.takeIf {
                    state == SessionState.Connected && session.actions.voiceTargetMode == VoiceTargetMode.WHISPER
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val connected: IHumlaSession? get() = sessions.connected

    val isTalking: Boolean get() = connected?.audio?.isTalking == true

    fun setTalking(talking: Boolean) {
        connected?.audio?.setTalking(talking)
    }

    fun toggleMute() {
        connected?.let(::toggleSelfMute)
    }

    fun toggleDeafen() {
        connected?.let(::toggleSelfDeafen)
    }

    fun stopWhispering() {
        connected?.actions?.stopWhispering()
    }
}
