package se.lublin.mumla.preference

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.BluetoothScoToggle
import se.lublin.mumla.util.Orbot

class GeneralSettingsFragment : MumlaPreferenceFragment() {

    private lateinit var bluetoothToggle: BluetoothScoToggle

    // Registered at construction: a fragment may not register a launcher once created.
    private val bluetoothPermissionRequester: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The answer does not decide the wish (see BluetoothScoToggle); the box follows it.
            bluetoothToggle.onPermissionAnswered()
            findPreference<CheckBoxPreference>(Settings.PREF_BLUETOOTH_SCO)?.isChecked =
                bluetoothToggle.isEnabled
            if (!granted) {
                Toast.makeText(requireContext(), R.string.bluetooth_perm_denied, Toast.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_general, rootKey)

        val useOrbotPreference: Preference =
            requireNotNull(preferenceScreen.findPreference(USE_TOR_KEY))
        useOrbotPreference.isEnabled = Orbot.isInstalled(requireContext())

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
                    // Keep the box empty until the dialog is answered; the callback writes the wish.
                    false
                }
            }
        }
    }

    private companion object {
        const val USE_TOR_KEY = "useTor"
    }
}
