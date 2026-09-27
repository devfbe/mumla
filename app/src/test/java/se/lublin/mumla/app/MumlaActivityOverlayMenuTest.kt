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
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.launchMumlaActivity
import se.lublin.mumla.testing.stubState

/** The overlay moved from the notification into the app's overflow menu. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityOverlayMenuTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val state = session.stubState(SessionState.Connected)

    private fun launch(): MumlaActivity {
        val intent = Intent(app, MumlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, DrawerAdapter.ITEM_SERVER)
        val activity = launchMumlaActivity(intent)
        installSession(session)
        idleMainLooper()
        return activity
    }

    private fun MumlaActivity.selectOverlay() {
        val menu = MenuBuilder(this)
        onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        val item = menu.findItem(R.id.action_overlay)
        assertThat(item.isVisible).isTrue()
        onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, item)
        idleMainLooper()
    }

    private fun startedServiceActions(): List<String?> =
        generateSequence { shadowOf(app).nextStartedService }.map { it.action }.toList()

    @Test
    fun withThePermissionTheServiceTogglesTheOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        val activity = launch()
        startedServiceActions()

        activity.selectOverlay()

        val started = shadowOf(app).nextStartedService
        assertThat(started.component?.className).isEqualTo(MumlaService::class.java.name)
        assertThat(started.action).isNotNull()
    }

    @Test
    fun withoutThePermissionTheUserIsAskedToGrantItFirst() {
        ShadowSettings.setCanDrawOverlays(false)
        val activity = launch()
        startedServiceActions()

        activity.selectOverlay()
        assertThat(startedServiceActions()).isEmpty()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog.isShowing).isTrue()
        assertThat(dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
            .isEqualTo(app.getString(R.string.grant_perm_draw_over_apps))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        assertThat(shadowOf(app).nextStartedActivity.action)
            .isEqualTo(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }

    @Test
    fun theOverlayIsOfferedOnlyWhileConnected() {
        state.value = SessionState.Disconnected()
        val activity = launch()
        val menu = MenuBuilder(activity)
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)

        assertThat(menu.findItem(R.id.action_overlay).isVisible).isFalse()
    }
}
