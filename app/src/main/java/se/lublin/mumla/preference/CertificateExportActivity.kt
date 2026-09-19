/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaSQLiteDatabase

class CertificateExportActivity : AppCompatActivity(), DialogInterface.OnClickListener {

    private lateinit var database: MumlaDatabase
    private lateinit var certificates: List<DatabaseCertificate>

    private val documentCreator: ActivityResultLauncher<String> =
        registerForActivityResult(CreateDocument("application/x-pkcs12"), ::onDocumentCreated)
    private var certificatePending: DatabaseCertificate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = MumlaSQLiteDatabase(this)
        certificates = database.certificates

        val labels = certificates.map { it.name as CharSequence }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_export_certificate_title)
            .setItems(labels, this)
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        database.close()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        val certificate = certificates[which]
        certificatePending = certificate
        documentCreator.launch(certificate.name)
    }

    private fun onDocumentCreated(uri: Uri?) {
        val pending = certificatePending
        if (uri != null && pending != null) {
            try {
                val os = contentResolver.openOutputStream(uri)
                val df = DocumentFile.fromSingleUri(this, uri)
                writeCertificate(os, pending, df?.name ?: "<unknown>")
            } catch (e: FileNotFoundException) {
                showErrorDialog(R.string.externalStorageUnavailable)
                Log.w(TAG, "FileNotFound on output file picked by user?!")
            }
        } else if (pending == null) {
            Log.w(TAG, "No pending certificate after user picked output file")
        }
        finish()
    }

    private fun writeCertificate(fos: OutputStream?, cert: DatabaseCertificate, path: String) {
        val data = database.getCertificateData(cert.id)
        try {
            BufferedOutputStream(fos).use { it.write(data) }
            Toast.makeText(this, getString(R.string.export_success, path), Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorDialog(R.string.error_writing_to_storage)
        }
    }

    private fun showErrorDialog(resourceId: Int) {
        MaterialAlertDialogBuilder(this)
            .setMessage(resourceId)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private val TAG = CertificateExportActivity::class.java.name
    }
}
