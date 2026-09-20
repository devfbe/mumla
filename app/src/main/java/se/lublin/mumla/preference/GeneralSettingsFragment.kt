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
            // Keep asking, stop gating (spec 4.1). See BluetoothScoToggle for why the answer does
            // not decide the wish; the box follows the wish, and the toast says what a denial may
            // cost on a device that enforces more than the annotations declare.
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
                    // Refusing the change keeps the box empty until the dialog has been answered,
                    // which is what makes P3's "asked before SCO is used" an ordering and not a
                    // slogan: the callback above is what writes the wish, and the service starts
                    // routing off that write.
                    false
                }
            }
        }
    }

    private companion object {
        const val USE_TOR_KEY = "useTor"
    }
}
