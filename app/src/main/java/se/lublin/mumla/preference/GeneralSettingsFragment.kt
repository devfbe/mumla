package se.lublin.mumla.preference

import android.Manifest
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.BluetoothScoToggle
import se.lublin.mumla.ui.showPermissionDeniedSnackbar
import se.lublin.mumla.util.BatteryOptimization
import se.lublin.mumla.util.Orbot

class GeneralSettingsFragment : MumlaPreferenceFragment(R.xml.settings_general) {

    private lateinit var bluetoothToggle: BluetoothScoToggle

    // Registered at construction: a fragment may not register a launcher once created.
    private val bluetoothPermissionRequester: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The answer does not decide the wish (see BluetoothScoToggle); the box follows it.
            bluetoothToggle.onPermissionAnswered()
            findPreference<CheckBoxPreference>(Settings.BLUETOOTH_SCO.key)?.isChecked =
                bluetoothToggle.isEnabled
            if (!granted) {
                requireActivity().showPermissionDeniedSnackbar(R.string.bluetooth_perm_denied)
            }
        }

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

        bluetoothToggle = BluetoothScoToggle(
            requireContext().applicationContext,
            Settings.getInstance(requireContext()),
        )
        val bluetoothPreference: CheckBoxPreference =
            requireNotNull(preferenceScreen.findPreference(Settings.BLUETOOTH_SCO.key))
        bluetoothPreference.setOnPreferenceChangeListener { _, newValue ->
            when (bluetoothToggle.request(newValue as Boolean)) {
                BluetoothScoToggle.Result.Enabled, BluetoothScoToggle.Result.Disabled -> true
                BluetoothScoToggle.Result.PermissionNeeded -> {
                    bluetoothPermissionRequester.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    // Keep the box empty until the dialog is answered; the callback writes the wish.
                    false
                }
            }
        }
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
