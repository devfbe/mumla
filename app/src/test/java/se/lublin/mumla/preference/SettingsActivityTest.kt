package se.lublin.mumla.preference

import android.content.DialogInterface
import android.view.KeyEvent
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {
    private val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())

    private fun screen(): PreferenceFragmentCompat =
        activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat

    private fun open(fragmentClass: Class<*>): PreferenceFragmentCompat {
        val root = screen()
        val entry = (0 until root.preferenceScreen.preferenceCount)
            .map { root.preferenceScreen.getPreference(it) }
            .single { it.fragment == fragmentClass.name }
        root.onPreferenceTreeClick(entry)
        shadowOf(Looper.getMainLooper()).idle()
        return screen()
    }

    private fun showDialog(fragment: PreferenceFragmentCompat, key: String): AlertDialog {
        fragment.onDisplayPreferenceDialog(requireNotNull(fragment.findPreference<Preference>(key)))
        shadowOf(Looper.getMainLooper()).idle()
        val dialogFragment = fragment.childFragmentManager.fragments.filterIsInstance<DialogFragment>().single()
        return dialogFragment.requireDialog() as AlertDialog
    }

    @Test
    fun `a screen opens on top of the index with its title, and back returns`() {
        val general = open(GeneralSettingsFragment::class.java)

        assertThat(general).isInstanceOf(GeneralSettingsFragment::class.java)
        assertThat(activity.supportActionBar?.title).isEqualTo(activity.getString(R.string.general))

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(screen()).isInstanceOf(SettingsActivity.RootPreferenceFragment::class.java)
        assertThat(activity.supportActionBar?.title).isEqualTo(activity.getString(R.string.action_settings))
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun `back on the index leaves the settings`() {
        activity.onBackPressedDispatcher.onBackPressed()

        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `the slider stores the multiplied value on ok, and nothing on cancel`() {
        val appearance = open(AppearanceSettingsFragment::class.java)

        var dialog = showDialog(appearance, Settings.PREF_PTT_BUTTON_HEIGHT)
        val seekBar = dialog.findViewById<SeekBar>(R.id.seek_bar)!!
        val value = dialog.findViewById<TextView>(R.id.seek_bar_value_view)!!
        assertThat(value.text.toString()).isEqualTo("150 dp")
        seekBar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        val expected = (15 + seekBar.progress) * 10
        assertThat(value.text.toString()).isEqualTo("$expected dp")

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(preferences.contains(Settings.PREF_PTT_BUTTON_HEIGHT)).isFalse()

        dialog = showDialog(appearance, Settings.PREF_PTT_BUTTON_HEIGHT)
        dialog.findViewById<SeekBar>(R.id.seek_bar)!!
            .onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(preferences.getInt(Settings.PREF_PTT_BUTTON_HEIGHT, 0)).isEqualTo(expected)
    }

    @Test
    fun `the key picker stores the pressed key on ok, and resets it with the neutral button`() {
        val audio = open(AudioSettingsFragment::class.java)

        var dialog = showDialog(audio, Settings.PREF_PUSH_KEY)
        val content = dialog.findViewById<TextView>(R.id.key_select_value_view)!!
        assertThat(content.text.toString()).isEqualTo(activity.getString(R.string.no_ptt_key))
        val root = content.rootView.findFocus()
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertThat(content.text.toString())
            .isEqualTo(KeyEvent.keyCodeToString(KeyEvent.KEYCODE_VOLUME_UP).removePrefix("KEYCODE_"))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(preferences.getInt(Settings.PREF_PUSH_KEY, 0)).isEqualTo(KeyEvent.KEYCODE_VOLUME_UP)

        dialog = showDialog(audio, Settings.PREF_PUSH_KEY)
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(preferences.getInt(Settings.PREF_PUSH_KEY, -1)).isEqualTo(0)
    }
}
