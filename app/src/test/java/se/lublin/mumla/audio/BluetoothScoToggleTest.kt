package se.lublin.mumla.audio

import android.Manifest
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
            .putBoolean(Settings.BLUETOOTH_SCO.key, false).commit()
        settings = Settings.getInstance(app)
        toggle = BluetoothScoToggle(app, settings)
    }

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    private fun deny() = shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private class Case(
        val name: String,
        val initial: Boolean,
        val permitted: Boolean,
        val action: BluetoothScoToggle.() -> BluetoothScoToggle.Result,
        val result: BluetoothScoToggle.Result,
        val stored: Boolean,
    )

    /** request(enabled) in all four corners of (enabled, permitted); toggle() asks for the opposite. */
    @Test
    fun onlyTurningItOnNeedsThePermission() {
        val on: BluetoothScoToggle.() -> BluetoothScoToggle.Result = { request(enabled = true) }
        val off: BluetoothScoToggle.() -> BluetoothScoToggle.Result = { request(enabled = false) }
        val flip: BluetoothScoToggle.() -> BluetoothScoToggle.Result = { toggle() }
        val enabled = BluetoothScoToggle.Result.Enabled
        val disabled = BluetoothScoToggle.Result.Disabled
        val asks = BluetoothScoToggle.Result.PermissionNeeded
        val cases = listOf(
            Case("on, permitted", initial = false, permitted = true, on, enabled, stored = true),
            Case("on, not permitted", initial = false, permitted = false, on, asks, stored = false),
            Case("off, permitted", initial = true, permitted = true, off, disabled, stored = false),
            Case("off, not permitted", initial = true, permitted = false, off, disabled, stored = false),
            Case("toggle from off, permitted", initial = false, permitted = true, flip, enabled, stored = true),
            Case("toggle from off, not permitted", initial = false, permitted = false, flip, asks, stored = false),
            Case("toggle from on, not permitted", initial = true, permitted = false, flip, disabled, stored = false),
        )
        for (case in cases) {
            settings.isBluetoothScoEnabled = case.initial
            if (case.permitted) grant() else deny()

            val result = case.action(BluetoothScoToggle(app, settings))

            assertWithMessage(case.name).that(result).isEqualTo(case.result)
            assertWithMessage(case.name).that(settings.isBluetoothScoEnabled).isEqualTo(case.stored)
        }
    }

    @Test
    fun answeringTheDialogStoresTheWishThatRaisedIt() {
        deny()
        toggle.toggle()
        assertThat(settings.isBluetoothScoEnabled).isFalse()

        toggle.onPermissionAnswered()

        assertThat(settings.isBluetoothScoEnabled).isTrue()
    }

    @Test
    fun answeringTheDialogTwiceLeavesItOn() {
        // Idempotent: a result left pending by an earlier process death can arrive too.
        toggle.onPermissionAnswered()
        toggle.onPermissionAnswered()

        assertThat(settings.isBluetoothScoEnabled).isTrue()
    }

    @Test
    fun isEnabledReadsThePreferenceBothWays() {
        assertThat(toggle.isEnabled).isFalse()

        settings.isBluetoothScoEnabled = true

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
