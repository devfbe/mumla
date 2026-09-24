package se.lublin.mumla.preference

import android.os.Bundle
import androidx.preference.ListPreference
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import java.util.Locale

class AppearanceSettingsFragment : MumlaPreferenceFragment(R.xml.settings_appearance) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        // Each language named in itself, after the system default.
        findPreference<ListPreference>(Settings.PREF_LANGUAGE)?.let { language ->
            val codes = resources.getStringArray(R.array.languageValues)
            language.entries = arrayOf(getString(R.string.language_system)) +
                codes.map { Locale.forLanguageTag(it).let { locale -> locale.getDisplayName(locale) } }
            language.entryValues = arrayOf("system") + codes
        }
    }
}
