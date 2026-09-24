/*
 * Copyright (C) 2026 The Mumla authors
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

import android.os.Looper
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceFragmentCompat
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.mumla.R
import se.lublin.mumla.preference.SettingsActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase

/** The activities draw behind the system bars and keep their controls clear of them. */
@RunWith(RobolectricTestRunner::class)
class EdgeToEdgeTest {
    private val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION))
        .build()

    private fun View.dispatch() {
        ViewCompat.dispatchApplyWindowInsets(this, insets)
    }

    @Test
    fun theMainScreenPadsTheAppBarTheContentAndTheDrawer() {
        installDatabase(mockk(relaxed = true))
        val activity = Robolectric.buildActivity(MumlaActivity::class.java).setup().get()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide

        activity.findViewById<View>(R.id.drawer_layout).dispatch()

        assertThat(activity.findViewById<View>(R.id.app_bar).paddingTop).isEqualTo(STATUS)
        assertThat(activity.findViewById<View>(R.id.content_frame).paddingBottom).isEqualTo(NAVIGATION)
        val drawer = activity.findViewById<View>(R.id.left_drawer)
        assertThat(drawer.paddingTop).isEqualTo(STATUS)
        assertThat(drawer.paddingBottom).isEqualTo(NAVIGATION)
    }

    @Test
    fun theSettingsListEndsAboveTheNavigationBar() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val list = (activity.supportFragmentManager.findFragmentById(R.id.settings_container)
            as PreferenceFragmentCompat).listView

        activity.window.decorView.dispatch()

        assertThat(activity.findViewById<View>(R.id.app_bar).paddingTop).isEqualTo(STATUS)
        assertThat(list.paddingBottom).isEqualTo(NAVIGATION)
        assertThat(list.clipToPadding).isFalse()
    }

    private companion object {
        const val STATUS = 63
        const val NAVIGATION = 126
    }
}
