package se.lublin.mumla.preference

import android.os.Bundle
import androidx.preference.Preference
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.ui.showAllNewsDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    private companion object {
        const val VERSION_KEY = "version"
        const val SHOW_NEWS_KEY = "showNews"
    }
}
