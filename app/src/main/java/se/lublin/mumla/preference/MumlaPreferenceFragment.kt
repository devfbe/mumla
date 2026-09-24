package se.lublin.mumla.preference

import android.os.Bundle
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import se.lublin.mumla.R

/**
 * A screen of [SettingsActivity] built from [preferencesXml]. Titles the action bar with the
 * [ARG_TITLE] argument, and shows the dialogs of Mumla's own dialog preferences.
 */
abstract class MumlaPreferenceFragment(@XmlRes private val preferencesXml: Int) : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(preferencesXml, rootKey)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val dialog = when (preference) {
            is SeekBarDialogPreference -> SeekBarPreferenceDialogFragment()
            is KeySelectDialogPreference -> KeySelectPreferenceDialogFragment()
            else -> return super.onDisplayPreferenceDialog(preference)
        }
        if (childFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
        dialog.arguments = Bundle().apply { putString(PreferenceValueDialogFragment.ARG_KEY, preference.key) }
        dialog.show(childFragmentManager, DIALOG_TAG)
    }

    override fun onResume() {
        super.onResume()
        val actionBar = (requireActivity() as AppCompatActivity).supportActionBar ?: return
        val title = arguments?.getCharSequence(ARG_TITLE)
        if (title != null) actionBar.title = title else actionBar.setTitle(R.string.action_settings)
    }

    companion object {
        /** The action bar title of the screen. */
        const val ARG_TITLE = "title"

        private const val DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG"
    }
}
