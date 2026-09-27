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
import android.database.SQLException
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.util.ApplicationScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CertificateGeneration"
private const val DATE_FORMAT = "yyyy-MM-dd-HH-mm-ss"

/**
 * Generates a client certificate, stores it and makes it the default, behind a progress dialog.
 * The work runs in the application scope, so it completes even if the calling screen goes away.
 * Main thread only.
 * @return the certificate, or null (after telling the user) if that failed.
 */
suspend fun Context.generateDefaultCertificate(): DatabaseCertificate? {
    val app = applicationContext
    val work = ApplicationScope.of(app).async { createDefaultCertificate(app) }
    val progress = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.generateCertProgress)
        .setView(R.layout.dialog_progress)
        .setCancelable(false)
        .show()
    try {
        val certificate = work.await()
        if (certificate == null) Toast.makeText(this, R.string.generateCertFailure, Toast.LENGTH_SHORT).show()
        return certificate
    } finally {
        progress.dismiss()
    }
}

/** The work of [generateDefaultCertificate]: null if it failed. */
internal suspend fun createDefaultCertificate(
    context: Context,
    repository: MumlaRepository = MumlaRepository.get(context),
    work: CoroutineDispatcher = Dispatchers.Default,
): DatabaseCertificate? {
    val name = context.getString(
        R.string.certificate_export_format,
        SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date()),
    )
    val certificate = try {
        val pkcs12 = withContext(work) {
            ByteArrayOutputStream().also { HumlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        }
        repository.io { addCertificate(name, pkcs12) }
    } catch (e: GeneralSecurityException) {
        HumlaLog.w(TAG, "Could not generate a certificate", e)
        null
    } catch (e: IOException) {
        HumlaLog.w(TAG, "Could not generate a certificate", e)
        null
    } catch (e: SQLException) {
        HumlaLog.w(TAG, "Could not store the certificate", e)
        null
    }
    certificate?.let { Settings.getInstance(context).defaultCertificateId = it.id }
    return certificate
}
