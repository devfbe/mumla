package se.lublin.mumla.preference

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import se.lublin.mumla.R

/**
 * The certificate rows open our own screens by class, set here because preference XML cannot
 * name the flavor's package. An action alone would resolve to another installed Mumla variant,
 * which declares the same actions, and change that app's certificates instead.
 */
class AuthenticationSettingsFragment : MumlaPreferenceFragment(R.xml.settings_authentication) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        opens("certificateGenerate", CertificateGenerateActivity::class.java)
        opens("certificateSelect", CertificateSelectActivity::class.java)
        opens("certificateImport", CertificateImportActivity::class.java)
        opens("certificateExport", CertificateExportActivity::class.java)
        opens("clearTrust", ServerCertificateClearActivity::class.java)
    }

    private fun opens(key: String, activity: Class<out Activity>) {
        requireNotNull(findPreference<Preference>(key)).intent = Intent(requireContext(), activity)
    }
}
