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
import android.os.Looper
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.session.PreferredAudioDevice
import se.lublin.mumla.R
import se.lublin.mumla.Settings

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
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(
                platformDevice(11, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, ""),
                platformDevice(12, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""),
                platformDevice(17, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA", "Sony WH"),
            ),
        )
    }

    /** `AudioDeviceInfoBuilder` can set neither an id nor an address. */
    private fun platformDevice(id: Int, type: Int, address: String, name: String = "Robolectric") =
        mockk<AudioDeviceInfo> {
            every { this@mockk.id } returns id
            every { this@mockk.type } returns type
            every { this@mockk.address } returns address
            every { productName } returns name
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
        shadowOf(Looper.getMainLooper()).idle()
        return screen() as AudioSettingsFragment
    }

    private fun AudioSettingsFragment.device() =
        requireNotNull(findPreference<ListPreference>(Settings.PREF_AUDIO_DEVICE))

    private fun ListPreference.labels() = entries.map { it.toString() }

    /** Picks the entry labelled [label] as the dialog does. */
    private fun ListPreference.pick(label: String) {
        val value = entryValues[labels().indexOf(label)].toString()
        if (callChangeListener(value)) this.value = value
    }

    private fun assertAudioManagerUntouched() {
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(audioManager.communicationDevice).isNull()
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
        assertAudioManagerUntouched()
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
