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

package se.lublin.mumla.service

import android.app.Application
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.channel.FakeUser
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubDisconnected
import se.lublin.mumla.testing.stubEvents

@RunWith(RobolectricTestRunner::class)
class MuteTileServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val self = FakeUser(1)
    private val session = mockk<IHumlaSession>(relaxed = true) { every { sessionUser } returns self }
    private lateinit var events: MutableSharedFlow<HumlaEvent>

    /** The tile service, listening, with [session] as the current session if [withSession]. */
    private fun listeningTile(withSession: Boolean = true): MuteTileService {
        events = session.stubEvents()
        if (withSession) installSession(session)
        val tile = Robolectric.buildService(MuteTileService::class.java).create().get()
        tile.onStartListening()
        idleMainLooper()
        return tile
    }

    @Test
    fun withoutASessionTheTileIsUnavailable() {
        val tile = listeningTile(withSession = false)

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_UNAVAILABLE)
        assertThat(tile.qsTile.subtitle).isEqualTo(app.getString(R.string.drawer_not_connected))
    }

    @Test
    fun theTileIsActiveWhileWeAreMutedAndFollowsOurState() {
        session.stubConnected()
        val tile = listeningTile()
        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_INACTIVE)

        self.selfMuted = true
        events.tryEmit(HumlaEvent.UserStateUpdated(self))
        idleMainLooper()

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_ACTIVE)
        assertThat(tile.qsTile.subtitle).isEqualTo(app.getString(R.string.a11y_state_muted))
    }

    @Test
    fun aDisconnectMakesTheTileUnavailable() {
        session.stubConnected()
        val tile = listeningTile()

        session.stubState(SessionState.Disconnected())
        idleMainLooper()

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun aNewSessionIsFollowedToo() {
        session.stubConnected()
        val tile = listeningTile()
        val next = mockk<IHumlaSession>(relaxed = true).also { it.stubState(SessionState.Connecting) }

        installSession(next)
        idleMainLooper()

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun aClickTogglesOurMute() {
        session.stubConnected()
        val tile = listeningTile()

        tile.onClick()

        verify { session.setSelfMuteDeafState(true, false) }
    }

    @Test
    fun aClickWhileDisconnectedDoesNothing() {
        session.stubDisconnected()
        val tile = listeningTile()

        tile.onClick()

        verify(exactly = 0) { session.setSelfMuteDeafState(any(), any()) }
    }

    @Test
    fun theModelShowsUnavailableActiveAndInactive() {
        assertThat(MuteTileModel.of(null))
            .isEqualTo(MuteTileModel(Tile.STATE_UNAVAILABLE, R.string.drawer_not_connected))
        assertThat(MuteTileModel.of(true)).isEqualTo(MuteTileModel(Tile.STATE_ACTIVE, R.string.a11y_state_muted))
        assertThat(MuteTileModel.of(false)).isEqualTo(MuteTileModel(Tile.STATE_INACTIVE, null))
    }

    @Test
    fun unmutingUndeafensAndMutingKeepsDeafness() {
        self.selfMuted = true
        self.selfDeafened = true
        toggleSelfMute(session)
        verify { session.setSelfMuteDeafState(false, false) }

        self.selfMuted = false
        self.selfDeafened = false
        toggleSelfMute(session)
        verify { session.setSelfMuteDeafState(true, false) }
    }
}
