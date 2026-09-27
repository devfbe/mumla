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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.installDatabase

/**
 * The service stays bound, and its notifications suppressed, while the activity is visible: a
 * paused activity (e.g. behind a permission prompt or in multi-window) still shows the chat.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityLifecycleTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
    }

    /** Visible from start to stop, so the service's notifications stay away while paused too. */
    @Test
    fun theAppCountsAsVisibleFromStartToStop() {
        val sessions = SessionManager.get(app)
        val controller = Robolectric.buildActivity(MumlaActivity::class.java).create()
        assertThat(sessions.appVisible.value).isFalse()

        controller.start()
        assertThat(sessions.appVisible.value).isTrue()

        controller.resume().pause()
        assertThat(sessions.appVisible.value).isTrue()

        controller.stop()
        assertThat(sessions.appVisible.value).isFalse()
    }
}
