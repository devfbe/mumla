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
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.humla.HumlaService
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.humla.session.AudioRouter
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * The effect pass for the audio settings screen: for every switch it offers, the test that reads
 * the result back off the object the audio threads use.
 *
 * Without this the screen is a list of preferences that may or may not be connected to anything,
 * which is exactly the defect class this project keeps finding -- the media key that did nothing,
 * the AGC setting that never reached Speex, the preference whose default disagreed with its XML.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceAudioPreferencesTest {
    private lateinit var service: MumlaService
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        service = Robolectric.buildService(MumlaService::class.java).create().get()
    }

    private fun field(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("no field $name on ${target.javaClass}")
    }

    /**
     * Task A9b replaced the `AudioHandler.Builder` the service used to hold with an immutable
     * [se.lublin.humla.session.AudioConfig], so what a preference lands in is a config field. The
     * config-to-builder half moved with it and is pinned in `DefaultAudioHandlerFactoryTest`.
     */
    private fun audioConfig() = service.getAudioConfigForTest()

    private fun vadConfig(): VadConfig = (field(service, "mActivityInputMode") as ActivityInputMode).vadConfig

    private fun change(key: String) = service.onSharedPreferenceChanged(prefs, key)

    // --- the voice gate -----------------------------------------------------------------------

    @Test
    fun `every voice gate preference reaches the running detector`() {
        val writes: Map<String, SharedPreferences.Editor.() -> Unit> = mapOf(
            Settings.PREF_VAD_MODE to { putString(Settings.PREF_VAD_MODE, "adaptive") },
            Settings.PREF_VAD_SENSITIVITY to { putInt(Settings.PREF_VAD_SENSITIVITY, 31) },
            Settings.PREF_VAD_HOLD_MS to { putInt(Settings.PREF_VAD_HOLD_MS, 410) },
            Settings.PREF_VAD_ONSET_FRAMES to { putString(Settings.PREF_VAD_ONSET_FRAMES, "4") },
            Settings.PREF_VAD_ADAPTIVE_FLOOR to { putBoolean(Settings.PREF_VAD_ADAPTIVE_FLOOR, false) },
            Settings.PREF_VAD_FLOOR_DB to { putInt(Settings.PREF_VAD_FLOOR_DB, 61) },
        )
        for ((key, write) in writes) {
            prefs.edit().apply(write).commit()
            change(key)
            assertThat(vadConfig()).isEqualTo(Settings.getInstance(service).getVadConfig())
        }
        // Read back the values themselves, not only the equality with Settings: an accessor that
        // returned a constant would satisfy the line above on both sides.
        val config = vadConfig()
        assertThat(config.snrFraction).isWithin(0.001f).of(0.31f)
        assertThat(config.holdTimeMs).isEqualTo(410L)
        assertThat(config.onsetFrames).isEqualTo(4)
        assertThat(config.adaptiveFloor).isFalse()
        assertThat(config.manualFloorDbfs).isEqualTo(-61f)
    }

    @Test
    fun `the legacy threshold slider still reaches the detector in amplitude mode`() {
        prefs.edit().putString(Settings.PREF_VAD_MODE, "amplitude").putInt(Settings.PREF_THRESHOLD, 81).commit()
        change(Settings.PREF_THRESHOLD)
        assertThat(vadConfig().startThreshold).isWithin(0.001f).of(0.81f)
    }

    @Test
    fun `the probability sliders reach the detector in probability mode`() {
        prefs.edit()
            .putString(Settings.PREF_VAD_MODE, "probability")
            .putInt(Settings.PREF_VAD_START, 77)
            .putInt(Settings.PREF_VAD_STOP, 22)
            .commit()
        change(Settings.PREF_VAD_START)
        assertThat(vadConfig().startThreshold).isWithin(0.001f).of(0.77f)
        assertThat(vadConfig().stopThreshold).isWithin(0.001f).of(0.22f)
    }

    // --- the preprocessor chain ----------------------------------------------------------------

    @Test
    fun `the noise suppression method reaches the audio config`() {
        prefs.edit().putString(Settings.PREF_NOISE_SUPPRESSION_METHOD, "speex").commit()
        change(Settings.PREF_NOISE_SUPPRESSION_METHOD)
        assertThat(audioConfig().noiseSuppression).isEqualTo("speex")
    }

    @Test
    fun `the speex suppression depth reaches the audio config`() {
        prefs.edit().putString(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB, "-35").commit()
        change(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB)
        assertThat(audioConfig().speexNoiseSuppressDb).isEqualTo(-35)
    }

    /** The handset mode is the router's earpiece default now; the stream no longer decides it. */
    @Test
    fun `handset mode makes the earpiece the default output`() {
        val router = field(service, "mRouter") as AudioRouter
        prefs.edit().putBoolean(Settings.PREF_HANDSET_MODE, true).commit()
        change(Settings.PREF_HANDSET_MODE)
        assertThat(router.earpieceByDefault).isTrue()

        prefs.edit().putBoolean(Settings.PREF_HANDSET_MODE, false).commit()
        change(Settings.PREF_HANDSET_MODE)
        assertThat(router.earpieceByDefault).isFalse()
    }

    /**
     * The chooser's echo switch writes a per-device override; the service has to hold all of them,
     * so the next device of that kind gets it too. Read off the map the route decision uses.
     */
    @Test
    fun `an echo cancellation override reaches the service`() {
        Settings.getInstance(service).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, false)
        change(Settings.echoCancellationKey(AudioDeviceCategory.SPEAKER))
        assertThat(field(service, "mEchoOverrides")).isEqualTo(mapOf(AudioDeviceCategory.SPEAKER to false))

        Settings.getInstance(service).setEchoCancellationOverride(AudioDeviceCategory.EARPIECE, false)
        change(Settings.echoCancellationKey(AudioDeviceCategory.EARPIECE))
        assertThat(field(service, "mEchoOverrides")).isEqualTo(
            mapOf(AudioDeviceCategory.SPEAKER to false, AudioDeviceCategory.EARPIECE to false),
        )
    }

    /** Four corners over two booleans: one `||` between them would pass three of the four. */
    @Test
    fun `each android audio effect toggle reaches its own config field`() {
        for (ns in listOf(false, true)) {
            for (agc in listOf(false, true)) {
                prefs.edit()
                    .putBoolean(Settings.PREF_ANDROID_NOISE_SUPPRESSOR, ns)
                    .putBoolean(Settings.PREF_ANDROID_AGC, agc)
                    .commit()
                change(Settings.PREF_ANDROID_NOISE_SUPPRESSOR)
                change(Settings.PREF_ANDROID_AGC)
                assertThat(audioConfig().androidNoiseSuppressor).isEqualTo(ns)
                assertThat(audioConfig().androidAgc).isEqualTo(agc)
            }
        }
    }

    // --- pin the set ---------------------------------------------------------------------------

    /**
     * Enumerated from the screen rather than from the diff: every `android:key` the audio settings
     * XML declares is either turned into an extra by [AudioPreferenceExtras] or named here with the
     * reason it is not. A switch added to the screen that reaches nothing fails this test instead
     * of shipping, which is the case no per-key assertion above can cover -- they only know about
     * the keys somebody already thought of.
     */
    @Test
    fun `every key on the audio settings screen is either wired or exempt with a reason`() {
        val exempt = mapOf(
            "vad_settings" to "a PreferenceCategory, not a setting",
            "input_level_meter" to "not persisted: the settings screen's own live meter",
            "audio_loopback_test" to "not persisted: the settings screen's own monitor switch",
            "vad_recalibrate" to "not persisted: restarts the settings screen's own measurement",
            "ptt_settings" to "a PreferenceCategory, not a setting",
            "talkKey" to "read by the overlay and the PTT button, not by the audio chain",
            "hotCorner" to "read by MumlaService's hot corner, in its own case",
            "hidePtt" to "read by the channel fragment when it builds the PTT button",
            "togglePtt" to "read by the PTT button when it handles a press",
            "ptt_sound" to "read by MumlaService's own field, in its own case",
            "disableOpus" to "flagged as requiring a reconnect, in its own case",
        )

        val keys = mutableSetOf<String>()
        val parser = service.resources.getXml(R.xml.settings_audio)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            keys += parser.getAttributeValue(ANDROID_NS, "key") ?: continue
        }

        assertThat(keys.filterNot { it in AudioPreferenceExtras.KEYS || it in exempt }).isEmpty()
        // The exemptions cannot rot: each one has to be a key the screen really still has.
        assertThat(keys).containsAtLeastElementsIn(exempt.keys)
    }

    /**
     * And the other direction, because [AudioPreferenceExtras.KEYS] alone proves only that a key
     * is listed: every key it claims must produce a non-empty bundle, and a key it does not claim
     * must produce an empty one.
     */
    @Test
    fun `every key the mapper claims produces an extra and every other key produces none`() {
        val settings = Settings.getInstance(service)
        for (key in AudioPreferenceExtras.KEYS) {
            assertThat(AudioPreferenceExtras.extrasFor(key, settings).isEmpty).isFalse()
        }
        for (key in listOf(Settings.PREF_USE_TTS, Settings.PREF_HOT_CORNER_KEY, Settings.PREF_PTT_SOUND, "nonsense")) {
            assertThat(AudioPreferenceExtras.extrasFor(key, settings).isEmpty).isTrue()
        }
    }

    /** Every voice-gate key produces the same one extra, so one drag is one reconfiguration. */
    @Test
    fun `the voice gate keys all produce exactly the vad config extra`() {
        val settings = Settings.getInstance(service)
        for (key in AudioPreferenceExtras.VAD_KEYS) {
            val extras = AudioPreferenceExtras.extrasFor(key, settings)
            assertThat(extras.keySet()).containsExactly(HumlaService.EXTRAS_VAD_CONFIG)
            // `HumlaService.requiresAudioRebuild` is gone with task A9b: the rebuild is decided by
            // the value of AudioConfig, not by the key, and EXTRAS_VAD_CONFIG reaches a live object
            // rather than the config. That one drag costs no rebuild is pinned end to end by
            // HumlaServiceAudioTest.aLiveExtraDoesNotRebuildThePipeline.
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
