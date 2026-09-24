package se.lublin.mumla.preference

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.humla.net.Pkcs12Certificates
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaSQLiteDatabase
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class CertificateExportActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        installDatabase()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(MumlaSQLiteDatabase.DATABASE_NAME)
    }

    private fun storeCertificate(name: String, data: ByteArray) {
        val db = MumlaSQLiteDatabase(context)
        db.addCertificate(name, data)
        db.close()
    }

    private fun startAndPick(): CertificateExportActivity {
        val activity = Robolectric.buildActivity(CertificateExportActivity::class.java).setup().get()
        activity.workDispatcher = Dispatchers.Unconfined
        activity.onClick(null, 0)
        return activity
    }

    private fun passwordDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.enter(password: String, confirm: String) {
        findViewById<EditText>(R.id.export_password)!!.setText(password)
        findViewById<EditText>(R.id.export_password_confirm)!!.setText(confirm)
        getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()
    }

    @Test
    fun `choosing a certificate asks for a password before creating the document`() {
        storeCertificate("alice.p12", byteArrayOf(1, 2, 3))
        val activity = startAndPick()

        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()
        val dialog = passwordDialog()
        assertThat(dialog.findViewById<EditText>(R.id.export_password)).isNotNull()

        dialog.enter("secret", "secret")

        val started = shadowOf(activity).nextStartedActivityForResult
        assertThat(started).isNotNull()
        assertThat(started.intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(started.intent.type).isEqualTo("application/x-pkcs12")
        assertThat(started.intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("alice.p12")
    }

    @Test
    fun `an empty or mismatched password keeps the dialog open`() {
        storeCertificate("alice.p12", byteArrayOf(1, 2, 3))
        val activity = startAndPick()
        val dialog = passwordDialog()

        dialog.enter("", "")
        assertThat(dialog.isShowing).isTrue()
        assertThat(dialog.findViewById<EditText>(R.id.export_password)!!.error).isNotNull()

        dialog.enter("secret", "other")
        assertThat(dialog.isShowing).isTrue()
        assertThat(dialog.findViewById<EditText>(R.id.export_password_confirm)!!.error).isNotNull()
        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()
    }

    @Test
    fun `the exported file opens only with the chosen password`() {
        val stored = ByteArrayOutputStream().also { HumlaCertificateGenerator.generateCertificate(it) }.toByteArray()
        storeCertificate("alice.p12", stored)
        val uri = Uri.parse("content://test.documents/alice.p12")
        val written = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, written)

        val activity = startAndPick()
        passwordDialog().enter("secret", "secret")
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        idleMainLooper()

        val exported = written.toByteArray()
        assertThat(exported).isNotEmpty()
        assertThat(exported).isNotEqualTo(stored)
        assertThrows(IOException::class.java) { Pkcs12Certificates.load(exported, null) }
        val store = Pkcs12Certificates.load(exported, "secret")
        assertThat(store.aliases().toList().any { store.isKeyEntry(it) }).isTrue()
        assertThat(activity.isFinishing).isTrue()
    }
}
