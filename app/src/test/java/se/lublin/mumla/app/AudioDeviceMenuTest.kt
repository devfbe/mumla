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

package se.lublin.mumla.app

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.humla.session.CommunicationDevice
import se.lublin.humla.session.PreferredAudioDevice
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubConnected

/**
 * The toolbar's audio chooser: "Automatic" and the devices on offer, the saved one ticked
 * while it is (or, without a session, could be) the one in use. A tap is saved; with a session it
 * also goes to the session, without one nothing reaches `AudioManager`. Default and takeover rules
 * belong to `AudioRouter`.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDeviceMenuTest {

    private val earpiece = CommunicationDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel")
    private val speaker = CommunicationDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel")
    private val headset = CommunicationDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Jabra Evolve", "AA")
    private val wired = CommunicationDevice(4, AudioDeviceInfo.TYPE_WIRED_HEADSET, "")

    private lateinit var app: Application
    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var chooser: AudioDeviceMenu
    private lateinit var service: IMumlaService
    private var boundService: IMumlaService? = null
    private lateinit var session: IHumlaSession
    private lateinit var audioManager: AudioManager
    private val settings: Settings get() = Settings.getInstance(app)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        audioManager = app.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(
                platformDevice(11, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, ""),
                platformDevice(12, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""),
                platformDevice(17, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA", "Sony WH"),
            ),
        )

        service = mockk(relaxed = true)
        session = mockk(relaxed = true)
        service.stubConnected(session)
        boundService = service
        every { session.audioDevices } returns listOf(earpiece, speaker, headset)
        every { session.activeAudioDevice } returns headset
        every { session.isEchoCancellationEnabled } returns false

        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        chooser = AudioDeviceMenu(controller.get(), settings) {
            boundService?.takeIf { it.isConnected }?.session
        }
    }

    private val activity: ServiceHostActivity get() = controller.get()

    /** `AudioDeviceInfoBuilder` can set neither an id nor an address. */
    private fun platformDevice(id: Int, type: Int, address: String, name: String = "Robolectric") =
        mockk<AudioDeviceInfo> {
            every { this@mockk.id } returns id
            every { this@mockk.type } returns type
            every { this@mockk.address } returns address
            every { productName } returns name
        }

    private fun disconnected() {
        every { service.isConnected } returns false
    }

    private fun tap(item: MenuItem): Boolean = chooser.onMenuItemSelected(item)

    private fun assertAudioManagerUntouched() {
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(audioManager.communicationDevice).isNull()
    }

    /** The real menu resource, inflated and prepared the way the action bar does it. */
    private fun prepared(): Menu {
        val menu = PopupMenu(activity, View(activity)).menu
        chooser.onCreateMenu(menu, activity.menuInflater)
        chooser.onPrepareMenu(menu)
        return menu
    }

    private fun Menu.chooser(): MenuItem = findItem(R.id.menu_audio_device)

    private fun Menu.choices(): List<MenuItem> {
        val sub = chooser().subMenu!!
        return (0 until sub.size()).map { sub.getItem(it) }
            .filter { it.groupId == R.id.menu_audio_device_group }
    }

    private fun Menu.automatic(): MenuItem = choices().first()

    private fun Menu.devices(): List<MenuItem> = choices().drop(1)

    private fun Menu.ticked(): List<Int> = choices().filter { it.isChecked }.map { it.itemId }

    private fun Menu.echo(): MenuItem = chooser().subMenu!!.findItem(R.id.menu_audio_echo)

    @Test
    fun theChooserHasATitleAndAnIcon() {
        val chooser = prepared().chooser()

        assertThat(chooser.title.toString()).isEqualTo(app.getString(R.string.audio_device))
        assertThat(chooser.icon).isNotNull()
    }

    @Test
    fun itListsAutomaticAndWhatTheSessionOffersWithReadableNames() {
        val titles = prepared().choices().map { it.title.toString() }

        assertThat(titles).containsExactly(
            app.getString(R.string.audio_device_automatic_current, "Jabra Evolve"),
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
            "Jabra Evolve",
        ).inOrder()
    }

    /**
     * Single choice with one tick. `MenuItemImpl` stores `isChecked` even on non-checkable items, so
     * checkability is asserted too.
     */
    @Test
    fun theSavedDeviceIsTickedWhileVoiceGoesToIt() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(headset)

        val menu = prepared()

        assertThat(menu.choices().all { it.isCheckable }).isTrue()
        assertThat(menu.ticked()).containsExactly(7)
        assertThat(menu.automatic().title.toString()).isEqualTo(app.getString(R.string.audio_device_automatic))
    }

    /** Nothing saved: "Automatic" is ticked and names the device it picked. */
    @Test
    fun withoutASavedDeviceAutomaticIsTicked() {
        val menu = prepared()

        assertThat(menu.ticked()).containsExactly(R.id.menu_audio_device_automatic)
    }

    /** A headset took over from the saved speaker: what plays now is the automatic pick. */
    @Test
    fun whenVoiceGoesElsewhereThanTheSavedDeviceAutomaticIsTicked() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(speaker)
        every { session.audioDevices } returns listOf(earpiece, speaker, wired)
        every { session.activeAudioDevice } returns wired

        val menu = prepared()

        assertThat(menu.ticked()).containsExactly(R.id.menu_audio_device_automatic)
        val wiredLabel = app.getString(R.string.audio_device_wired)
        assertThat(menu.automatic().title.toString())
            .isEqualTo(app.getString(R.string.audio_device_automatic_current, wiredLabel))
    }

    @Test
    fun tappingADeviceHandsItToTheSessionSavesItAndRedrawsTheTick() {
        val before = activity.menuInvalidations
        val speakerItem = prepared().devices().single { it.itemId == 2 }

        val consumed = tap(speakerItem)

        assertThat(consumed).isTrue()
        verify(exactly = 1) { session.selectAudioDevice(2) }
        assertThat(settings.preferredAudioDevice).isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertThat(activity.menuInvalidations).isGreaterThan(before)
    }

    /** A Bluetooth headset is saved by its address; its id is gone at the next connection. */
    @Test
    fun tappingAHeadsetSavesItsAddress() {
        tap(prepared().devices().single { it.itemId == 7 })

        assertThat(settings.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA"))
    }

    @Test
    fun tappingAutomaticForgetsTheSavedDeviceAndTheSessionsPick() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(headset)

        val consumed = tap(prepared().automatic())

        assertThat(consumed).isTrue()
        assertThat(settings.preferredAudioDevice).isNull()
        verify(exactly = 1) { session.selectAutomaticAudioDevice() }
    }

    /**
     * Devices are read when the chooser opens. The tap on the chooser is not consumed, so the
     * platform opens the refilled submenu.
     */
    @Test
    fun openingTheChooserReadsTheDevicesAgain() {
        settings.preferredAudioDevice = PreferredAudioDevice.of(headset)
        every { session.audioDevices } returns listOf(earpiece, speaker)
        every { session.activeAudioDevice } returns speaker
        val menu = prepared()
        assertThat(menu.devices()).hasSize(2)

        every { session.audioDevices } returns listOf(earpiece, speaker, headset)
        every { session.activeAudioDevice } returns headset
        val consumed = tap(menu.chooser())

        assertThat(consumed).isFalse()
        assertThat(menu.devices().map { it.itemId }).containsExactly(1, 2, 7).inOrder()
        assertThat(menu.ticked()).containsExactly(7)
    }

    @Test
    fun withAConnectionTheChooserIsShown() {
        assertThat(prepared().chooser().isVisible).isTrue()
    }

    /** A platform that refused the device list still leaves "Automatic". */
    @Test
    fun anEmptyDeviceListLeavesAutomatic() {
        every { session.audioDevices } returns emptyList()

        val menu = prepared()

        assertThat(menu.chooser().isVisible).isTrue()
        assertThat(menu.choices().map { it.itemId }).containsExactly(R.id.menu_audio_device_automatic)
    }

    // --- without a connection -----------------------------------------------------------------

    /** Without a session the chooser lists what the platform offers for calls, never the session. */
    @Test
    fun withoutAConnectionTheChooserListsThePlatformsDevices() {
        disconnected()

        val menu = prepared()

        assertThat(menu.chooser().isVisible).isTrue()
        assertThat(menu.choices().map { it.title.toString() }).containsExactly(
            app.getString(R.string.audio_device_automatic),
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
            "Sony WH",
        ).inOrder()
        assertThat(menu.ticked()).containsExactly(R.id.menu_audio_device_automatic)
        verify(exactly = 0) { session.audioDevices }
    }

    @Test
    fun withoutAServiceTheChooserListsThePlatformsDevices() {
        boundService = null

        val menu = prepared()

        assertThat(menu.chooser().isVisible).isTrue()
        assertThat(menu.devices().map { it.itemId }).containsExactly(11, 12, 17).inOrder()
    }

    /** The saved headset is ticked when it is there, found by address though its id changed. */
    @Test
    fun withoutAConnectionTheSavedDeviceIsTickedWhenItIsThere() {
        disconnected()
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA")

        assertThat(prepared().ticked()).containsExactly(17)

        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "BB")

        assertThat(prepared().ticked()).containsExactly(R.id.menu_audio_device_automatic)
    }

    /** Routing without a voice session would duck every other app's audio. */
    @Test
    fun withoutAConnectionATapIsOnlySaved() {
        disconnected()
        val before = activity.menuInvalidations

        val consumed = tap(prepared().devices().single { it.itemId == 17 })

        assertThat(consumed).isTrue()
        assertThat(settings.preferredAudioDevice)
            .isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA"))
        verify(exactly = 0) { session.selectAudioDevice(any()) }
        assertAudioManagerUntouched()
        assertThat(activity.menuInvalidations).isGreaterThan(before)
    }

    @Test
    fun withoutAConnectionAutomaticIsOnlySaved() {
        disconnected()
        settings.preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

        tap(prepared().automatic())

        assertThat(settings.preferredAudioDevice).isNull()
        verify(exactly = 0) { session.selectAutomaticAudioDevice() }
        assertAudioManagerUntouched()
    }

    /** A tap on a session device after the connection went away, which the platform does not offer, does nothing. */
    @Test
    fun aTapAfterTheConnectionWentAwayIsIgnored() {
        val speakerItem = prepared().devices().single { it.itemId == 2 }
        disconnected()

        tap(speakerItem)

        verify(exactly = 0) { session.selectAudioDevice(any()) }
        assertThat(settings.preferredAudioDevice).isNull()
        assertAudioManagerUntouched()
    }

    /** Nothing runs without a session, so there is no echo canceller to show. */
    @Test
    fun withoutAConnectionTheEchoSwitchIsHidden() {
        disconnected()

        assertThat(prepared().echo().isVisible).isFalse()
    }

    // --- echo cancellation, below the devices ------------------------------------------------

    /**
     * One switch under the devices showing the effective setting for the current device's kind.
     * Checkable on its own, outside the single-choice group.
     */
    @Test
    fun theEchoSwitchShowsWhatRunsForTheActiveDevice() {
        val echo = prepared().echo()
        assertThat(echo.title.toString()).isEqualTo(app.getString(R.string.echo_cancellation))
        assertThat(echo.isCheckable).isTrue()
        assertThat(echo.isChecked).isFalse()
        assertThat(echo.groupId).isNotEqualTo(R.id.menu_audio_device_group)

        every { session.isEchoCancellationEnabled } returns true

        assertThat(prepared().echo().isChecked).isTrue()
    }

    /** Tapping it writes the override for this kind of device. */
    @Test
    fun tappingTheEchoSwitchRemembersTheChoiceForThisKindOfDevice() {
        val settings = Settings.getInstance(app)
        val before = activity.menuInvalidations

        val consumed = tap(prepared().echo())

        assertThat(consumed).isTrue()
        assertThat(settings.echoCancellationOverrides)
            .containsExactly(AudioDeviceCategory.BLUETOOTH, true)
        assertThat(activity.menuInvalidations).isGreaterThan(before)

        every { session.isEchoCancellationEnabled } returns true
        every { session.activeAudioDevice } returns speaker
        tap(prepared().echo())

        assertThat(settings.echoCancellationOverrides).containsExactly(
            AudioDeviceCategory.BLUETOOTH, true,
            AudioDeviceCategory.SPEAKER, false,
        )
    }

    @Test
    fun withoutAnActiveDeviceTheEchoSwitchIsHidden() {
        every { session.activeAudioDevice } returns null

        assertThat(prepared().echo().isVisible).isFalse()
    }

    @Test
    fun thereIsNoSeparateEchoMenuAnyMore() {
        val menu = prepared()
        val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }

        assertThat(titles).doesNotContain(app.getString(R.string.echo_cancellation))
        assertThat(menu.chooser().subMenu!!.size()).isEqualTo(5) // automatic, three devices, the switch
    }

    /** The chooser replaced the old checkable "Bluetooth" item. */
    @Test
    fun thereIsNoSeparateBluetoothItemAnyMore() {
        val menu = prepared()
        val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }

        assertThat(titles).doesNotContain("Bluetooth")
    }
}
