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

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import se.lublin.humla.net.Pkcs12Certificates
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.util.UUID
import se.lublin.mumla.ui.AppMessages

class CertificateImportActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent(), ::onFilePicked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity gets the pending pick's result without asking again.
        if (savedInstanceState == null) picker.launch("*/*")
    }

    private fun onFilePicked(uri: Uri?) {
        if (uri == null) finish() else import(uri)
    }

    private fun import(uri: Uri) {
        // Read once: the picker's stream is spent after one read, and a password retry needs the bytes.
        val pkcs12: ByteArray = try {
            contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            finish()
            return
        } catch (e: IOException) {
            invalidCertificate(e)
            return
        }

        val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: (UUID.randomUUID().toString() + ".p12")

        storeKeystore(CharArray(0), displayName, pkcs12)
    }

    private fun storeKeystore(password: CharArray, fileName: String, pkcs12: ByteArray) {
        val keyStore: KeyStore = try {
            Pkcs12Certificates.load(ByteArrayInputStream(pkcs12), password)
        } catch (e: Exception) {
            when (e) {
                is CertificateException, is KeyStoreException, is IOException, is NoSuchAlgorithmException -> {
                    // A well-formed PKCS#12 file that does not open is taken to need a (different) password.
                    if (Pkcs12Certificates.isPkcs12(pkcs12)) {
                        askForPassword(fileName, pkcs12)
                    } else {
                        invalidCertificate(e)
                    }
                    return
                }
                else -> throw e
            }
        }

        val output = ByteArrayOutputStream()
        try {
            keyStore.store(output, CharArray(0))
        } catch (e: Exception) {
            when (e) {
                is KeyStoreException, is IOException, is NoSuchAlgorithmException, is CertificateException -> {
                    e.printStackTrace()
                    AppMessages.post(this, R.string.certificate_load_failed)
                    finish()
                    return
                }
                else -> throw e
            }
        }

        val pkcs12Out = output.toByteArray()
        val success = getString(R.string.certificate_import_success, fileName)
        lifecycleScope.launch {
            MumlaRepository.get(this@CertificateImportActivity).io { addCertificate(fileName, pkcs12Out) }
            AppMessages.get(this@CertificateImportActivity).post(success)
            finish()
        }
    }

    private fun askForPassword(fileName: String, pkcs12: ByteArray) {
        val passwordField = EditText(this)
        passwordField.setHint(R.string.password)
        passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.decrypt_certificate)
            .setView(passwordField)
            .setOnCancelListener { finish() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                storeKeystore(passwordField.text.toString().toCharArray(), fileName, pkcs12)
            }
            .show()
    }

    private fun invalidCertificate(e: Exception) {
        e.printStackTrace()
        AppMessages.post(this, R.string.invalid_certificate)
        finish()
    }
}
