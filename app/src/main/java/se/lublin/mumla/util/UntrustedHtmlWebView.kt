package se.lublin.mumla.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import se.lublin.humla.util.HumlaLog

private const val TAG = "UntrustedHtmlWebView"
private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "mumble")

/**
 * Locks this WebView down for rendering server-supplied HTML: no network, file or content loads,
 * no JavaScript, and link clicks are handed to an external viewer instead of navigating in place.
 */
fun WebView.configureForUntrustedHtml() {
    settings.apply {
        javaScriptEnabled = false
        blockNetworkLoads = true
        blockNetworkImage = true
        allowFileAccess = false
        allowContentAccess = false
    }
    webViewClient = ExternalLinkWebViewClient()
}

private class ExternalLinkWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        openExternally(view, request.url)

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        openExternally(view, Uri.parse(url))

    private fun openExternally(view: WebView, uri: Uri): Boolean {
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return true
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            HumlaLog.w(TAG, "No app to open $uri", e)
        }
        return true
    }
}
