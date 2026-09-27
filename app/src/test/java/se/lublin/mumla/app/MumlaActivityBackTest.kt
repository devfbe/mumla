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

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubState

/** Back asks before leaving a connected server, and only then: predictive back works otherwise. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityBackTest {

    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val state = session.stubState(SessionState.Disconnected())
    private lateinit var activity: MumlaActivity

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
        activity = Robolectric.buildActivity(MumlaActivity::class.java).setup().get()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
        installSession(session)
        idleMainLooper()
    }

    private val intercepted get() = activity.onBackPressedDispatcher.hasEnabledCallbacks()

    @Test
    fun backIsNotInterceptedWhileDisconnected() {
        assertThat(intercepted).isFalse()
    }

    @Test
    fun backIsInterceptedOnlyWhileConnected() {
        state.value = SessionState.Connected
        idleMainLooper()
        assertThat(intercepted).isTrue()

        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(intercepted).isFalse()
    }

    @Test
    fun backWhileConnectedAsksBeforeDisconnecting() {
        state.value = SessionState.Connected
        idleMainLooper()

        activity.onBackPressedDispatcher.onBackPressed()
        idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
            .isEqualTo(activity.getString(R.string.disconnectSure, "Home"))
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun aNewSessionThatIsNotConnectedYetStopsTheInterception() {
        state.value = SessionState.Connected
        idleMainLooper()

        installSession(mockk<IHumlaSession>(relaxed = true).also { it.stubState(SessionState.Connecting) })
        idleMainLooper()

        assertThat(intercepted).isFalse()
    }
}
