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

package se.lublin.mumla.preference

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.TestCaptureSource
import se.lublin.mumla.audio.TestPlaybackSink

/** The live meter only takes the microphone, and the audio mode, while the user asks for it. */
@RunWith(RobolectricTestRunner::class)
class AudioSettingsFragmentTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val source = TestCaptureSource(emptyList())
    private val capture = TestCaptureSource.Factory(source)
    private val sink = TestPlaybackSink.Factory(TestPlaybackSink())

    @Before
    fun seams() {
        AudioSettingsFragment.captureFactory = capture
        AudioSettingsFragment.sinkFactory = sink
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun resetSeams() {
        AudioSettingsFragment.resetFactories()
    }

    private val activity by lazy { Robolectric.buildActivity(SettingsActivity::class.java).setup().get() }

    private fun screen(): PreferenceFragmentCompat =
        activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat

    private fun openAudio(): AudioSettingsFragment {
        val root = screen()
        val entry = (0 until root.preferenceScreen.preferenceCount)
            .map { root.preferenceScreen.getPreference(it) }
            .single { it.fragment == AudioSettingsFragment::class.java.name }
        root.onPreferenceTreeClick(entry)
        idle()
        return screen() as AudioSettingsFragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AudioSettingsFragment.switch(key: String) = requireNotNull(findPreference<SwitchPreferenceCompat>(key))
    private fun AudioSettingsFragment.testSwitch() = switch("audio_test_microphone")
    private fun AudioSettingsFragment.loopbackSwitch() = switch("audio_loopback_test")
    private fun AudioSettingsFragment.meter() =
        requireNotNull(findPreference<InputLevelMeterPreference>("input_level_meter"))

    @Test
    fun `opening the screen leaves the microphone and the audio mode alone`() {
        val audio = openAudio()

        assertThat(capture.request).isNull()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(audio.testSwitch().isChecked).isFalse()
        assertThat(audio.loopbackSwitch().isEnabled).isFalse()
        assertThat(audio.meter().message).isEqualTo(app.getString(R.string.inputLevelMeterIdle))
    }

    @Test
    fun `turning the test on starts the meter in communication mode when echo cancellation is on`() {
        Settings.getInstance(app).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, true)
        val audio = openAudio()

        audio.testSwitch().performClick()
        idle()

        assertThat(capture.request).isNotNull()
        assertThat(source.events).contains("start")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        assertThat(audio.loopbackSwitch().isEnabled).isTrue()
    }

    @Test
    fun `turning the test off stops the meter, the monitor and communication mode`() {
        Settings.getInstance(app).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, true)
        val audio = openAudio()
        audio.testSwitch().performClick()
        audio.loopbackSwitch().performClick()
        idle()
        assertThat(sink.openedWith).isNotNull()

        audio.testSwitch().performClick()
        idle()

        assertThat(source.events.last()).isEqualTo("release")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(audio.loopbackSwitch().isChecked).isFalse()
        assertThat(audio.loopbackSwitch().isEnabled).isFalse()
        assertThat(audio.meter().message).isEqualTo(app.getString(R.string.inputLevelMeterIdle))
    }

    @Test
    fun `leaving the screen stops the test and resets both switches`() {
        Settings.getInstance(app).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, true)
        val audio = openAudio()
        audio.testSwitch().performClick()
        audio.loopbackSwitch().performClick()
        idle()

        audio.onPause()

        assertThat(source.events.last()).isEqualTo("release")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(audio.testSwitch().isChecked).isFalse()
        assertThat(audio.loopbackSwitch().isChecked).isFalse()
    }

    @Test
    fun `an audio setting changed while not testing does not start the meter`() {
        openAudio()

        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Settings.PREF_VAD_MODE, "amplitude").commit()
        idle()

        assertThat(capture.request).isNull()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `an audio setting changed while testing restarts the meter`() {
        val audio = openAudio()
        audio.testSwitch().performClick()
        idle()
        val first = capture.request

        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Settings.PREF_VAD_MODE, "amplitude").commit()
        idle()

        assertThat(capture.request).isNotSameInstanceAs(first)
        assertThat(audio.testSwitch().isChecked).isTrue()
    }

    @Test
    fun `without the microphone permission the test says the meter is unavailable`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = openAudio()

        audio.testSwitch().performClick()
        idle()

        assertThat(capture.request).isNull()
        assertThat(audio.meter().message).isEqualTo(app.getString(R.string.inputLevelMeterUnavailable))
    }
}
