/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice

@RunWith(RobolectricTestRunner::class)
class SettingsAudioTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val settings get() = Settings.getInstance(context)

    // --- noise suppression: one key, because two would drift apart --------------------------

    @Test
    fun `noise suppression is read from the one key the quick menu also writes`() {
        assertThat(settings.noiseSuppressionMode).isEqualTo(NoiseSuppressionMode.RNNOISE)
        prefs.edit().putString(Settings.NOISE_SUPPRESSION_METHOD.key, "speex").commit()
        assertThat(settings.noiseSuppressionMode).isEqualTo(NoiseSuppressionMode.SPEEX)
        prefs.edit().putString(Settings.NOISE_SUPPRESSION_METHOD.key, "none").commit()
        assertThat(settings.noiseSuppressionMode).isEqualTo(NoiseSuppressionMode.NONE)
    }

    @Test
    fun `an installation that had switched the old preprocessor off keeps it off`() {
        prefs.edit().putBoolean(Settings.PREPROCESSOR_ENABLED.key, false).commit()
        assertThat(settings.noiseSuppressionMode).isEqualTo(NoiseSuppressionMode.NONE)
        prefs.edit().putString(Settings.NOISE_SUPPRESSION_METHOD.key, "rnnoise").commit()
        assertThat(settings.noiseSuppressionMode).isEqualTo(NoiseSuppressionMode.RNNOISE)
    }

    /** One tap writes one key, so the audio chain is rebuilt once, not twice. */
    @Test
    fun `setting the noise suppression method writes exactly one key`() {
        val written = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> written += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            settings.noiseSuppressionMethod = "speex"
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        assertThat(written).containsExactly(Settings.NOISE_SUPPRESSION_METHOD.key)
    }

    @Test
    fun `speex noise suppression defaults to -25 dB and only accepts the supported steps`() {
        assertThat(settings.speexNoiseSuppressDb).isEqualTo(-25)
        prefs.edit().putString(Settings.SPEEX_NOISE_SUPPRESS_DB.key, "-35").commit()
        assertThat(settings.speexNoiseSuppressDb).isEqualTo(-35)
        prefs.edit().putString(Settings.SPEEX_NOISE_SUPPRESS_DB.key, "-15").commit()
        assertThat(settings.speexNoiseSuppressDb).isEqualTo(-15)
        prefs.edit().putString(Settings.SPEEX_NOISE_SUPPRESS_DB.key, "-20").commit()
        assertThat(settings.speexNoiseSuppressDb).isEqualTo(-25)
        prefs.edit().putString(Settings.SPEEX_NOISE_SUPPRESS_DB.key, "loud").commit()
        assertThat(settings.speexNoiseSuppressDb).isEqualTo(-25)
    }

    @Test
    fun `echo cancellation follows the kind of device until the user overrides it`() {
        assertThat(settings.isEchoCancellationEnabled(AudioDeviceCategory.SPEAKER)).isTrue()
        assertThat(settings.isEchoCancellationEnabled(AudioDeviceCategory.EARPIECE)).isTrue()
        assertThat(settings.isEchoCancellationEnabled(AudioDeviceCategory.BLUETOOTH)).isFalse()
        assertThat(settings.isEchoCancellationEnabled(AudioDeviceCategory.WIRED)).isFalse()
        assertThat(settings.echoCancellationOverrides).isEmpty()
    }

    /** One override per kind of device, remembered, and none of the others touched. */
    @Test
    fun `an echo cancellation override is kept for its kind of device only`() {
        settings.setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, false)
        settings.setEchoCancellationOverride(AudioDeviceCategory.BLUETOOTH, true)

        val reread = Settings.getInstance(ApplicationProvider.getApplicationContext())
        assertThat(reread.isEchoCancellationEnabled(AudioDeviceCategory.SPEAKER)).isFalse()
        assertThat(reread.isEchoCancellationEnabled(AudioDeviceCategory.BLUETOOTH)).isTrue()
        assertThat(reread.isEchoCancellationEnabled(AudioDeviceCategory.EARPIECE)).isTrue()
        assertThat(reread.echoCancellationOverrides).containsExactly(
            AudioDeviceCategory.SPEAKER, false,
            AudioDeviceCategory.BLUETOOTH, true,
        )
        assertThat(Settings.ECHO_CANCELLATION_KEYS)
            .contains(Settings.echoCancellationKey(AudioDeviceCategory.SPEAKER))
    }

    @Test
    fun `no audio device is saved until the user picks one`() {
        assertThat(settings.preferredAudioDevice).isNull()
    }

    @Test
    fun `a saved audio device reads back with its address`() {
        val headset = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "00:11:22:33:44:55")

        settings.preferredAudioDevice = headset

        assertThat(Settings.getInstance(context).preferredAudioDevice).isEqualTo(headset)
    }

    @Test
    fun `a saved audio device without an address reads back without one`() {
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        assertThat(settings.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    /** Automatic is the absence of a saved device, not a device of its own. */
    @Test
    fun `saving automatic removes the saved device`() {
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        settings.preferredAudioDevice = null

        assertThat(prefs.contains(Settings.AUDIO_DEVICE.key)).isFalse()
        assertThat(settings.preferredAudioDevice).isNull()
    }

    @Test
    fun `a garbled saved audio device reads as automatic`() {
        prefs.edit().putString(Settings.AUDIO_DEVICE.key, "speaker").commit()

        assertThat(settings.preferredAudioDevice).isNull()
    }

    /** The old "output without a headset" becomes the saved device, once. */
    @Test
    fun `the earpiece as default output becomes the saved earpiece`() {
        prefs.edit().putString("default_output", "earpiece").commit()

        val migrated = Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(migrated.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertThat(prefs.contains("default_output")).isFalse()
    }

    /** The speaker was the automatic default all along, so nothing is saved for it. */
    @Test
    fun `the speaker as default output becomes automatic`() {
        prefs.edit().putString("default_output", "speaker").commit()

        val migrated = Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(migrated.preferredAudioDevice).isNull()
        assertThat(prefs.contains("default_output")).isFalse()
    }

    /** A device the user saved already is theirs; a stale default output does not win. */
    @Test
    fun `a saved audio device is not overwritten by the old default output`() {
        prefs.edit()
            .putString("default_output", "earpiece")
            .putString(Settings.AUDIO_DEVICE.key, "${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}")
            .commit()

        val migrated = Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(migrated.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    /** Whoever had the handset mode on gets the earpiece saved, once. */
    @Test
    fun `handset mode becomes the saved earpiece`() {
        prefs.edit().putBoolean("handset_mode", true).commit()

        val migrated = Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(migrated.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertThat(prefs.contains("handset_mode")).isFalse()
    }

    @Test
    fun `handset mode switched off leaves automatic and is removed`() {
        prefs.edit().putBoolean("handset_mode", false).commit()

        val migrated = Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(migrated.preferredAudioDevice).isNull()
        assertThat(prefs.contains("handset_mode")).isFalse()
    }

    /** A default output the user chose after the handset mode is theirs; the stale flag does not win. */
    @Test
    fun `a chosen default output is not overwritten by the old handset flag`() {
        prefs.edit()
            .putBoolean("handset_mode", true)
            .putString("default_output", "speaker")
            .commit()

        val migrated = Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(migrated.preferredAudioDevice).isNull()
        assertThat(prefs.contains("handset_mode")).isFalse()
    }

    /** The obsolete global echo cancellation value is removed from disk, once. */
    @Test
    fun `the old echo cancellation method is removed from the preferences`() {
        prefs.edit().putString("echo_cancellation_method", "system").commit()

        Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(prefs.contains("echo_cancellation_method")).isFalse()
    }

    /** Opus is always offered now; a stored "avoid Opus" choice is dropped rather than left behind. */
    @Test
    fun `the old avoid-opus choice is removed from the preferences`() {
        prefs.edit().putBoolean("disableOpus", true).commit()

        Settings.getInstance(ApplicationProvider.getApplicationContext())

        assertThat(prefs.contains("disableOpus")).isFalse()
    }

    @Test
    fun `the adaptive gate is the default and reproduces the demand the fixed window made`() {
        assertThat(settings.vadMode).isEqualTo(VadMode.ADAPTIVE)
        val config = settings.vadConfig
        assertThat(config.mode).isEqualTo(VadMode.ADAPTIVE)
        assertThat(config.snrFraction).isWithin(0.001f).of(VadConfig.DEFAULT_SNR_FRACTION)
        assertThat(config.holdTimeMs).isEqualTo(250L)
        assertThat(config.adaptiveFloor).isTrue()
    }

    /**
     * The library keeps today's behaviour as its default so that no other caller changes silently;
     * the user-facing default is here, next to the screen that explains it.
     */
    @Test
    fun `the transient guard is on by default in the app and off by default in the library`() {
        assertThat(settings.vadConfig.onsetFrames).isEqualTo(2)
        assertThat(VadConfig.DEFAULT_ONSET_FRAMES).isEqualTo(1)
    }

    @Test
    fun `the sensitivity slider is read as a fraction of the measured gap`() {
        prefs.edit().putInt(Settings.VAD_SENSITIVITY.key, 30).commit()
        assertThat(settings.vadConfig.snrFraction).isWithin(0.001f).of(0.3f)
        prefs.edit().putInt(Settings.VAD_SENSITIVITY.key, 140).commit()
        assertThat(settings.vadConfig.snrFraction).isWithin(0.001f).of(1f)
        prefs.edit().putInt(Settings.VAD_SENSITIVITY.key, -20).commit()
        assertThat(settings.vadConfig.snrFraction).isWithin(0.001f).of(0f)
    }

    @Test
    fun `the hand-set floor is stored as dB below full scale and read back negative`() {
        prefs.edit()
            .putBoolean(Settings.VAD_ADAPTIVE_FLOOR.key, false)
            .putInt(Settings.VAD_FLOOR_DB.key, 60)
            .commit()
        val config = settings.vadConfig
        assertThat(config.adaptiveFloor).isFalse()
        assertThat(config.manualFloorDbfs).isEqualTo(-60f)
    }

    @Test
    fun `a hand-set floor outside what a microphone can produce is clamped, not thrown`() {
        prefs.edit().putBoolean(Settings.VAD_ADAPTIVE_FLOOR.key, false).putInt(Settings.VAD_FLOOR_DB.key, 5).commit()
        assertThat(settings.vadConfig.manualFloorDbfs).isEqualTo(VadConfig.MAX_FLOOR_DBFS)
        prefs.edit().putInt(Settings.VAD_FLOOR_DB.key, 400).commit()
        assertThat(settings.vadConfig.manualFloorDbfs).isEqualTo(VadConfig.MIN_FLOOR_DBFS)
    }

    @Test
    fun `amplitude mode is still driven by the legacy threshold slider`() {
        prefs.edit()
            .putString(Settings.VAD_MODE.key, "amplitude")
            .putInt(Settings.THRESHOLD.key, 70)
            .putInt(Settings.VAD_HOLD_MS.key, 400)
            .commit()
        assertThat(settings.vadConfig)
            .isEqualTo(VadConfig.amplitude(0.7f, 400, Settings.VAD_ONSET_FRAMES.default))
    }

    @Test
    fun `probability sliders are read in percent and stop never exceeds start`() {
        prefs.edit()
            .putString(Settings.VAD_MODE.key, "probability")
            .putInt(Settings.VAD_START.key, 40)
            .putInt(Settings.VAD_STOP.key, 55)
            .putInt(Settings.VAD_HOLD_MS.key, 100)
            .commit()
        val config = settings.vadConfig
        assertThat(config.startThreshold).isWithin(0.001f).of(0.4f)
        assertThat(config.stopThreshold).isWithin(0.001f).of(0.4f)
        assertThat(config.holdTimeMs).isEqualTo(100L)
    }

    @Test
    fun `a hold longer than the detector can convert is clamped rather than wrapping negative`() {
        prefs.edit().putInt(Settings.VAD_HOLD_MS.key, -50).commit()
        assertThat(settings.vadConfig.holdTimeMs).isEqualTo(0L)
    }

    @Test
    fun `an onset of zero frames would be a gate that never opens and is clamped away`() {
        prefs.edit().putString(Settings.VAD_ONSET_FRAMES.key, "0").commit()
        assertThat(settings.vadConfig.onsetFrames).isEqualTo(1)
        prefs.edit().putString(Settings.VAD_ONSET_FRAMES.key, "99").commit()
        assertThat(settings.vadConfig.onsetFrames).isEqualTo(Settings.MAX_VAD_ONSET_FRAMES)
        prefs.edit().putString(Settings.VAD_ONSET_FRAMES.key, "many").commit()
        assertThat(settings.vadConfig.onsetFrames).isEqualTo(Settings.VAD_ONSET_FRAMES.default)
    }

    @Test
    fun `an unknown stored mode falls back to the amplitude detector everyone has been running`() {
        prefs.edit().putString(Settings.VAD_MODE.key, "telepathy").commit()
        assertThat(settings.vadMode).isEqualTo(VadMode.AMPLITUDE)
    }

    @Test
    fun `android effects default off and read their toggles`() {
        assertThat(settings.androidAudioEffects.noiseSuppressor).isFalse()
        assertThat(settings.androidAudioEffects.automaticGainControl).isFalse()
        prefs.edit()
            .putBoolean(Settings.ANDROID_NOISE_SUPPRESSOR.key, true)
            .putBoolean(Settings.ANDROID_AGC.key, true)
            .commit()
        assertThat(settings.androidAudioEffects.noiseSuppressor).isTrue()
        assertThat(settings.androidAudioEffects.automaticGainControl).isTrue()
    }

    /** Four corners over two booleans, because one `||` between them would pass three of them. */
    @Test
    fun `each android effect toggle reaches its own field`() {
        for (ns in listOf(false, true)) {
            for (agc in listOf(false, true)) {
                prefs.edit()
                    .putBoolean(Settings.ANDROID_NOISE_SUPPRESSOR.key, ns)
                    .putBoolean(Settings.ANDROID_AGC.key, agc)
                    .commit()
                val effects = settings.androidAudioEffects
                assertThat(effects.noiseSuppressor).isEqualTo(ns)
                assertThat(effects.automaticGainControl).isEqualTo(agc)
            }
        }
    }
}
