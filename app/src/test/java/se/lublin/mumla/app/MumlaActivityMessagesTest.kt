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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.ui.AppMessages
import se.lublin.mumla.testing.launchMumlaActivity
import se.lublin.mumla.testing.snackbarText

/** Messages from where there is no screen are shown by the activity that is started. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityMessagesTest {
    private val app = ApplicationProvider.getApplicationContext<MumlaApplication>()

    @Test
    fun aMessagePostedWhileTheActivityIsStartedIsShownAsASnackbar() {
        val activity = launchMumlaActivity()

        AppMessages.get(app).post("Trusted certificates cleared")

        assertThat(activity.snackbarText()).isEqualTo("Trusted certificates cleared")
    }

    /** E.g. from a screen that finished right away: the message waits for the next started activity. */
    @Test
    fun aMessagePostedWithoutAScreenWaitsForTheNextOne() {
        AppMessages.get(app).post("Certificate imported")

        val activity = launchMumlaActivity()

        assertThat(activity.snackbarText()).isEqualTo("Certificate imported")
    }
}
