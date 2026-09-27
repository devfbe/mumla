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

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.util.collectEvents

/** Receives the current session of a [SessionManager]; every call on the main thread. */
interface SessionClient {
    /** A session became current; also called on [bindClient] if one already is. */
    fun onSessionAttached(session: IHumlaSession) = Unit

    /** The session passed to [onSessionAttached] was replaced, or the client stopped listening. */
    fun onSessionDetached() = Unit

    /** The attached session's state, the current one first. */
    fun onSessionState(state: SessionState) = Unit

    fun onSessionEvent(event: HumlaEvent) = Unit
}

/**
 * Feeds [client] the current session, its state and its events until [owner] is destroyed. Events
 * emitted during [SessionClient.onSessionAttached] are not missed.
 */
fun SessionManager.bindClient(owner: LifecycleOwner, client: SessionClient): Job {
    var current: IHumlaSession? = null
    var following: Job? = null
    fun detach() {
        if (current == null) return
        following?.cancel()
        following = null
        current = null
        client.onSessionDetached()
    }
    return owner.lifecycleScope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
        try {
            session.collect { session ->
                if (session === current) return@collect
                detach()
                if (session != null) {
                    current = session
                    following = launch(start = CoroutineStart.UNDISPATCHED) {
                        collectEvents(this, session, client::onSessionEvent)
                        client.onSessionAttached(session)
                        session.state.collect(client::onSessionState)
                    }
                }
            }
        } finally {
            detach()
        }
    }
}
