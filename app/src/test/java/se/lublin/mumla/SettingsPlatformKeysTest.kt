package se.lublin.mumla

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsPlatformKeysTest {
    private lateinit var context: Context
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = Settings.getInstance(context)
    }

    /** A connected Bluetooth headset is used without being asked for, as in the phone app. */
    @Test
    fun bluetoothScoIsOnByDefault() {
        assertThat(settings.isBluetoothScoEnabled).isTrue()
    }

    @Test
    fun bluetoothScoIsPersistedUnderTheSpecKey() {
        settings.isBluetoothScoEnabled = false

        val raw = PreferenceManager.getDefaultSharedPreferences(context)
        assertThat(raw.getBoolean("pref_bluetooth_sco", true)).isFalse()
        assertThat(Settings.getInstance(context).isBluetoothScoEnabled).isFalse()
    }

    @Test
    fun mediaButtonActionDefaultsToAuto() {
        assertThat(settings.mediaButtonAction).isEqualTo(MediaButtonAction.AUTO)
    }

    @Test
    fun mediaButtonActionReadsStoredValue() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("media_button_action", "mute").commit()

        assertThat(settings.mediaButtonAction).isEqualTo(MediaButtonAction.MUTE)
    }

    @Test
    fun mediaButtonActionFallsBackToAutoForUnknownValue() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("media_button_action", "bogus").commit()

        assertThat(settings.mediaButtonAction).isEqualTo(MediaButtonAction.AUTO)
    }

    @Test
    fun batteryOptimizationAskedDefaultsToFalseAndPersists() {
        assertThat(settings.isBatteryOptimizationAsked).isFalse()

        settings.isBatteryOptimizationAsked = true

        assertThat(Settings.getInstance(context).isBatteryOptimizationAsked).isTrue()
    }
}
