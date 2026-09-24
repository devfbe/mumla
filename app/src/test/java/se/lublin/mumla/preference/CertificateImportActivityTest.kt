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

package se.lublin.mumla.preference

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.humla.net.Pkcs12Certificates
import se.lublin.mumla.db.MumlaSQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Importing a certificate closes the picked document's stream on every path. */
@RunWith(RobolectricTestRunner::class)
class CertificateImportActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://test.documents/cert.p12")

    private open class RecordingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(MumlaSQLiteDatabase.DATABASE_NAME)
    }

    /** A document that fails half way, as a provider backed by the network can. */
    private class FailingStream : RecordingStream(ByteArray(0)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("gone")
        override fun read(): Int = throw java.io.IOException("gone")
    }

    private fun import(stream: RecordingStream) {
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        val controller = Robolectric.buildActivity(CertificateImportActivity::class.java).setup()
        val activity = controller.get()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun storedCertificates() = MumlaSQLiteDatabase(context).let { db ->
        try { db.getCertificates() } finally { db.close() }
    }

    @Test
    fun anUnreadableDocumentIsClosed() {
        val stream = RecordingStream(byteArrayOf(1, 2, 3))

        import(stream)

        assertThat(storedCertificates()).isEmpty()
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun anImportedCertificateIsStoredAndItsDocumentClosed() {
        val p12 = ByteArrayOutputStream().also { HumlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        val stream = RecordingStream(p12)

        import(stream)

        assertThat(storedCertificates()).hasSize(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun aDocumentThatFailsToReadIsClosedAndEndsTheImport() {
        val stream = FailingStream()

        import(stream)

        assertThat(storedCertificates()).isEmpty()
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun aPasswordProtectedCertificateAsksForThePasswordInAMaskedField() {
        val plain = ByteArrayOutputStream().also { HumlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        val keyStore = Pkcs12Certificates.load(plain, null)
        val protectedP12 = ByteArrayOutputStream().also { keyStore.store(it, "secret".toCharArray()) }.toByteArray()

        import(RecordingStream(protectedP12))

        val dialog = ShadowDialog.getLatestDialog()
        assertThat(dialog).isNotNull()
        val field = findEditText(dialog.window!!.decorView)
        assertThat(field).isNotNull()
        assertThat(field!!.inputType and InputType.TYPE_MASK_CLASS).isEqualTo(InputType.TYPE_CLASS_TEXT)
        assertThat(field.inputType and InputType.TYPE_MASK_VARIATION).isEqualTo(InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertThat(field.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)

        field.setText("secret")
        (dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(storedCertificates()).hasSize(1)
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findEditText(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
