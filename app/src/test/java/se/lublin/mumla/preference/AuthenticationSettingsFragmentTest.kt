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

package se.lublin.mumla.preference

import android.content.ComponentName
import android.content.Context
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.testing.openScreen

/**
 * The certificate rows open our own screens by class: another installed Mumla variant declares
 * the same actions, and an implicit intent would open its screens and change its certificates.
 */
@RunWith(RobolectricTestRunner::class)
class AuthenticationSettingsFragmentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every certificate row opens our own activity explicitly`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val screen = activity.openScreen(AuthenticationSettingsFragment::class.java)

        val expected = mapOf(
            "certificateGenerate" to CertificateGenerateActivity::class.java,
            "certificateSelect" to CertificateSelectActivity::class.java,
            "certificateImport" to CertificateImportActivity::class.java,
            "certificateExport" to CertificateExportActivity::class.java,
            "clearTrust" to ServerCertificateClearActivity::class.java,
        )
        for ((key, target) in expected) {
            val preference = requireNotNull(screen.findPreference<Preference>(key)) { "no preference $key" }
            val intent = requireNotNull(preference.intent) { "$key has no intent" }
            assertWithMessage(key).that(intent.component).isEqualTo(ComponentName(context, target))
        }
    }
}
