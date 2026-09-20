package se.lublin.mumla.preference

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import info.guardianproject.netcipher.proxy.OrbotHelper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.BluetoothScoToggle

class GeneralSettingsFragment : MumlaPreferenceFragment() {

    private lateinit var bluetoothToggle: BluetoothScoToggle

    // Registered from the constructor because a fragment may not register a launcher once it has
    // been created.
    private val bluetoothPermissionRequester: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val enabled = bluetoothToggle.onPermissionResult(granted)
            findPreference<CheckBoxPreference>(Settings.PREF_BLUETOOTH_SCO)?.isChecked = enabled
            if (!enabled) {
                Toast.makeText(requireContext(), R.string.grant_perm_bluetooth, Toast.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_general, rootKey)

        val useOrbotPreference: Preference =
            requireNotNull(preferenceScreen.findPreference(USE_TOR_KEY))
        useOrbotPreference.isEnabled = OrbotHelper.isOrbotInstalled(requireContext())

        bluetoothToggle = BluetoothScoToggle(
            requireContext().applicationContext,
            Settings.getInstance(requireContext()),
        )
        val bluetoothPreference: CheckBoxPreference =
            requireNotNull(preferenceScreen.findPreference(Settings.PREF_BLUETOOTH_SCO))
        bluetoothPreference.setOnPreferenceChangeListener { _, newValue ->
            when (bluetoothToggle.request(newValue as Boolean)) {
                BluetoothScoToggle.Result.Enabled, BluetoothScoToggle.Result.Disabled -> true
                BluetoothScoToggle.Result.PermissionNeeded -> {
                    bluetoothPermissionRequester.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    // Refusing the change is what keeps the box empty: the user has a checked
                    // box or a working headset, never a checked box without one.
                    false
                }
            }
        }
    }

    private companion object {
        const val USE_TOR_KEY = "useTor"
    }
}
