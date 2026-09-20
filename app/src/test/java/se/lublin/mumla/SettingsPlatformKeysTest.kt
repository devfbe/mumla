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
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        settings = Settings.getInstance(context)
    }

    @Test
    fun bluetoothScoIsOffByDefault() {
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun bluetoothScoIsPersistedUnderTheSpecKey() {
        settings.setBluetoothScoEnabled(true)

        val raw = PreferenceManager.getDefaultSharedPreferences(context)
        assertThat(raw.getBoolean("pref_bluetooth_sco", false)).isTrue()
        assertThat(Settings.getInstance(context).isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun mediaButtonActionDefaultsToAuto() {
        assertThat(settings.getMediaButtonAction()).isEqualTo(MediaButtonAction.AUTO)
    }

    @Test
    fun mediaButtonActionReadsStoredValue() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("media_button_action", "mute").commit()

        assertThat(settings.getMediaButtonAction()).isEqualTo(MediaButtonAction.MUTE)
    }

    @Test
    fun mediaButtonActionFallsBackToAutoForUnknownValue() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("media_button_action", "bogus").commit()

        assertThat(settings.getMediaButtonAction()).isEqualTo(MediaButtonAction.AUTO)
    }

    @Test
    fun batteryOptimizationAskedDefaultsToFalseAndPersists() {
        assertThat(settings.isBatteryOptimizationAsked()).isFalse()

        settings.setBatteryOptimizationAsked(true)

        assertThat(Settings.getInstance(context).isBatteryOptimizationAsked()).isTrue()
    }
}
