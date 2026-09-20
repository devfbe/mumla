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
 * Both inputs this file branches on are swept over their full space rather than sampled:
 * `request` and `shouldRouteToBluetooth` each read two booleans, so each gets four corners
 * (spec 4.04: a clause sweep says nothing about the operator joining the clauses -- 2^k inputs,
 * not k mutations). Neither input comes from a hand-written fake: the preference is a real
 * `Settings` over Robolectric's SharedPreferences and the grant state is Robolectric's own
 * package manager, and every test writes both of them.
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

    // --- onPermissionResult(granted) ---

    @Test
    fun aGrantedResultTurnsItOn() {
        deny()
        toggle.toggle()

        val enabled = toggle.onPermissionResult(granted = true)

        assertThat(enabled).isTrue()
        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun aDeniedResultLeavesItOff() {
        deny()
        toggle.toggle()

        val enabled = toggle.onPermissionResult(granted = false)

        assertThat(enabled).isFalse()
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun aDeniedResultDoesNotClearAWishThatIsAlreadyStored() {
        // The answer is "the new enabled state", not "was it granted": a denial must not reach
        // into the preference and switch off something the user asked for. Without this corner
        // `return settings.isBluetoothScoEnabled()` and `return granted` are indistinguishable,
        // and so are `if (granted) set(true)` and `set(granted)`.
        settings.setBluetoothScoEnabled(true)

        val enabled = toggle.onPermissionResult(granted = false)

        assertThat(enabled).isTrue()
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

    // --- shouldRouteToBluetooth: all four corners of (wanted, permitted) ---
    //
    // This is the decision MumlaService makes on every (re)connection and on every change of the
    // preference while connected. The service hook has no logic of its own, so this is where the
    // "Bluetooth survives a reconnect" behaviour of spec section 6 is pinned.

    @Test
    fun routesWhenWantedAndPermitted() {
        settings.setBluetoothScoEnabled(true)
        grant()

        assertThat(BluetoothScoToggle.shouldRouteToBluetooth(app, settings)).isTrue()
    }

    @Test
    fun doesNotRouteWhenThePermissionWasRevoked() {
        settings.setBluetoothScoEnabled(true)
        deny()

        assertThat(BluetoothScoToggle.shouldRouteToBluetooth(app, settings)).isFalse()
    }

    @Test
    fun doesNotRouteWhenTheWishIsOffEvenThoughItCould() {
        grant()

        assertThat(BluetoothScoToggle.shouldRouteToBluetooth(app, settings)).isFalse()
    }

    @Test
    fun doesNotRouteWhenNeitherHolds() {
        deny()

        assertThat(BluetoothScoToggle.shouldRouteToBluetooth(app, settings)).isFalse()
    }
}
