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

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.preference.ListPreference
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.assertUntouched
import se.lublin.mumla.testing.offerCommunicationDevices
import se.lublin.mumla.testing.openScreen

/**
 * The audio device setting: "Automatic", the devices the platform offers now and the saved one even
 * while it is away. It writes the same preference as the toolbar chooser and never routes.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDevicePreferenceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val settings get() = Settings.getInstance(app)

    @Before
    fun devices() {
        audioManager.offerCommunicationDevices()
    }

    private val activity by lazy { Robolectric.buildActivity(SettingsActivity::class.java).setup().get() }

    private fun openAudio() = activity.openScreen(AudioSettingsFragment::class.java)

    private fun AudioSettingsFragment.device() =
        requireNotNull(findPreference<ListPreference>(Settings.AUDIO_DEVICE.key))

    private fun ListPreference.labels() = entries.map { it.toString() }

    /** Picks the entry labelled [label] as the dialog does. */
    private fun ListPreference.pick(label: String) {
        val value = entryValues[labels().indexOf(label)].toString()
        if (callChangeListener(value)) this.value = value
    }

    @Test
    fun itListsAutomaticAndThePlatformsDevices() {
        val device = openAudio().device()

        assertThat(device.title.toString()).isEqualTo(app.getString(R.string.audio_device))
        assertThat(device.labels()).containsExactly(
            app.getString(R.string.audio_device_automatic),
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
            "Sony WH",
        ).inOrder()
        assertThat(device.summary.toString()).isEqualTo(app.getString(R.string.audio_device_automatic))
    }

    @Test
    fun theSavedDeviceIsTheChoiceWhenItIsThere() {
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA")

        val device = openAudio().device()

        assertThat(device.labels()).hasSize(4)
        assertThat(device.entry.toString()).isEqualTo("Sony WH")
        assertThat(device.summary.toString()).isEqualTo("Sony WH")
    }

    @Test
    fun aSavedDeviceThatIsAwayIsListedAsNotConnected() {
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "BB")

        val device = openAudio().device()

        val away = app.getString(R.string.audio_device_not_connected, app.getString(R.string.audio_device_bluetooth))
        assertThat(device.labels().last()).isEqualTo(away)
        assertThat(device.labels()).hasSize(5)
        assertThat(device.summary.toString()).isEqualTo(away)
    }

    @Test
    fun pickingADeviceSavesItWithoutRouting() {
        val device = openAudio().device()

        device.pick("Sony WH")

        assertThat(settings.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA"))
        assertThat(device.summary.toString()).isEqualTo("Sony WH")
        audioManager.assertUntouched()
    }

    @Test
    fun pickingAutomaticForgetsTheSavedDevice() {
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "BB")
        val device = openAudio().device()

        device.pick(app.getString(R.string.audio_device_automatic))

        assertThat(settings.preferredAudioDevice).isNull()
        assertThat(device.labels()).hasSize(4) // the away device is no longer offered
        assertThat(device.summary.toString()).isEqualTo(app.getString(R.string.audio_device_automatic))
    }

    /** What the toolbar chooser saves shows up when the screen comes back. */
    @Test
    fun aChoiceMadeElsewhereShowsOnResume() {
        val audio = openAudio()
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        audio.onPause()
        audio.onResume()

        assertThat(audio.device().summary.toString()).isEqualTo(app.getString(R.string.audio_device_speaker))
    }
}
