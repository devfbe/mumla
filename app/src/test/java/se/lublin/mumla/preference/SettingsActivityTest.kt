package se.lublin.mumla.preference

import android.content.DialogInterface
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.slider.Slider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.currentScreen
import se.lublin.mumla.testing.openScreen
import se.lublin.mumla.testing.rowOf

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {
    private val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())

    private fun showDialog(fragment: PreferenceFragmentCompat, key: String): AlertDialog {
        fragment.onDisplayPreferenceDialog(requireNotNull(fragment.findPreference<Preference>(key)))
        idleMainLooper()
        val dialogFragment = fragment.childFragmentManager.fragments.filterIsInstance<DialogFragment>().single()
        return dialogFragment.requireDialog() as AlertDialog
    }

    @Test
    fun `a screen opens on top of the index with its title, and back returns`() {
        val general = activity.openScreen(GeneralSettingsFragment::class.java)

        assertThat(general).isInstanceOf(GeneralSettingsFragment::class.java)
        assertThat(activity.supportActionBar?.title).isEqualTo(activity.getString(R.string.general))

        activity.onBackPressedDispatcher.onBackPressed()
        idleMainLooper()

        assertThat(activity.currentScreen()).isInstanceOf(SettingsActivity.RootPreferenceFragment::class.java)
        assertThat(activity.supportActionBar?.title).isEqualTo(activity.getString(R.string.action_settings))
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `back on the index leaves the settings`() {
        activity.onBackPressedDispatcher.onBackPressed()

        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `a slider shows its value with the unit and stores a moved thumb as it is shown`() {
        val appearance = activity.openScreen(AppearanceSettingsFragment::class.java)
        val row = appearance.rowOf(Settings.PTT_BUTTON_HEIGHT.key)
        val value = row.findViewById<TextView>(R.id.slider_value)
        assertThat(value.text.toString()).isEqualTo(activity.getString(R.string.unitDp, 150))

        row.findViewById<Slider>(R.id.slider)
            .onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))

        assertThat(value.text.toString()).isEqualTo(activity.getString(R.string.unitDp, 160))
        assertThat(preferences.getInt(Settings.PTT_BUTTON_HEIGHT.key, 0)).isEqualTo(160)
        assertThat(Settings.getInstance(activity).pttButtonHeight).isEqualTo(160)
    }

    @Test
    fun `the key picker stores the pressed key on ok, and resets it with the neutral button`() {
        val audio = activity.openScreen(AudioSettingsFragment::class.java)

        var dialog = showDialog(audio, Settings.TALK_KEY.key)
        val content = dialog.findViewById<TextView>(R.id.key_select_value_view)!!
        assertThat(content.text.toString()).isEqualTo(activity.getString(R.string.no_ptt_key))
        val root = content.rootView.findFocus()
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertThat(content.text.toString())
            .isEqualTo(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_VOLUME_UP).removePrefix("KEYCODE_"))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()
        assertThat(preferences.getInt(Settings.TALK_KEY.key, 0)).isEqualTo(KeyEvent.KEYCODE_VOLUME_UP)

        dialog = showDialog(audio, Settings.TALK_KEY.key)
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idleMainLooper()
        assertThat(preferences.getInt(Settings.TALK_KEY.key, -1)).isEqualTo(0)
    }
}
