package se.lublin.mumla.ui

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.databinding.DialogNewsBinding
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/** Shows [message] with an OK button; [onDismiss] runs however the dialog is closed. */
fun Context.showMessageDialog(message: CharSequence, onDismiss: (() -> Unit)? = null): AlertDialog =
    MaterialAlertDialogBuilder(this)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
        .show()

/** Asks [message], under [title] if given; [onConfirm] runs only if the user picks [confirmLabel]. */
fun Context.showConfirmDialog(
    message: CharSequence,
    @StringRes confirmLabel: Int = android.R.string.ok,
    title: CharSequence? = null,
    onConfirm: () -> Unit,
): AlertDialog =
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()

/** The news of each release, oldest first; add new versions at the end. */
private val NEWS_ITEMS = linkedMapOf(
    "3.7.0" to R.string.app_news_items_v3_7_0,
    "3.7.1" to R.string.app_news_items_v3_7_1,
    "3.7.2" to R.string.app_news_items_v3_7_2,
    "3.7.3" to R.string.app_news_items_v3_7_3,
)

/** The versions in [NEWS_ITEMS] up to this build's. */
private val RELEVANT_VERSIONS: List<String> by lazy {
    val build = Version.parse(BuildConfig.VERSIONTAG)
    NEWS_ITEMS.keys.takeWhile { Version.parse(it) <= build }
}

/** Shows the news of releases the user has not seen yet; returns whether there were any. */
fun maybeShowNewsDialog(context: Context): Boolean {
    val shown = Settings.getInstance(context).newsShownVersions
    val toShow = RELEVANT_VERSIONS.filter { it !in shown }
    if (toShow.isEmpty()) return false
    showNewsDialog(context, toShow, markShown = true)
    return true
}

/** Shows the news of every release up to this one. */
fun showAllNewsDialog(context: Context) = showNewsDialog(context, RELEVANT_VERSIONS, markShown = false)

private fun showNewsDialog(context: Context, versions: List<String>, markShown: Boolean) {
    val html = buildString {
        for (version in versions.asReversed()) {
            val resId = NEWS_ITEMS[version] ?: continue
            append("<b>${context.getString(R.string.version)} $version</b><br/>")
            append(context.getString(resId).replace("\n", "<br/>"))
            append("<br/>")
        }
        if (markShown) append("<em>${context.getString(R.string.app_news_footer_on_startup)}</em>")
    }
    val binding = DialogNewsBinding.inflate(LayoutInflater.from(context))
    binding.newsTextView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.app_news)
        .setView(binding.root)
        .setCancelable(false)
        .setPositiveButton(android.R.string.ok) { dialog, _ ->
            if (markShown) Settings.getInstance(context).addNewsShownVersions(versions)
            dialog.dismiss()
        }
        .show()
}

/** A `major.minor.patch` version; a `-suffix` is ignored, and missing or bad parts count as 0. */
private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    companion object {
        private const val PARTS = 3

        fun parse(version: String): Version {
            val numbers = mutableListOf<Int>()
            for (part in version.substringBefore('-').split('.').take(PARTS)) {
                val number = part.toIntOrNull()
                if (number == null) {
                    Log.d("DialogUtils", "Failed to parse version string: $version")
                    break
                }
                numbers += number
            }
            return Version(numbers.getOrElse(0) { 0 }, numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
        }
    }
}
