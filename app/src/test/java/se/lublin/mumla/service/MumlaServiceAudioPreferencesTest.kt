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

package se.lublin.mumla.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.testutil.testActivityInputMode
import se.lublin.humla.testutil.testRouter
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.createMumlaService

/**
 * For every switch on the audio settings screen, reads the result back off the object the audio
 * threads use.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceAudioPreferencesTest {
    private lateinit var service: MumlaService
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        service = createMumlaService().get()
    }

    private fun pipeline() = service.getAudioConfigForTest().settings

    private fun vadConfig(): VadConfig = service.testActivityInputMode.vadConfig

    private fun change(key: String) = service.onPreferenceChanged(key)

    @Test
    fun `every voice gate preference reaches the running detector`() {
        val writes: Map<String, SharedPreferences.Editor.() -> Unit> = mapOf(
            Settings.VAD_MODE.key to { putString(Settings.VAD_MODE.key, "adaptive") },
            Settings.VAD_SENSITIVITY.key to { putInt(Settings.VAD_SENSITIVITY.key, 31) },
            Settings.VAD_HOLD_MS.key to { putInt(Settings.VAD_HOLD_MS.key, 410) },
            Settings.VAD_ONSET_FRAMES.key to { putString(Settings.VAD_ONSET_FRAMES.key, "4") },
            Settings.VAD_ADAPTIVE_FLOOR.key to { putBoolean(Settings.VAD_ADAPTIVE_FLOOR.key, false) },
            Settings.VAD_FLOOR_DB.key to { putInt(Settings.VAD_FLOOR_DB.key, 61) },
        )
        for ((key, write) in writes) {
            prefs.edit().apply(write).commit()
            change(key)
            assertThat(vadConfig()).isEqualTo(Settings.getInstance(service).vadConfig)
        }
        // Read back the values themselves: an accessor returning a constant would pass the above.
        val config = vadConfig()
        assertThat(config.snrFraction).isWithin(0.001f).of(0.31f)
        assertThat(config.holdTimeMs).isEqualTo(410L)
        assertThat(config.onsetFrames).isEqualTo(4)
        assertThat(config.adaptiveFloor).isFalse()
        assertThat(config.manualFloorDbfs).isEqualTo(-61f)
    }

    @Test
    fun `the legacy threshold slider still reaches the detector in amplitude mode`() {
        prefs.edit().putString(Settings.VAD_MODE.key, "amplitude").putInt(Settings.THRESHOLD.key, 81).commit()
        change(Settings.THRESHOLD.key)
        assertThat(vadConfig().startThreshold).isWithin(0.001f).of(0.81f)
    }

    @Test
    fun `the probability sliders reach the detector in probability mode`() {
        prefs.edit()
            .putString(Settings.VAD_MODE.key, "probability")
            .putInt(Settings.VAD_START.key, 77)
            .putInt(Settings.VAD_STOP.key, 22)
            .commit()
        change(Settings.VAD_START.key)
        assertThat(vadConfig().startThreshold).isWithin(0.001f).of(0.77f)
        assertThat(vadConfig().stopThreshold).isWithin(0.001f).of(0.22f)
    }

    @Test
    fun `the noise suppression method reaches the audio config`() {
        prefs.edit().putString(Settings.NOISE_SUPPRESSION_METHOD.key, "speex").commit()
        change(Settings.NOISE_SUPPRESSION_METHOD.key)
        assertThat(pipeline().noiseSuppression).isEqualTo(NoiseSuppressionMode.SPEEX)
    }

    @Test
    fun `the speex suppression depth reaches the audio config`() {
        prefs.edit().putString(Settings.SPEEX_NOISE_SUPPRESS_DB.key, "-35").commit()
        change(Settings.SPEEX_NOISE_SUPPRESS_DB.key)
        assertThat(pipeline().speexNoiseSuppressDb).isEqualTo(-35)
    }

    /** The device saved in the chooser reaches the router, connected or not. */
    @Test
    fun `the saved audio device reaches the router`() {
        val router = service.testRouter
        val earpiece = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        Settings.getInstance(service).preferredAudioDevice = earpiece
        change(Settings.AUDIO_DEVICE.key)
        assertThat(router.preferred).isEqualTo(earpiece)

        Settings.getInstance(service).preferredAudioDevice = null
        change(Settings.AUDIO_DEVICE.key)
        assertThat(router.preferred).isNull()
    }

    /**
     * The chooser's echo switch writes a per-device override; the service has to hold all of them,
     * so the next device of that kind gets it too. Read off the map the route decision uses.
     */
    @Test
    fun `an echo cancellation override reaches the service`() {
        Settings.getInstance(service).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, false)
        change(Settings.echoCancellationKey(AudioDeviceCategory.SPEAKER))
        assertThat(service.sessionConfig.echoCancellationOverrides)
            .isEqualTo(mapOf(AudioDeviceCategory.SPEAKER to false))

        Settings.getInstance(service).setEchoCancellationOverride(AudioDeviceCategory.EARPIECE, false)
        change(Settings.echoCancellationKey(AudioDeviceCategory.EARPIECE))
        assertThat(service.sessionConfig.echoCancellationOverrides).isEqualTo(
            mapOf(AudioDeviceCategory.SPEAKER to false, AudioDeviceCategory.EARPIECE to false),
        )
    }

    /** Four corners over two booleans: one `||` between them would pass three of the four. */
    @Test
    fun `each android audio effect toggle reaches its own config field`() {
        for (ns in listOf(false, true)) {
            for (agc in listOf(false, true)) {
                prefs.edit()
                    .putBoolean(Settings.ANDROID_NOISE_SUPPRESSOR.key, ns)
                    .putBoolean(Settings.ANDROID_AGC.key, agc)
                    .commit()
                change(Settings.ANDROID_NOISE_SUPPRESSOR.key)
                change(Settings.ANDROID_AGC.key)
                assertThat(pipeline().androidEffects).isEqualTo(AndroidAudioEffects(ns, agc))
            }
        }
    }

    /**
     * Every `android:key` in the audio settings XML is either turned into an extra by
     * [SessionSettings] or exempted here with a reason, so a new switch that reaches nothing
     * fails this test.
     */
    @Test
    fun `every key on the audio settings screen is either wired or exempt with a reason`() {
        val exempt = mapOf(
            "vad_settings" to "a PreferenceCategory, not a setting",
            "input_level_meter" to "not persisted: the settings screen's own live meter",
            "audio_loopback_test" to "not persisted: the settings screen's own monitor switch",
            "audio_test_microphone" to "not persisted: the settings screen's own meter switch",
            "vad_recalibrate" to "not persisted: restarts the settings screen's own measurement",
            "ptt_settings" to "a PreferenceCategory, not a setting",
            "talkKey" to "read by the overlay and the PTT button, not by the audio chain",
            "hotCorner" to "read by MumlaService's hot corner, in its own case",
            "hidePtt" to "read by the channel fragment when it builds the PTT button",
            "togglePtt" to "read by the PTT button when it handles a press",
            "allow_external_ptt" to "read by the talk broadcast receiver on each broadcast",
            "ptt_sound" to "read by MumlaService's own field, in its own case",
        )

        val keys = mutableSetOf<String>()
        val parser = service.resources.getXml(R.xml.settings_audio)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            keys += parser.getAttributeValue(ANDROID_NS, "key") ?: continue
        }

        assertThat(keys.filterNot { it in SessionSettings.AUDIO_KEYS || it in exempt }).isEmpty()
        // The exemptions cannot rot: each one has to be a key the screen really still has.
        assertThat(keys).containsAtLeastElementsIn(exempt.keys)
    }

    /** An audio key reapplies every audio setting at once; any other key leaves the config alone. */
    @Test
    fun `only an audio key reconfigures the session`() {
        val settings = Settings.getInstance(service)
        prefs.edit().putBoolean(Settings.HALF_DUPLEX.key, true).commit()
        val before = service.sessionConfig

        for (key in listOf(Settings.USE_TTS.key, Settings.HOT_CORNER.key, Settings.PTT_SOUND.key, "nonsense")) {
            change(key)
            assertThat(service.sessionConfig).isSameInstanceAs(before)
        }
        change(Settings.HALF_DUPLEX.key)
        assertThat(service.sessionConfig).isEqualTo(SessionSettings.withAudioSettings(before, settings))
        assertThat(service.sessionConfig.halfDuplex).isTrue()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
