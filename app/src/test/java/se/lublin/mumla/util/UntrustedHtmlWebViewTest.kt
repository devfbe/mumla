package se.lublin.mumla.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UntrustedHtmlWebViewTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val webView = WebView(app).apply { configureForUntrustedHtml() }

    @Test
    fun `network, file and content access are all blocked`() {
        val settings = webView.settings
        assertThat(settings.blockNetworkLoads).isTrue()
        assertThat(settings.blockNetworkImage).isTrue()
        assertThat(settings.allowFileAccess).isFalse()
        assertThat(settings.allowContentAccess).isFalse()
        assertThat(settings.javaScriptEnabled).isFalse()
    }

    @Test
    fun `an http link click opens an external viewer instead of navigating`() {
        val client = shadowOf(webView).webViewClient
        @Suppress("DEPRECATION")
        val handled = client.shouldOverrideUrlLoading(webView, "https://example.org/a")

        assertThat(handled).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.data).isEqualTo(Uri.parse("https://example.org/a"))
        assertThat(started.categories).contains(Intent.CATEGORY_BROWSABLE)
    }

    @Test
    fun `a link with an unexpected scheme is swallowed`() {
        val client = shadowOf(webView).webViewClient
        @Suppress("DEPRECATION")
        val handled = client.shouldOverrideUrlLoading(webView, "file:///data/data/se.lublin.mumla/x")

        assertThat(handled).isTrue()
        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }
}
