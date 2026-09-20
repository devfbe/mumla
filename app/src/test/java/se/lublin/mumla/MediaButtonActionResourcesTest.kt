package se.lublin.mumla

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.preference.GeneralSettingsFragment
import se.lublin.mumla.preference.SettingsActivity

/**
 * The headset-button preference, checked where the user meets it rather than only in the
 * resource tables.
 *
 * Two of these tests exist because of what stream P already got wrong once: eleven tests in
 * `ChannelFragmentTalkStateTest` drove a talk button that was `GONE`, and passed. A settings
 * entry has the same failure mode one level up -- an array can be perfect while the preference
 * is on no screen, or the screen on no index -- so the screen is inflated for real here, out of
 * the fragment the settings index actually launches.
 *
 * The wording assertions are not decoration. Spec section 4.1 ("Name the two push-to-talk
 * behaviours in the settings UI") makes three statements binding on this preference, and the
 * only place they can be pinned is the text the user reads.
 */
@RunWith(RobolectricTestRunner::class)
class MediaButtonActionResourcesTest {

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A persisted value shadows the XML default, and a ListPreference persists the default
        // the moment it is attached -- so the default test only means anything from empty
        // preferences. This is the one stored value that can change what these tests observe;
        // the other nine keys on this screen are independent checkboxes.
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun stringArrayByName(name: String): List<String> {
        val id = context.resources.getIdentifier(name, "array", context.packageName)
        assertWithMessage("string-array R.array.%s is missing", name).that(id).isNotEqualTo(0)
        return context.resources.getStringArray(id).toList()
    }

    private fun generalScreen(): PreferenceScreen {
        val fragment = GeneralSettingsFragment()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        return fragment.preferenceScreen
    }

    private fun mediaButtonPreference(): ListPreference {
        val screen = generalScreen()
        val preference = screen.findPreference<Preference>(Settings.PREF_MEDIA_BUTTON_ACTION)
        assertWithMessage(
            "no preference with key '%s' on the general settings screen",
            Settings.PREF_MEDIA_BUTTON_ACTION
        ).that(preference).isNotNull()
        assertThat(preference).isInstanceOf(ListPreference::class.java)
        return preference as ListPreference
    }

    @Test
    fun theHeadsetButtonPreferenceSitsInTheControlsCategory() {
        val screen = generalScreen()

        val category = screen.findPreference<Preference>("controls_settings")
        assertWithMessage("no PreferenceCategory with key 'controls_settings'")
            .that(category).isNotNull()
        assertThat(category).isInstanceOf(PreferenceCategory::class.java)
        assertThat((category as PreferenceGroup).findPreference<Preference>(Settings.PREF_MEDIA_BUTTON_ACTION))
            .isNotNull()
        assertThat(category.title.toString()).isNotEmpty()
    }

    @Test
    fun theGeneralScreenIsReachableFromTheSettingsIndex() {
        val index = SettingsActivity.RootPreferenceFragment()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, index)
            .commitNow()

        val screen = index.preferenceScreen
        val fragments = (0 until screen.preferenceCount).map { screen.getPreference(it).fragment }
        assertThat(fragments).contains(GeneralSettingsFragment::class.java.name)
    }

    @Test
    fun entryValuesAreExactlyTheEnumsPrefValues() {
        val values = stringArrayByName("mediaButtonActionValues")

        // Spelled out so the assertion cannot mirror the enum ...
        assertThat(values).containsExactly("none", "auto", "mute").inOrder()
        // ... and compared against the whole enum, so a fourth constant added later fails here
        // instead of quietly becoming an action the user cannot choose.
        assertThat(values).isEqualTo(MediaButtonAction.entries.map { it.prefValue })
    }

    @Test
    fun everyEntryValueHasItsOwnName() {
        val names = stringArrayByName("mediaButtonActionNames")
        val values = stringArrayByName("mediaButtonActionValues")

        assertThat(names).hasSize(values.size)
        assertThat(names).containsNoDuplicates()
        names.forEach { assertThat(it.trim()).isNotEmpty() }
    }

    @Test
    fun theListPreferenceOffersExactlyThoseEntries() {
        val preference = mediaButtonPreference()

        assertThat(preference.entryValues.map { it.toString() })
            .isEqualTo(stringArrayByName("mediaButtonActionValues"))
        assertThat(preference.entries.map { it.toString() })
            .isEqualTo(stringArrayByName("mediaButtonActionNames"))
    }

    @Test
    fun theScreenDefaultAndTheCodeDefaultAgree() {
        val preference = mediaButtonPreference()

        // Two sources of one default: `android:defaultValue` in the XML and
        // Settings.DEFAULT_MEDIA_BUTTON_ACTION, which is what every reader outside this screen
        // gets. A mismatch is invisible at runtime because fromPrefValue() falls back to AUTO.
        assertThat(preference.value).isEqualTo(Settings.DEFAULT_MEDIA_BUTTON_ACTION)
        assertThat(stringArrayByName("mediaButtonActionValues")).contains(preference.value)
        assertThat(Settings.getInstance(context).getMediaButtonAction())
            .isEqualTo(MediaButtonAction.fromPrefValue(preference.value))
    }

    @Test
    fun theSummarySaysTapAndNotHold() {
        // Measured in task 4 against the decompiled MediaSessionService: once a press produces
        // repeats (~400 ms) the service stops tracking, and everything after that -- the repeats
        // and the final UP -- is swallowed, so a held button produces zero toggles. The user has
        // to learn that from the preference rather than from not being heard.
        //
        // These match phrases, not words, on purpose: the first draft asserted `contains("tap")`
        // and would have survived deleting the whole imperative, because "one tap switches on"
        // sits in another sentence. Reword the summary freely -- but both facts have to survive
        // the rewording, and then these patterns are updated deliberately.
        val summary = mediaButtonPreference().summary.toString().lowercase()

        assertWithMessage("the summary must tell the user to tap the button: %s", summary)
            .that(summary).containsMatch("""tap it""")
        assertWithMessage("the summary must say that a held button does nothing: %s", summary)
            .that(summary).containsMatch("""hold[^.]*\b(nothing|no effect|does not)""")
    }

    @Test
    fun theSummaryNamesTheHoldVersusToggleDifference() {
        // Same physical button, two behaviours: PREF_PTT_TOGGLE defaults to false, so the
        // on-screen and physical push-to-talk keys transmit only while held, while this one
        // toggles -- a headset button cannot be held. Spec 4.1 requires that to be said where
        // the action is chosen, not only in a stream ledger.
        val summary = mediaButtonPreference().summary.toString().lowercase()

        assertWithMessage("the summary must say that this button toggles: %s", summary)
            .that(summary).containsMatch("""it toggles""")
        assertWithMessage("the summary must say that push-to-talk is held instead: %s", summary)
            .that(summary).containsMatch("""push-to-talk[^.]*\bhold""")
        assertWithMessage("the summary must name the preference that changes the other half")
            .that(summary).contains(context.getString(R.string.togglePtt).lowercase())
    }

    @Test
    fun theOffEntrySaysThatOtherAppsKeepTheMediaKeys() {
        // MediaKeyHandler must not consume the event for NONE, or the media keys break for
        // every other app and nobody would blame Mumla. "Nothing" alone does not say that.
        val names = stringArrayByName("mediaButtonActionNames")
        val values = stringArrayByName("mediaButtonActionValues")
        val offName = names[values.indexOf(MediaButtonAction.NONE.prefValue)].lowercase()

        assertWithMessage("the 'none' entry must say the keys stay with other apps: %s", offName)
            .that(offName).contains("app")
    }
}
