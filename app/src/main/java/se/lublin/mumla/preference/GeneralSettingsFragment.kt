package se.lublin.mumla.preference

import android.os.Bundle
import androidx.preference.Preference
import info.guardianproject.netcipher.proxy.OrbotHelper
import se.lublin.mumla.R

class GeneralSettingsFragment : MumlaPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_general, rootKey)

        val useOrbotPreference: Preference =
            requireNotNull(preferenceScreen.findPreference(USE_TOR_KEY))
        useOrbotPreference.isEnabled = OrbotHelper.isOrbotInstalled(requireContext())
    }

    private companion object {
        const val USE_TOR_KEY = "useTor"
    }
}
