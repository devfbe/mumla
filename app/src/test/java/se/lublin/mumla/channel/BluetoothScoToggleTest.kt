package se.lublin.mumla.channel

import android.Manifest
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.Settings

/**
 * The persisted "Bluetooth headset" wish, as opposed to the active SCO link owned by AudioRouter;
 * only the wish survives a disconnect. `request` reads two booleans, so all four corners are
 * tested, over a real `Settings` and Robolectric's package manager.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothScoToggleTest {
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var toggle: BluetoothScoToggle

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Switched off explicitly: the default is on.
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(Settings.PREF_BLUETOOTH_SCO, false).commit()
        settings = Settings.getInstance(app)
        toggle = BluetoothScoToggle(app, settings)
    }

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    private fun deny() = shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    // --- request(enabled): all four corners of (enabled, permitted) ---

    @Test
    fun requestingOnWithPermissionPersistsTrue() {
        grant()

        val result = toggle.request(enabled = true)

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Enabled)
        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun requestingOnWithoutPermissionPersistsNothing() {
        deny()

        val result = toggle.request(enabled = true)

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.PermissionNeeded)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun requestingOffWithPermissionPersistsFalse() {
        settings.setBluetoothScoEnabled(true)
        grant()

        val result = toggle.request(enabled = false)

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Disabled)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun requestingOffWithoutPermissionPersistsFalse() {
        settings.setBluetoothScoEnabled(true)
        deny()

        val result = toggle.request(enabled = false)

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Disabled)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    // --- toggle(): reads the stored value and asks for its opposite ---

    @Test
    fun togglingFromOffWithPermissionTurnsItOn() {
        grant()

        val result = toggle.toggle()

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Enabled)
        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun togglingFromOffWithoutPermissionAsksAndDoesNotPersist() {
        deny()

        val result = toggle.toggle()

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.PermissionNeeded)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun togglingFromOnNeverNeedsPermission() {
        settings.setBluetoothScoEnabled(true)
        deny()

        val result = toggle.toggle()

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Disabled)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    // --- onPermissionAnswered() ---

    @Test
    fun answeringTheDialogStoresTheWishThatRaisedIt() {
        deny()
        toggle.toggle()
        assertThat(settings.isBluetoothScoEnabled()).isFalse()

        toggle.onPermissionAnswered()

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun answeringTheDialogTwiceLeavesItOn() {
        // Idempotent: a result left pending by an earlier process death can arrive too.
        toggle.onPermissionAnswered()
        toggle.onPermissionAnswered()

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    // --- isEnabled / hasPermission ---

    @Test
    fun isEnabledReadsThePreferenceBothWays() {
        assertThat(toggle.isEnabled).isFalse()

        settings.setBluetoothScoEnabled(true)

        assertThat(toggle.isEnabled).isTrue()
    }

    @Test
    fun hasPermissionReflectsGrantState() {
        deny()
        assertThat(BluetoothScoToggle.hasPermission(app)).isFalse()

        grant()
        assertThat(BluetoothScoToggle.hasPermission(app)).isTrue()
    }
}
