package se.lublin.mumla.preference

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.log.AppLog
import se.lublin.mumla.ui.showAllNewsDialog
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "AboutSettings"
private const val VERSION_KEY = "version"
private const val SHOW_NEWS_KEY = "showNews"
private const val SHARE_LOG_KEY = "shareLog"

class AboutSettingsFragment : MumlaPreferenceFragment(R.xml.settings_about) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        requireNotNull(findPreference<Preference>(VERSION_KEY)).apply {
            summary = versionSummary()
            setOnPreferenceClickListener {
                Settings.getInstance(requireContext()).resetNewsShownVersion()
                true
            }
        }
        requireNotNull(findPreference<Preference>(SHOW_NEWS_KEY)).setOnPreferenceClickListener {
            showAllNewsDialog(requireContext())
            true
        }
        requireNotNull(findPreference<Preference>(SHARE_LOG_KEY)).setOnPreferenceClickListener {
            shareLog()
            true
        }
    }

    private fun shareLog() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = try {
                withContext(Dispatchers.IO) { AppLog.export(context, AppLog.report()) }
            } catch (e: IOException) {
                HumlaLog.w(TAG, "Could not write the log", e)
                Toast.makeText(context, R.string.share_log_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val title = getString(R.string.share_log)
            startActivity(Intent.createChooser(AppLog.shareIntent(uri, title), title))
        }
    }

    private fun versionSummary(): String = buildString {
        append("${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        when (BuildConfig.FLAVOR) {
            "foss" -> append("\nFOSS flavor")
            "beta" -> {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                append("\nBeta flavor with versioncode ${BuildConfig.VERSION_CODE}")
                append("\nBuildtime ${format.format(Date(BuildConfig.TIMESTAMP))} UTC")
            }
            "donation" -> append("\n*) ${getString(R.string.donation_thanks)}")
        }
    }
}
