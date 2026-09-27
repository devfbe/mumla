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

package se.lublin.mumla.session

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.ui.AppMessages
import se.lublin.mumla.testing.nextMessage
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubState

/**
 * Changed preferences reach the current session: every audio setting through one reconfigure,
 * and a note when a connection setting only applies from the next connection. The session is a
 * mock that keeps what it is configured with.
 */
/**
 * For every switch on the audio settings screen, reads the result back off the object the audio
 * threads use.
 */
@RunWith(RobolectricTestRunner::class)
class SessionSettingsSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var config = SessionConfig()
    private var configures = 0
    private val session = mockk<IHumlaSession>(relaxed = true) {
        every { this@mockk.config } answers { this@SessionSettingsSyncTest.config }
        every { configure(any()) } answers {
            this@SessionSettingsSyncTest.config = firstArg()
            configures++
            false
        }
    }

    @Before
    fun setUp() {
        installSession(session)
    }

    private fun pipeline() = config.audio.pipeline

    private fun vadConfig(): VadConfig = config.audio.vad


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
            assertThat(vadConfig()).isEqualTo(Settings.getInstance(context).vadConfig)
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
        assertThat(vadConfig().startThreshold).isWithin(0.001f).of(0.81f)
    }

    @Test
    fun `the probability sliders reach the detector in probability mode`() {
        prefs.edit()
            .putString(Settings.VAD_MODE.key, "probability")
            .putInt(Settings.VAD_START.key, 77)
            .putInt(Settings.VAD_STOP.key, 22)
            .commit()
        assertThat(vadConfig().startThreshold).isWithin(0.001f).of(0.77f)
        assertThat(vadConfig().stopThreshold).isWithin(0.001f).of(0.22f)
    }

    @Test
    fun `the noise suppression method reaches the audio config`() {
        prefs.edit().putString(Settings.NOISE_SUPPRESSION_METHOD.key, "speex").commit()
        assertThat(pipeline().noiseSuppression).isEqualTo(NoiseSuppressionMode.SPEEX)
    }

    @Test
    fun `the speex suppression depth reaches the audio config`() {
        prefs.edit().putString(Settings.SPEEX_NOISE_SUPPRESS_DB.key, "-35").commit()
        assertThat(pipeline().speexNoiseSuppressDb).isEqualTo(-35)
    }

    /** The device saved in the chooser reaches the session, connected or not. */
    @Test
    fun `the saved audio device reaches the session`() {
        val earpiece = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        Settings.getInstance(context).preferredAudioDevice = earpiece
        assertThat(config.audio.preferredDevice).isEqualTo(earpiece)

        Settings.getInstance(context).preferredAudioDevice = null
        assertThat(config.audio.preferredDevice).isNull()
    }

    /** The routing itself is the session's; here only the wish has to arrive, either way. */
    @Test
    fun `the bluetooth preference reaches the session`() {
        Settings.getInstance(context).isBluetoothScoEnabled = false
        assertThat(config.audio.bluetoothAutomatic).isFalse()

        Settings.getInstance(context).isBluetoothScoEnabled = true
        assertThat(config.audio.bluetoothAutomatic).isTrue()
    }

    /**
     * The chooser's echo switch writes a per-device override; the session has to hold all of them,
     * so the next device of that kind gets it too. Read off the map the route decision uses.
     */
    @Test
    fun `an echo cancellation override reaches the session`() {
        Settings.getInstance(context).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, false)
        assertThat(config.audio.echoCancellationOverrides)
            .isEqualTo(mapOf(AudioDeviceCategory.SPEAKER to false))

        Settings.getInstance(context).setEchoCancellationOverride(AudioDeviceCategory.EARPIECE, false)
        assertThat(config.audio.echoCancellationOverrides).isEqualTo(
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
            "advanced_audio" to "a PreferenceCategory, not a setting",
        )

        val keys = mutableSetOf<String>()
        val parser = context.resources.getXml(R.xml.settings_audio)
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
        prefs.edit()
            .putBoolean(Settings.USE_TTS.key, true)
            .putString(Settings.HOT_CORNER.key, Settings.ARRAY_HOT_CORNER_TOP_LEFT)
            .putBoolean(Settings.PTT_SOUND.key, true)
            .putBoolean("nonsense", true)
            .commit()
        assertThat(configures).isEqualTo(0)

        prefs.edit().putBoolean(Settings.HALF_DUPLEX.key, true).commit()

        assertThat(configures).isEqualTo(1)
        assertThat(config.audio.halfDuplex).isTrue()
        assertThat(config).isEqualTo(SessionSettings.withAudioSettings(SessionConfig(), Settings.getInstance(context)))
    }

    /** Only a connection setting says so, and only while connected: it applies from the next one. */
    @Test
    fun `a setting that needs a reconnect says so while connected`() {
        for (key in listOf(Settings.CERT_ID.key, Settings.FORCE_TCP.key, Settings.USE_TOR.key)) {
            val messages = AppMessages()
            session.stubState(SessionState.Connecting)
            SessionSettingsSync(context, SessionManager.get(context), messages)
                .onPreferenceChanged(key)
            assertThat(nextMessage(messages)).isNull()

            session.stubState(SessionState.Connected)
            SessionSettingsSync(context, SessionManager.get(context), messages)
                .onPreferenceChanged(key)
            assertThat(nextMessage(messages)).isEqualTo(context.getString(R.string.change_requires_reconnect))
        }
        verify(exactly = 0) { session.disconnect() }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
