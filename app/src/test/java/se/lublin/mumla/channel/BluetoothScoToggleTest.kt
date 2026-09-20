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
 * The decision behind "Bluetooth headset": what the user *wants* (a persisted preference) as
 * opposed to what is *active* (an SCO link, which stream A's ScoRouter owns). Only the first
 * survives a disconnect -- `HumlaService.onConnectionDisconnected` stops SCO on every drop,
 * including the ones auto-reconnect recovers from, and nothing ever started it again. That is
 * the user's original complaint, and this class holds the half of the fix that is a setting.
 *
 * The one input this file branches on is swept over its full space rather than sampled:
 * `request` reads two booleans, so it gets four corners (spec 4.04: a clause sweep says nothing
 * about the operator joining the clauses -- 2^k inputs, not k mutations). Neither input comes
 * from a hand-written fake: the preference is a real `Settings` over Robolectric's
 * SharedPreferences and the grant state is Robolectric's own package manager, and every test
 * writes both of them.
 *
 * `shouldRouteToBluetooth` used to live here with four corners of its own, and is gone: under the
 * ruling in spec 4.1 the routing follows the wish alone, so the second dimension no longer
 * exists. It was deleted rather than pinned to `true` -- a gate that is always open is a gate
 * somebody writes back in (4.04: removing state beats adding a guard). What the service reads now
 * is `Settings.isBluetoothScoEnabled()`, pinned in `MumlaServiceBluetoothTest`.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothScoToggleTest {
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var toggle: BluetoothScoToggle

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
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
        // Idempotent, because the launcher can deliver a result the process death of an earlier
        // request left pending, on top of the one the current tap asked for.
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
