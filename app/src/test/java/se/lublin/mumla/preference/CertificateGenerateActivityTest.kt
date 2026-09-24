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

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaSQLiteDatabase
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.installDatabase

@RunWith(RobolectricTestRunner::class)
class CertificateGenerateActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(MumlaSQLiteDatabase.DATABASE_NAME)
    }

    @Test
    fun aGeneratedCertificateIsStoredMadeTheDefaultAndNamed() {
        val database = installDatabase()

        Robolectric.buildActivity(CertificateGenerateActivity::class.java).setup()
        drainMainUntil { database.certificates.isNotEmpty() && !latestMessage().isNullOrEmpty() }

        val stored = database.certificates.single()
        assertThat(latestMessage()).isEqualTo(context.getString(R.string.generateCertSuccess, stored.name))
        assertThat(Settings.getInstance(context).getDefaultCertificate()).isEqualTo(stored.id)
        assertThat(database.getCertificateData(stored.id)).isNotEmpty()
    }

    private fun latestMessage(): String? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.findViewById<TextView>(android.R.id.message)?.text?.toString()
}
