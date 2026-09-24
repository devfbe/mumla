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
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase

/** Back asks before leaving a connected server, and only then: predictive back works otherwise. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityBackTest {

    private val state = MutableStateFlow<SessionState>(SessionState.Disconnected())
    private val service: IMumlaService = mockk(relaxed = true) {
        every { sessionState } returns state
        every { isConnected } answers { state.value == SessionState.Connected }
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private lateinit var activity: MumlaActivity

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
        activity = Robolectric.buildActivity(MumlaActivity::class.java).setup().get()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
        ViewModelProvider(activity)[ServiceViewModel::class.java].attach(service)
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
    fun unbindingStopsTheInterception() {
        state.value = SessionState.Connected
        idleMainLooper()

        ViewModelProvider(activity)[ServiceViewModel::class.java].attach(null)
        idleMainLooper()

        assertThat(intercepted).isFalse()
    }
}
