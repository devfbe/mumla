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

package se.lublin.mumla.log

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.preference.Preference
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.preference.AboutSettingsFragment
import se.lublin.mumla.testing.FileProviderCache
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.drainMainUntil

/** "Share log" on the About screen hands the redacted buffer to the share sheet as a file. */
@RunWith(RobolectricTestRunner::class)
class ShareLogTest {
    @Before
    fun setUp() {
        FileProviderCache.clear()
    }

    @Test
    fun theSharedFileHoldsTheRecentLogWithoutSecrets() {
        HumlaLog.i("ShareLogTest", "connecting with password=hunter2 to example.org")
        val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val fragment = AboutSettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()

        requireNotNull(fragment.findPreference<Preference>("shareLog")).performClick()
        var chooser: Intent? = null
        drainMainUntil(description = "the share sheet") {
            chooser = shadowOf(activity).nextStartedActivity
            chooser != null
        }

        assertThat(chooser!!.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = requireNotNull(IntentCompat.getParcelableExtra(chooser!!, Intent.EXTRA_INTENT, Intent::class.java))
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        val uri = requireNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
        val text = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertThat(text).startsWith("Mumla ")
        assertThat(text).contains("I/ShareLogTest")
        assertThat(text).contains("password=<redacted> to example.org")
        assertThat(text).doesNotContain("hunter2")
    }
}
