package se.lublin.mumla.preference

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.db.MumlaSQLiteDatabase

@RunWith(RobolectricTestRunner::class)
class CertificateExportActivityTest {

    @Test
    fun `choosing a certificate asks the system to create a pkcs12 document named after it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MumlaSQLiteDatabase(context)
        db.addCertificate("alice.p12", byteArrayOf(1, 2, 3))
        db.close()

        val activity = Robolectric.buildActivity(CertificateExportActivity::class.java).setup().get()
        activity.onClick(null, 0)

        val started = shadowOf(activity).nextStartedActivityForResult
        assertThat(started).isNotNull()
        assertThat(started.intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(started.intent.type).isEqualTo("application/x-pkcs12")
        assertThat(started.intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("alice.p12")
    }
}
