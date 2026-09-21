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
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.AdaptiveVadTracker
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode

@RunWith(RobolectricTestRunner::class)
class SettingsAudioTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val settings get() = Settings.getInstance(context)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    // --- noise suppression: one key, because two would drift apart --------------------------

    @Test
    fun `noise suppression is read from the one key the quick menu also writes`() {
        assertThat(settings.getNoiseSuppressionMode()).isEqualTo(NoiseSuppressionMode.RNNOISE)
        prefs.edit().putString(Settings.PREF_NOISE_SUPPRESSION_METHOD, "speex").commit()
        assertThat(settings.getNoiseSuppressionMode()).isEqualTo(NoiseSuppressionMode.SPEEX)
        prefs.edit().putString(Settings.PREF_NOISE_SUPPRESSION_METHOD, "none").commit()
        assertThat(settings.getNoiseSuppressionMode()).isEqualTo(NoiseSuppressionMode.NONE)
    }

    @Test
    fun `an installation that had switched the old preprocessor off keeps it off`() {
        prefs.edit().putBoolean(Settings.PREF_PREPROCESSOR_ENABLED, false).commit()
        assertThat(settings.getNoiseSuppressionMode()).isEqualTo(NoiseSuppressionMode.NONE)
        prefs.edit().putString(Settings.PREF_NOISE_SUPPRESSION_METHOD, "rnnoise").commit()
        assertThat(settings.getNoiseSuppressionMode()).isEqualTo(NoiseSuppressionMode.RNNOISE)
    }

    /**
     * The defect the quick-access menu shipped with: one tap wrote two keys, each of which is a
     * `configureExtras` of its own, and the audio chain was rebuilt twice -- measured 93 ms apart.
     * The write is now one key, so the second rebuild cannot exist rather than being debounced.
     */
    @Test
    fun `setting the noise suppression method writes exactly one key`() {
        val written = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> written += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            settings.setNoiseSuppressionMethod("speex")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        assertThat(written).containsExactly(Settings.PREF_NOISE_SUPPRESSION_METHOD)
    }

    @Test
    fun `speex noise suppression defaults to -25 dB and only accepts the supported steps`() {
        assertThat(settings.getSpeexNoiseSuppressDb()).isEqualTo(-25)
        prefs.edit().putString(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB, "-35").commit()
        assertThat(settings.getSpeexNoiseSuppressDb()).isEqualTo(-35)
        prefs.edit().putString(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB, "-15").commit()
        assertThat(settings.getSpeexNoiseSuppressDb()).isEqualTo(-15)
        prefs.edit().putString(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB, "-20").commit()
        assertThat(settings.getSpeexNoiseSuppressDb()).isEqualTo(-25)
        prefs.edit().putString(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB, "loud").commit()
        assertThat(settings.getSpeexNoiseSuppressDb()).isEqualTo(-25)
    }

    @Test
    fun `echo cancellation mode parses the stored method`() {
        assertThat(settings.getEchoCancellationMode()).isEqualTo(EchoCancellationMode.NONE)
        prefs.edit().putString(Settings.PREF_ECHO_CANCELLATION_METHOD, "system").commit()
        assertThat(settings.getEchoCancellationMode()).isEqualTo(EchoCancellationMode.ANDROID)
        prefs.edit().putString(Settings.PREF_ECHO_CANCELLATION_METHOD, "webrtc").commit()
        assertThat(settings.getEchoCancellationMode()).isEqualTo(EchoCancellationMode.WEBRTC)
    }

    // --- the voice gate ----------------------------------------------------------------------

    @Test
    fun `the adaptive gate is the default and reproduces the demand the fixed window made`() {
        assertThat(settings.getVadMode()).isEqualTo(VadMode.ADAPTIVE)
        val config = settings.getVadConfig()
        assertThat(config.mode).isEqualTo(VadMode.ADAPTIVE)
        assertThat(config.snrFraction).isWithin(0.001f).of(AdaptiveVadTracker.DEFAULT_FRACTION)
        assertThat(config.snrFraction * AdaptiveVadTracker.DEFAULT_GAP_DB).isWithin(0.01f).of(13.0f)
        assertThat(config.holdTimeMs).isEqualTo(250L)
        assertThat(config.adaptiveFloor).isTrue()
    }

    /**
     * The library keeps today's behaviour as its default so that no other caller changes silently;
     * the user-facing default is here, next to the screen that explains it.
     */
    @Test
    fun `the transient guard is on by default in the app and off by default in the library`() {
        assertThat(settings.getVadConfig().onsetFrames).isEqualTo(2)
        assertThat(VadConfig.DEFAULT_ONSET_FRAMES).isEqualTo(1)
    }

    @Test
    fun `the sensitivity slider is read as a fraction of the measured gap`() {
        prefs.edit().putInt(Settings.PREF_VAD_SENSITIVITY, 30).commit()
        assertThat(settings.getVadConfig().snrFraction).isWithin(0.001f).of(0.3f)
        prefs.edit().putInt(Settings.PREF_VAD_SENSITIVITY, 140).commit()
        assertThat(settings.getVadConfig().snrFraction).isWithin(0.001f).of(1f)
        prefs.edit().putInt(Settings.PREF_VAD_SENSITIVITY, -20).commit()
        assertThat(settings.getVadConfig().snrFraction).isWithin(0.001f).of(0f)
    }

    @Test
    fun `the hand-set floor is stored as dB below full scale and read back negative`() {
        prefs.edit()
            .putBoolean(Settings.PREF_VAD_ADAPTIVE_FLOOR, false)
            .putInt(Settings.PREF_VAD_FLOOR_DB, 60)
            .commit()
        val config = settings.getVadConfig()
        assertThat(config.adaptiveFloor).isFalse()
        assertThat(config.manualFloorDbfs).isEqualTo(-60f)
    }

    @Test
    fun `a hand-set floor outside what a microphone can produce is clamped, not thrown`() {
        prefs.edit().putBoolean(Settings.PREF_VAD_ADAPTIVE_FLOOR, false).putInt(Settings.PREF_VAD_FLOOR_DB, 5).commit()
        assertThat(settings.getVadConfig().manualFloorDbfs).isEqualTo(AdaptiveVadTracker.MAX_FLOOR_DBFS)
        prefs.edit().putInt(Settings.PREF_VAD_FLOOR_DB, 400).commit()
        assertThat(settings.getVadConfig().manualFloorDbfs).isEqualTo(AdaptiveVadTracker.MIN_FLOOR_DBFS)
    }

    @Test
    fun `amplitude mode is still driven by the legacy threshold slider`() {
        prefs.edit()
            .putString(Settings.PREF_VAD_MODE, "amplitude")
            .putInt(Settings.PREF_THRESHOLD, 70)
            .putInt(Settings.PREF_VAD_HOLD_MS, 400)
            .commit()
        assertThat(settings.getVadConfig())
            .isEqualTo(VadConfig.amplitude(0.7f, 400, Settings.DEFAULT_VAD_ONSET_FRAMES))
    }

    @Test
    fun `probability sliders are read in percent and stop never exceeds start`() {
        prefs.edit()
            .putString(Settings.PREF_VAD_MODE, "probability")
            .putInt(Settings.PREF_VAD_START, 40)
            .putInt(Settings.PREF_VAD_STOP, 55)
            .putInt(Settings.PREF_VAD_HOLD_MS, 100)
            .commit()
        val config = settings.getVadConfig()
        assertThat(config.startThreshold).isWithin(0.001f).of(0.4f)
        assertThat(config.stopThreshold).isWithin(0.001f).of(0.4f)
        assertThat(config.holdTimeMs).isEqualTo(100L)
    }

    @Test
    fun `a hold longer than the detector can convert is clamped rather than wrapping negative`() {
        prefs.edit().putInt(Settings.PREF_VAD_HOLD_MS, -50).commit()
        assertThat(settings.getVadConfig().holdTimeMs).isEqualTo(0L)
    }

    @Test
    fun `an onset of zero frames would be a gate that never opens and is clamped away`() {
        prefs.edit().putString(Settings.PREF_VAD_ONSET_FRAMES, "0").commit()
        assertThat(settings.getVadConfig().onsetFrames).isEqualTo(1)
        prefs.edit().putString(Settings.PREF_VAD_ONSET_FRAMES, "99").commit()
        assertThat(settings.getVadConfig().onsetFrames).isEqualTo(Settings.MAX_VAD_ONSET_FRAMES)
        prefs.edit().putString(Settings.PREF_VAD_ONSET_FRAMES, "many").commit()
        assertThat(settings.getVadConfig().onsetFrames).isEqualTo(Settings.DEFAULT_VAD_ONSET_FRAMES)
    }

    @Test
    fun `an unknown stored mode falls back to the amplitude detector everyone has been running`() {
        prefs.edit().putString(Settings.PREF_VAD_MODE, "telepathy").commit()
        assertThat(settings.getVadMode()).isEqualTo(VadMode.AMPLITUDE)
    }

    // --- the platform audio effects ------------------------------------------------------------

    @Test
    fun `android effects default off and read their toggles`() {
        assertThat(settings.getAndroidAudioEffects().noiseSuppressor).isFalse()
        assertThat(settings.getAndroidAudioEffects().automaticGainControl).isFalse()
        prefs.edit()
            .putBoolean(Settings.PREF_ANDROID_NOISE_SUPPRESSOR, true)
            .putBoolean(Settings.PREF_ANDROID_AGC, true)
            .commit()
        assertThat(settings.getAndroidAudioEffects().noiseSuppressor).isTrue()
        assertThat(settings.getAndroidAudioEffects().automaticGainControl).isTrue()
    }

    /** Four corners over two booleans, because one `||` between them would pass three of them. */
    @Test
    fun `each android effect toggle reaches its own field`() {
        for (ns in listOf(false, true)) {
            for (agc in listOf(false, true)) {
                prefs.edit()
                    .putBoolean(Settings.PREF_ANDROID_NOISE_SUPPRESSOR, ns)
                    .putBoolean(Settings.PREF_ANDROID_AGC, agc)
                    .commit()
                val effects = settings.getAndroidAudioEffects()
                assertThat(effects.noiseSuppressor).isEqualTo(ns)
                assertThat(effects.automaticGainControl).isEqualTo(agc)
            }
        }
    }
}
