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

package se.lublin.mumla.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.mockk
import se.lublin.humla.AudioControls
import se.lublin.humla.IHumlaSession
import se.lublin.humla.SessionActions
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.session.SessionManager
import java.util.WeakHashMap

private val stateFlows = WeakHashMap<IHumlaSession, MutableStateFlow<SessionState>>()
private val eventFlows = WeakHashMap<IHumlaSession, MutableSharedFlow<HumlaEvent>>()
private val audioMocks = WeakHashMap<IHumlaSession, AudioControls>()
private val actionMocks = WeakHashMap<IHumlaSession, SessionActions>()

/** The mocked session's audio controls, as a mock of their own so their calls can be counted. */
fun IHumlaSession.stubAudio(): AudioControls =
    audioMocks.getOrPut(this) { mockk<AudioControls>(relaxed = true).also { every { audio } returns it } }

/** The mocked session's actions, as a mock of their own so their calls can be counted. */
fun IHumlaSession.stubActions(): SessionActions =
    actionMocks.getOrPut(this) { mockk<SessionActions>(relaxed = true).also { every { actions } returns it } }

/** The mocked session's state, stubbed as a flow the test moves; [state] now. */
fun IHumlaSession.stubState(state: SessionState): MutableStateFlow<SessionState> =
    stateFlows.getOrPut(this) { MutableStateFlow(state).also { every { this@stubState.state } returns it } }
        .also { it.value = state }

/** The mocked session's events, stubbed as a flow the test emits into; the same flow on every call. */
fun IHumlaSession.stubEvents(): MutableSharedFlow<HumlaEvent> = eventFlows.getOrPut(this) {
    MutableSharedFlow<HumlaEvent>(extraBufferCapacity = 64).also { every { events } returns it }
}

fun <T : IHumlaSession> T.stubConnected(): T = apply {
    stubEvents()
    stubSnapshotsIfAbsent()
    stubState(SessionState.Connected)
}

fun <T : IHumlaSession> T.stubDisconnected(): T = apply {
    stubEvents()
    stubSnapshotsIfAbsent()
    stubState(SessionState.Disconnected())
}

/** Makes [session] the app's current session, as a connect would. */
fun installSession(session: IHumlaSession) {
    session.stubEvents()
    session.stubSnapshotsIfAbsent()
    if (stateFlows[session] == null) session.stubState(SessionState.Connected)
    SessionManager.get(ApplicationProvider.getApplicationContext<Context>()).adopt(session)
}
