package se.lublin.mumla.preference

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * The settings half of spec P3: `BLUETOOTH_CONNECT` is asked for *before* SCO is used, wherever
 * the user flips the switch. Without the gate a tick here writes `true` and the service then
 * silently refuses to route anything -- a checked box that does nothing, which is worse than no
 * box, because the user has no way to see why they are not heard.
 *
 * The box is driven with `performClick()`, not `callChangeListener(...)`: the latter neither
 * ticks the box nor persists anything by itself, so every assertion about the box would hold
 * whatever the listener did. `performClick()` is the path `PreferenceGroupAdapter` runs when the
 * row is tapped, which is what makes `isChecked` and the persisted value observables at all.
 */
@RunWith(RobolectricTestRunner::class)
class GeneralSettingsBluetoothTest {

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var app: Application
    private lateinit var activity: HostActivity
    private lateinit var fragment: GeneralSettingsFragment
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // A stored value shadows the XML default, and preferences survive between test methods
        // in one JVM. Without this the default test measures what an earlier method left behind.
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        settings = Settings.getInstance(app)
        ShadowToast.reset()

        activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        fragment = GeneralSettingsFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
    }

    private fun checkBox(): CheckBoxPreference {
        val preference = fragment.preferenceScreen
            .findPreference<Preference>(Settings.PREF_BLUETOOTH_SCO)
        assertWithMessage(
            "no preference with key '%s' on the general settings screen",
            Settings.PREF_BLUETOOTH_SCO,
        ).that(preference).isNotNull()
        assertThat(preference).isInstanceOf(CheckBoxPreference::class.java)
        return preference as CheckBoxPreference
    }

    private fun lastRequestedPermissions(): List<String> =
        shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList() ?: emptyList()

    /** Hands the fragment's `ActivityResultLauncher` the answer the system would deliver. */
    private fun answerThePermissionDialog(granted: Boolean) {
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission) {
            "nothing asked for a permission, so there is no dialog to answer"
        }
        if (granted) {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        }
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
    }

    @Test
    fun theCheckBoxSitsInTheControlsCategoryAndStartsFromTheCodeDefault() {
        val category = fragment.preferenceScreen.findPreference<Preference>("controls_settings")
        assertWithMessage("no PreferenceCategory with key 'controls_settings'")
            .that(category).isNotNull()
        assertThat(category).isInstanceOf(PreferenceCategory::class.java)
        assertThat((category as PreferenceGroup).findPreference<Preference>(Settings.PREF_BLUETOOTH_SCO))
            .isNotNull()

        // Two sources for one default: android:defaultValue on this screen, and
        // Settings.DEFAULT_BLUETOOTH_SCO, which is what every other reader gets. A divergence is
        // invisible at runtime until the screen has been opened once.
        assertThat(checkBox().isChecked).isEqualTo(Settings.DEFAULT_BLUETOOTH_SCO)
        assertThat(settings.isBluetoothScoEnabled()).isEqualTo(Settings.DEFAULT_BLUETOOTH_SCO)
    }

    @Test
    fun theSummarySaysThatThePermissionIsNeeded() {
        // The box can be tapped and stay empty. The only place that can be explained beforehand
        // is the text under it.
        val summary = checkBox().summary.toString().lowercase()

        assertWithMessage("the summary must name the permission the tick will ask for: %s", summary)
            .that(summary).containsMatch("""nearby devices""")
    }

    @Test
    fun tickingItWithoutThePermissionAsksForItAndLeavesTheBoxEmpty() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(lastRequestedPermissions()).contains(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(checkBox().isChecked).isFalse()
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun tickingItWithThePermissionPersistsIt() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(checkBox().isChecked).isTrue()
        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun untickingItNeverAsksForThePermission() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(checkBox().isChecked).isFalse()
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun grantingThePermissionAfterwardsTurnsItOnAndTicksTheBox() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()

        answerThePermissionDialog(granted = true)

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        assertThat(checkBox().isChecked).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
    }

    @Test
    fun denyingThePermissionLeavesItOffAndSaysWhatIsMissing() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()

        answerThePermissionDialog(granted = false)

        assertThat(settings.isBluetoothScoEnabled()).isFalse()
        assertThat(checkBox().isChecked).isFalse()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.grant_perm_bluetooth))
    }
}
