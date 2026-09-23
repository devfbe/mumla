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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.net.Pkcs12Certificates
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaSQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.util.UUID

/**
 * Created by andrew on 11/01/16.
 */
class CertificateImportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fileIntent = Intent(Intent.ACTION_GET_CONTENT)
        fileIntent.setType("*/*")
        fileIntent.addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION")
        startActivityForResult(fileIntent, REQUEST_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_FILE) return

        if (resultCode == RESULT_CANCELED) {
            finish()
            return
        }

        val uri: Uri = data!!.data!!
        val input: InputStream = try {
            contentResolver.openInputStream(uri)!!
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            // FIXME(acomminos)
            finish()
            return
        }

        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        val displayName = if (cursor != null && cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            UUID.randomUUID().toString() + ".p12"
        }
        cursor?.close()

        storeKeystore(CharArray(0), displayName, input)
    }

    private fun storeKeystore(password: CharArray, fileName: String, input: InputStream) {
        val keyStore: KeyStore = try {
            Pkcs12Certificates.load(input, password)
        } catch (e: CertificateException) {
            // A problem occurred when reading the stream; interpret this as a password being
            // required. Request a password from the user and reattempt decryption.
            // FIXME(acomminos): examine p12 file's SafeBags to determine the presence of a password
            val passwordField = EditText(this)
            passwordField.setHint(R.string.password)
            passwordField.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.decrypt_certificate)
                .setView(passwordField)
                .setOnCancelListener { finish() }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    storeKeystore(passwordField.text.toString().toCharArray(), fileName, input)
                }
                .show()
            return
        } catch (e: KeyStoreException) {
            invalidCertificate(e)
            return
        } catch (e: IOException) {
            invalidCertificate(e)
            return
        } catch (e: NoSuchAlgorithmException) {
            invalidCertificate(e)
            return
        }

        val output = ByteArrayOutputStream()
        try {
            keyStore.store(output, CharArray(0))
        } catch (e: Exception) {
            when (e) {
                is KeyStoreException, is IOException, is NoSuchAlgorithmException, is CertificateException -> {
                    e.printStackTrace()
                    Toast.makeText(this, R.string.certificate_load_failed, Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                else -> throw e
            }
        }

        val database: MumlaDatabase = MumlaSQLiteDatabase(this)
        database.addCertificate(fileName, output.toByteArray())
        database.close()

        Toast.makeText(this, getString(R.string.certificate_import_success, fileName), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun invalidCertificate(e: Exception) {
        e.printStackTrace()
        Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val REQUEST_FILE = 0
    }
}
