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
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.session.SessionState
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase

/**
 * The service stays bound, and its notifications suppressed, while the activity is visible: a
 * paused activity (e.g. behind a permission prompt or in multi-window) still shows the chat.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityLifecycleTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val service: IMumlaService = mockk(relaxed = true) {
        every { sessionState } returns MutableStateFlow(SessionState.Disconnected())
    }

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
    }

    @Test
    fun theServiceIsBoundFromStartToStop() {
        val controller = Robolectric.buildActivity(MumlaActivity::class.java).create().start()
        assertThat(shadowOf(app).boundServiceConnections).hasSize(1)

        controller.resume().pause()
        assertThat(shadowOf(app).unboundServiceConnections).isEmpty()

        controller.stop()
        assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
    }

    @Test
    fun notificationsStaySuppressedWhilePausedAndResumeOnStop() {
        val controller = Robolectric.buildActivity(MumlaActivity::class.java).setup()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
        ViewModelProvider(controller.get())[ServiceViewModel::class.java].attach(service)
        idleMainLooper()
        verify { service.setSuppressNotifications(true) }

        controller.pause()
        idleMainLooper()
        verify(exactly = 0) { service.setSuppressNotifications(false) }

        controller.stop()
        idleMainLooper()
        verify { service.setSuppressNotifications(false) }
    }
}
