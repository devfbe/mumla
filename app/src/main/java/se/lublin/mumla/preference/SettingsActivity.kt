package se.lublin.mumla.preference

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ActivitySettingsBinding
import se.lublin.mumla.ui.showAppMessages
import se.lublin.mumla.util.Edge
import se.lublin.mumla.util.padForSystemBars

/** The settings: an index of screens, each opened on top of it with the back stack. */
class SettingsActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appBar.padForSystemBars(Edge.START, Edge.TOP, Edge.END)
        binding.settingsContainer.padForSystemBars(Edge.START, Edge.END)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.action_settings)
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        showAppMessages()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, RootPreferenceFragment())
                .commit()
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val className = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, className)
        fragment.arguments = Bundle(pref.extras).apply {
            putCharSequence(MumlaPreferenceFragment.ARG_TITLE, pref.title)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    /** The index of the settings screens. */
    class RootPreferenceFragment : MumlaPreferenceFragment(R.xml.preference_headers)
}
