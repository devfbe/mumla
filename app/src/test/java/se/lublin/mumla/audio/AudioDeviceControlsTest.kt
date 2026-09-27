/*
 * Copyright (C) 2026 The Mumla contributors
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

package se.lublin.mumla.audio

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.AudioControls
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.session.isConnected
import se.lublin.mumla.testing.assertUntouched
import se.lublin.mumla.testing.offerCommunicationDevices
import se.lublin.mumla.testing.stubAudio
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubState

/**
 * The audio device chooser: "Automatic" and the devices on offer, the saved one selected while it
 * is (or, without a session, could be) the one in use. A choice is saved; with a session it also
 * goes to the session, without one nothing reaches `AudioManager`. Default and takeover rules
 * belong to `AudioRouter`.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDeviceControlsTest {

    private val earpiece = CommunicationDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel")
    private val speaker = CommunicationDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel")
    private val headset = CommunicationDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Jabra Evolve", "AA")
    private val wired = CommunicationDevice(4, AudioDeviceInfo.TYPE_WIRED_HEADSET, "")

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val settings: Settings get() = Settings.getInstance(app)
    private var current: IHumlaSession? = null
    private lateinit var session: IHumlaSession
    private lateinit var audio: AudioControls
    private val controls = AudioDeviceControls(app, Settings.getInstance(app)) { current?.takeIf { it.isConnected } }

    @Before
    fun setUp() {
        audioManager.offerCommunicationDevices()
        session = mockk<IHumlaSession>(relaxed = true).stubConnected()
        audio = session.stubAudio()
        current = session
        every { audio.devices } returns listOf(earpiece, speaker, headset)
        every { audio.activeDevice } returns headset
        every { audio.isEchoCancellationEnabled } returns false
    }

    private fun disconnected() {
        session.stubState(SessionState.Disconnected())
    }

    private fun selected(): List<Int?> = controls.choices().filter { it.selected }.map { it.id }

    @Test
    fun itListsAutomaticAndWhatTheSessionOffersWithReadableNames() {
        assertThat(controls.choices().map { it.label }).containsExactly(
            app.getString(R.string.audio_device_automatic_current, "Jabra Evolve"),
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
            "Jabra Evolve",
        ).inOrder()
    }

    @Test
    fun theSavedDeviceIsSelectedWhileVoiceGoesToIt() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(headset)

        assertThat(selected()).containsExactly(7)
        assertThat(controls.choices().first().label).isEqualTo(app.getString(R.string.audio_device_automatic))
    }

    @Test
    fun withoutASavedDeviceAutomaticIsSelected() {
        assertThat(selected()).containsExactly(null)
    }

    /** A headset took over from the saved speaker: what plays now is the automatic pick. */
    @Test
    fun whenVoiceGoesElsewhereThanTheSavedDeviceAutomaticIsSelected() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(speaker)
        every { audio.devices } returns listOf(earpiece, speaker, wired)
        every { audio.activeDevice } returns wired

        assertThat(selected()).containsExactly(null)
        assertThat(controls.choices().first().label).isEqualTo(
            app.getString(R.string.audio_device_automatic_current, app.getString(R.string.audio_device_wired)),
        )
    }

    @Test
    fun choosingADeviceHandsItToTheSessionAndSavesIt() {
        controls.choose(2)

        verify(exactly = 1) { audio.selectDevice(2) }
        assertThat(settings.preferredAudioDevice).isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    /** A Bluetooth headset is saved by its address; its id is gone at the next connection. */
    @Test
    fun choosingAHeadsetSavesItsAddress() {
        controls.choose(7)

        assertThat(settings.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA"))
    }

    @Test
    fun choosingAutomaticForgetsTheSavedDeviceAndTheSessionsPick() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(headset)

        controls.choose(null)

        assertThat(settings.preferredAudioDevice).isNull()
        verify(exactly = 1) { audio.selectAutomaticDevice() }
    }

    /** Devices are read on every call, so a headset switched on in between shows up. */
    @Test
    fun theChoicesAreReadAgainEachTime() {
        every { audio.devices } returns listOf(earpiece, speaker)
        assertThat(controls.choices()).hasSize(3)

        every { audio.devices } returns listOf(earpiece, speaker, headset)

        assertThat(controls.choices().map { it.id }).containsExactly(null, 1, 2, 7).inOrder()
    }

    /** A platform that refused the device list still leaves "Automatic". */
    @Test
    fun anEmptyDeviceListLeavesAutomatic() {
        every { audio.devices } returns emptyList()

        assertThat(controls.choices().map { it.id }).containsExactly(null)
    }

    /** Without a session the chooser lists what the platform offers for calls, never the session. */
    @Test
    fun withoutAConnectionItListsThePlatformsDevices() {
        disconnected()

        assertThat(controls.choices().map { it.label }).containsExactly(
            app.getString(R.string.audio_device_automatic),
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
            "Sony WH",
        ).inOrder()
        assertThat(selected()).containsExactly(null)
        verify(exactly = 0) { audio.devices }
    }

    @Test
    fun withoutAServiceItListsThePlatformsDevices() {
        current = null

        assertThat(controls.choices().map { it.id }).containsExactly(null, 11, 12, 17).inOrder()
    }

    /** The saved headset is selected when it is there, found by address though its id changed. */
    @Test
    fun withoutAConnectionTheSavedDeviceIsSelectedWhenItIsThere() {
        disconnected()
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA")

        assertThat(selected()).containsExactly(17)

        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "BB")

        assertThat(selected()).containsExactly(null)
    }

    /** Routing without a voice session would duck every other app's audio. */
    @Test
    fun withoutAConnectionAChoiceIsOnlySaved() {
        disconnected()

        controls.choose(17)

        assertThat(settings.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA"))
        verify(exactly = 0) { audio.selectDevice(any()) }
        audioManager.assertUntouched()
    }

    @Test
    fun withoutAConnectionAutomaticIsOnlySaved() {
        disconnected()
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

        controls.choose(null)

        assertThat(settings.preferredAudioDevice).isNull()
        verify(exactly = 0) { audio.selectAutomaticDevice() }
        audioManager.assertUntouched()
    }

    /** A choice of a session device after the connection went away, which the platform does not offer, does nothing. */
    @Test
    fun aChoiceAfterTheConnectionWentAwayIsIgnored() {
        disconnected()

        controls.choose(2)

        verify(exactly = 0) { audio.selectDevice(any()) }
        assertThat(settings.preferredAudioDevice).isNull()
        audioManager.assertUntouched()
    }

    // --- echo cancellation ----------------------------------------------------------------------

    @Test
    fun theEchoSwitchShowsWhatRunsForTheActiveDevice() {
        assertThat(controls.echoCategory).isEqualTo(AudioDeviceCategory.BLUETOOTH)
        assertThat(controls.isEchoCancellationEnabled).isFalse()

        every { audio.isEchoCancellationEnabled } returns true

        assertThat(controls.isEchoCancellationEnabled).isTrue()
    }

    @Test
    fun theEchoChoiceIsRememberedForTheActiveDevicesKind() {
        controls.setEchoCancellationEnabled(true)

        every { audio.activeDevice } returns speaker
        controls.setEchoCancellationEnabled(false)

        assertThat(settings.echoCancellationOverrides).containsExactly(
            AudioDeviceCategory.BLUETOOTH, true,
            AudioDeviceCategory.SPEAKER, false,
        )
    }

    /** A session that routes nowhere yet has no canceller to show. */
    @Test
    fun withoutAnActiveDeviceThereIsNoEchoSwitch() {
        every { audio.activeDevice } returns null

        assertThat(controls.echoCategory).isNull()
        controls.setEchoCancellationEnabled(true)
        assertThat(settings.echoCancellationOverrides).isEmpty()
    }

    /** Before connecting, the switch is for the saved device's kind, else the speaker's. */
    @Test
    fun withoutAConnectionTheEchoSwitchIsForTheSavedDevicesKind() {
        disconnected()
        assertThat(controls.echoCategory).isEqualTo(AudioDeviceCategory.SPEAKER)
        assertThat(controls.isEchoCancellationEnabled).isTrue()

        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA")
        controls.setEchoCancellationEnabled(true)

        assertThat(controls.echoCategory).isEqualTo(AudioDeviceCategory.BLUETOOTH)
        assertThat(controls.isEchoCancellationEnabled).isTrue()
        assertThat(settings.echoCancellationOverrides).containsExactly(AudioDeviceCategory.BLUETOOTH, true)
        verify(exactly = 0) { audio.isEchoCancellationEnabled }
    }
}
