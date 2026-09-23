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
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.mumla.db.MumlaSQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Importing a certificate reads a document the user picked. That stream was never closed, on any
 * path -- a ParcelFileDescriptor underneath on a device, reclaimed by the finalizer ("A resource
 * failed to call close").
 */
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
}
