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
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.launchMumlaActivity
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.ui.ConnectRequests
import se.lublin.mumla.ui.ServerRequest

/** Prompts of the main screen can each be left without doing anything. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityPromptsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun latestDialog(): AlertDialog {
        idleMainLooper()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun theUsernamePromptForAPublicServerCanBeCancelled() {
        val activity = launchMumlaActivity()
        val server = PublicServer("Public", null, "Sweden", null, "public.example", 64738, null, null)

        ViewModelProvider(activity)[ConnectRequests::class.java].request(ServerRequest.Public(server))
        val prompt = latestDialog()

        assertThat(prompt.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
            .isEqualTo(app.getString(android.R.string.cancel))
        prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleMainLooper()
        assertThat(prompt.isShowing).isFalse()
    }

    @Test
    fun aPermissionDenialIsAcknowledgedWithOk() {
        val session = mockk<IHumlaSession>(relaxed = true) {
            every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
        }
        session.stubState(SessionState.Connected)
        val events = session.stubEvents()
        val intent = Intent(app, MumlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, DrawerAdapter.ITEM_SERVER)
        launchMumlaActivity(intent)
        installSession(session)
        idleMainLooper()

        events.tryEmit(HumlaEvent.PermissionDenied(HumlaEvent.DenyType.OTHER, "no"))
        val denial = latestDialog()

        assertThat(denial.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            .isEqualTo(app.getString(android.R.string.ok))
        assertThat(denial.isShowing).isTrue()
    }
}
