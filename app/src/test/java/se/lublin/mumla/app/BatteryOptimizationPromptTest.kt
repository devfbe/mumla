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

import android.app.Application
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
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
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubDisconnected
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.ui.ServiceViewModel

/** MumlaActivity offers the battery optimisation exemption once, after a connection succeeds. */
@RunWith(RobolectricTestRunner::class)
class BatteryOptimizationPromptTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = Settings.getInstance(app)
    private lateinit var activity: MumlaActivity

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
    }

    private fun launch() {
        activity = Robolectric.buildActivity(MumlaActivity::class.java).setup().get()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
    }

    private fun attach(service: IMumlaService?) {
        ViewModelProvider(activity)[ServiceViewModel::class.java].attach(service)
        idleMainLooper()
    }

    private fun connectedService() = mockk<IMumlaService>(relaxed = true).stubConnected(mockk(relaxed = true))

    private fun exempt(value: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, value)
    }

    private fun promptShown(): Boolean {
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog ?: return false
        return dialog.isShowing &&
            dialog.findViewById<TextView>(android.R.id.message)?.text.toString() ==
            app.getString(R.string.battery_optimization_prompt_message)
    }

    private fun prompt() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun itIsOfferedOnceConnectedWhileOptimized() {
        launch()

        attach(connectedService())

        assertThat(promptShown()).isTrue()
    }

    @Test
    fun itIsOfferedWhenTheSessionBecomesSynchronized() {
        launch()
        val service = mockk<IMumlaService>(relaxed = true).stubDisconnected()
        val events = service.stubEvents()
        attach(service)
        assertThat(promptShown()).isFalse()

        service.stubConnected(mockk(relaxed = true))
        events.tryEmit(HumlaEvent.Connected)
        idleMainLooper()

        assertThat(promptShown()).isTrue()
    }

    @Test
    fun itIsNotOfferedWhileDisconnected() {
        launch()

        attach(mockk<IMumlaService>(relaxed = true).stubDisconnected())

        assertThat(promptShown()).isFalse()
    }

    @Test
    fun itIsNotOfferedWhenAlreadyExempt() {
        exempt(true)
        launch()

        attach(connectedService())

        assertThat(promptShown()).isFalse()
    }

    @Test
    fun itIsNotOfferedWhenAskedBefore() {
        settings.isBatteryOptimizationAsked = true
        launch()

        attach(connectedService())

        assertThat(promptShown()).isFalse()
    }

    @Test
    fun acceptingRequestsTheExemptionForMumla() {
        launch()
        attach(connectedService())

        prompt().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(started.data.toString()).isEqualTo("package:${app.packageName}")
        assertThat(settings.isBatteryOptimizationAsked).isTrue()
    }

    @Test
    fun decliningIsRememberedAndNothingIsStarted() {
        launch()
        attach(connectedService())

        prompt().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleMainLooper()

        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(settings.isBatteryOptimizationAsked).isTrue()
        attach(null)
        attach(connectedService())
        assertThat(promptShown()).isFalse()
    }

    @Test
    fun anUnansweredPromptIsOfferedAgainOnReturn() {
        launch()
        attach(connectedService())
        prompt().dismiss() // e.g. the activity was paused
        idleMainLooper()

        attach(null)
        attach(connectedService())

        assertThat(promptShown()).isTrue()
        assertThat(settings.isBatteryOptimizationAsked).isFalse()
    }
}
