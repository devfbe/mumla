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
package se.lublin.mumla.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.model.UserState
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.launchMumlaActivity
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubState

/** On the channel screen the app bar names the server, and below it where we are and our own mute state. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityAppBarTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val state = session.stubState(SessionState.Connected)
    private val model = session.stubModel(me(channel = 1, muted = false))

    private fun me(channel: Int, muted: Boolean) = serverState(self = 7) {
        channel(0, "Root")
        channel(1, "Lobby")
        channel(2, "Games")
        user(UserState(7, "me", channel, isSelfMuted = muted, isSelfDeafened = muted))
    }

    private fun launch(): MumlaActivity {
        val intent = Intent(app, MumlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, DrawerAdapter.ITEM_SERVER)
        val activity = launchMumlaActivity(intent)
        installSession(session)
        idleMainLooper()
        return activity
    }

    @Test
    fun theTitleIsTheServerAndTheSubtitleOurChannel() {
        val appBar = launch().supportActionBar!!

        assertThat(appBar.title.toString()).isEqualTo("Home")
        assertThat(appBar.subtitle.toString()).isEqualTo("Lobby")
    }

    @Test
    fun theSubtitleFollowsAMoveAndOurMuteState() {
        val appBar = launch().supportActionBar!!

        model.value = me(channel = 2, muted = true)
        idleMainLooper()

        assertThat(appBar.subtitle.toString())
            .isEqualTo("Games · " + app.getString(R.string.self_status_muted_deafened))
    }

    @Test
    fun leavingTheChannelScreenDropsTheSubtitle() {
        val appBar = launch().supportActionBar!!

        state.value = SessionState.Disconnected()
        idleMainLooper()

        assertThat(appBar.subtitle).isNull()
        assertThat(appBar.title.toString()).isEqualTo(app.getString(R.string.drawer_favorites))
    }
}
