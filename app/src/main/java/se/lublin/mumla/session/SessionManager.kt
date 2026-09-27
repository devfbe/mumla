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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.mumla.chat.SessionChat

/**
 * The app's server session. Every connect creates a new one; an ended session stays current, so
 * its reason can still be shown, until the next connect replaces and closes it. Screens, the
 * foreground service and the notifications all read the session here. Main thread.
 *
 * [newSession] builds a session for a configuration; [startForeground] starts the foreground
 * service, which a connect does while the user is looking at the app, as Android 12 requires.
 * Automatic reconnects happen inside one session, so the running service carries them.
 */
class SessionManager(
    private val newSession: (SessionConfig) -> IHumlaSession,
    private val startForeground: () -> Unit,
    /** The chat history of the current session. */
    val chat: SessionChat,
) {
    /** Implemented by the Application, which owns the process's session manager. */
    interface Owner {
        val sessionManager: SessionManager
    }

    private val current = MutableStateFlow<IHumlaSession?>(null)
    private val mutableErrorShown = MutableStateFlow(false)

    /** The current session, or null before the first connect. */
    val session: StateFlow<IHumlaSession?> = current.asStateFlow()

    /** The current session's state; Disconnected without one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<SessionState> = current.flatMapLatest { it?.state ?: flowOf(SessionState.Disconnected()) }

    val currentState: SessionState get() = current.value?.state?.value ?: SessionState.Disconnected()

    /** The current session while it is synchronized with its server. */
    val connected: IHumlaSession? get() = current.value?.takeIf { it.isConnected }

    /** Whether the session holds, or is re-establishing, a connection. */
    val isActive: Boolean get() = currentState !is SessionState.Disconnected

    /** Whether the user has seen why the current session ended; reset by every connect. */
    val errorShown: StateFlow<Boolean> = mutableErrorShown.asStateFlow()

    private val mutableAppVisible = MutableStateFlow(false)

    /**
     * Whether a screen of the app is visible. It shows the connection itself, so connection
     * notifications stay away, and it shows the chat, so chat notifications go.
     */
    val appVisible: StateFlow<Boolean> = mutableAppVisible.asStateFlow()

    fun setAppVisible(visible: Boolean) {
        mutableAppVisible.value = visible
    }

    /** Connects to the server of [config] in a new session, replacing the current one. */
    fun connect(config: SessionConfig) {
        val session = newSession(config)
        adopt(session)
        session.connect()
        if (session.state.value !is SessionState.Disconnected) startForeground()
    }

    /** Connects again with the last session's configuration. */
    fun reconnect() {
        current.value?.config?.let(::connect)
    }

    /**
     * Makes [session] the current one and closes the previous. Its chat is followed before
     * anything can happen in it, and observers move to it before the previous one ends.
     */
    internal fun adopt(session: IHumlaSession) {
        val previous = current.value
        mutableErrorShown.value = false
        chat.follow(session)
        current.value = session
        previous?.close()
    }

    fun disconnect() {
        current.value?.disconnect()
    }

    /** Gives up the automatic reconnect. The user asked for it, so the reason counts as shown. */
    fun cancelReconnect() {
        mutableErrorShown.value = true
        current.value?.cancelReconnect()
    }

    fun markErrorShown() {
        mutableErrorShown.value = true
    }

    companion object {
        fun get(context: Context): SessionManager = (context.applicationContext as Owner).sessionManager
    }
}

/** Whether the session is synchronized with its server; derived from its only state. */
val IHumlaSession.isConnected: Boolean get() = state.value == SessionState.Connected
