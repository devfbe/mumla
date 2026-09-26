package se.lublin.mumla.preference

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
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
import se.lublin.mumla.testing.ThemedActivity

/**
 * `BLUETOOTH_CONNECT` is asked for when the user ticks the Bluetooth switch. The box is driven
 * with `performClick()`, the path a tap takes; `callChangeListener(...)` neither ticks nor
 * persists anything.
 */
@RunWith(RobolectricTestRunner::class)
class GeneralSettingsBluetoothTest {
    private lateinit var app: Application
    private lateinit var activity: ThemedActivity
    private lateinit var fragment: GeneralSettingsFragment
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Preferences survive between test methods in one JVM, and a stored value shadows the
        // XML default.
        settings = Settings.getInstance(app)
        ShadowToast.reset()
    }

    private fun open() {
        activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        fragment = GeneralSettingsFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
    }

    /** The default is on; ticking the box is the gesture that asks for the permission. */
    private fun openSwitchedOff() {
        settings.isBluetoothScoEnabled = false
        open()
    }

    private fun checkBox(): CheckBoxPreference {
        val preference = fragment.preferenceScreen
            .findPreference<Preference>(Settings.BLUETOOTH_SCO.key)
        assertWithMessage(
            "no preference with key '%s' on the general settings screen",
            Settings.BLUETOOTH_SCO.key,
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
        open()
        val category = fragment.preferenceScreen.findPreference<Preference>("controls_settings")
        assertWithMessage("no PreferenceCategory with key 'controls_settings'")
            .that(category).isNotNull()
        assertThat(category).isInstanceOf(PreferenceCategory::class.java)
        assertThat((category as PreferenceGroup).findPreference<Preference>(Settings.BLUETOOTH_SCO.key))
            .isNotNull()

        // Two sources for one default: android:defaultValue on this screen, and
        // Settings.BLUETOOTH_SCO.default, which every other reader gets.
        assertThat(checkBox().isChecked).isEqualTo(Settings.BLUETOOTH_SCO.default)
        assertThat(settings.isBluetoothScoEnabled).isEqualTo(Settings.BLUETOOTH_SCO.default)
    }

    @Test
    fun theSummarySaysThatThePermissionIsNeeded() {
        open()
        // The box can be tapped and stay empty; only the text under it can explain that.
        val summary = checkBox().summary.toString().lowercase()

        assertWithMessage("the summary must name the permission the tick will ask for: %s", summary)
            .that(summary).containsMatch("""nearby devices""")
    }

    @Test
    fun tickingItWithoutThePermissionAsksForItAndLeavesTheBoxEmpty() {
        openSwitchedOff()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(lastRequestedPermissions()).contains(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(checkBox().isChecked).isFalse()
        assertThat(settings.isBluetoothScoEnabled).isFalse()
    }

    @Test
    fun tickingItWithThePermissionPersistsIt() {
        openSwitchedOff()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(checkBox().isChecked).isTrue()
        assertThat(settings.isBluetoothScoEnabled).isTrue()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun untickingItNeverAsksForThePermission() {
        openSwitchedOff()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(checkBox().isChecked).isFalse()
        assertThat(settings.isBluetoothScoEnabled).isFalse()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun grantingThePermissionAfterwardsTurnsItOnAndTicksTheBox() {
        openSwitchedOff()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()

        answerThePermissionDialog(granted = true)

        assertThat(settings.isBluetoothScoEnabled).isTrue()
        assertThat(checkBox().isChecked).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
    }

    /** A denial is not a "no": the preference is kept regardless. */
    @Test
    fun denyingThePermissionStillTurnsItOnAndSaysWhatItMayCost() {
        openSwitchedOff()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()

        answerThePermissionDialog(granted = false)

        assertThat(settings.isBluetoothScoEnabled).isTrue()
        assertThat(checkBox().isChecked).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.bluetooth_perm_denied))
    }
}
