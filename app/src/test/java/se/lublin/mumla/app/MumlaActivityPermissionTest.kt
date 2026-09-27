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

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.ui.ConnectRequests
import se.lublin.mumla.ui.ServerRequest

/** How MumlaActivity gets the permissions a connection needs. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityPermissionTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = Settings.getInstance(app)
    private val server = Server(1, "Home", "example.org", 64738, "me", null)

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
    }

    private fun launch(): MumlaActivity {
        val activity = Robolectric.buildActivity(MumlaActivity::class.java).setup().get()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
        return activity
    }

    private fun MumlaActivity.requestConnect() {
        ViewModelProvider(this)[ConnectRequests::class.java].request(ServerRequest.Favourite(server))
        idleMainLooper()
    }

    private val MumlaActivity.requested get() = shadowOf(this).lastRequestedPermission

    private fun MumlaActivity.requestedPermissions() = requested.requestedPermissions.toList()

    private fun MumlaActivity.answer(granted: Boolean) {
        val request = requested
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(1) { result })
        idleMainLooper()
    }

    private fun connectElsewhere() {
        installSession(mockk<IHumlaSession>(relaxed = true).stubConnected())
        idleMainLooper()
    }

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun rationale(permission: String, show: Boolean) {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(permission, show)
    }

    @Test
    fun theMicrophoneIsRequestedFirst() {
        val activity = launch()

        activity.requestConnect()

        assertThat(activity.requestedPermissions()).containsExactly(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun theReasonIsExplainedBeforeAskingAgain() {
        settings.isMicrophonePermissionAsked = true
        rationale(Manifest.permission.RECORD_AUDIO, true)
        val activity = launch()

        activity.requestConnect()
        assertThat(activity.requested).isNull()
        val dialog = latestDialog()
        assertThat(dialog.message()).isEqualTo(app.getString(R.string.microphone_permission_rationale))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()
        assertThat(activity.requestedPermissions()).containsExactly(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun aPermanentlyDeniedMicrophoneLeadsToTheAppSettings() {
        settings.isMicrophonePermissionAsked = true
        val activity = launch()

        activity.requestConnect()
        val dialog = latestDialog()
        assertThat(dialog.message()).isEqualTo(app.getString(R.string.microphone_permission_settings))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        val started: Intent = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(started.data.toString()).isEqualTo("package:${app.packageName}")
    }

    @Test
    fun theMicrophoneRequestIsRemembered() {
        val activity = launch()
        activity.requestConnect()

        activity.answer(granted = false)

        assertThat(settings.isMicrophonePermissionAsked).isTrue()
    }

    @Test
    fun notificationsAreAskedForOnceAcrossLaunches() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = launch()
        activity.requestConnect()
        assertThat(activity.requestedPermissions()).containsExactly(Manifest.permission.POST_NOTIFICATIONS)

        // Connected elsewhere, so the flow ends in a confirmation instead of a connection.
        connectElsewhere()
        activity.answer(granted = false)
        assertThat(settings.isNotificationPermissionAsked).isTrue()
        assertThat(latestDialog().message()).isEqualTo(app.getString(R.string.reconnect_dialog_message))

        val relaunched = launch()
        connectElsewhere()
        relaunched.requestConnect()
        assertThat(relaunched.requested).isNull()
        assertThat(latestDialog().message()).isEqualTo(app.getString(R.string.reconnect_dialog_message))
    }
}
