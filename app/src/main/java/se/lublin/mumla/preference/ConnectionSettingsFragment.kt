package se.lublin.mumla.preference

import android.os.Bundle
import androidx.preference.Preference
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.util.BatteryOptimization
import se.lublin.mumla.util.Orbot

/** Reconnecting, running in the background, and the transport and route to the server. */
class ConnectionSettingsFragment : MumlaPreferenceFragment(R.xml.settings_connection) {

    private val batteryPreference: Preference
        get() = requireNotNull(preferenceScreen.findPreference(Settings.PREF_BATTERY_OPTIMIZATION))

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        batteryPreference.setOnPreferenceClickListener {
            val context = requireContext()
            if (BatteryOptimization.isExempt(context)) {
                BatteryOptimization.openSettings(context)
            } else {
                BatteryOptimization.requestExemption(context)
            }
            true
        }

        val useOrbotPreference: Preference =
            requireNotNull(preferenceScreen.findPreference(Settings.USE_TOR.key))
        useOrbotPreference.isEnabled = Orbot.isInstalled(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // The exemption is changed in system screens, so it may differ on return.
        batteryPreference.setSummary(
            if (BatteryOptimization.isExempt(requireContext())) {
                R.string.battery_optimization_unrestricted
            } else {
                R.string.battery_optimization_optimized
            },
        )
    }
}
