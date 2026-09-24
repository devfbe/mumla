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

package se.lublin.mumla.channel

import android.app.Application
import android.media.AudioDeviceInfo
import android.os.Bundle
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
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.humla.session.CommunicationDevice
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ChatTargetParentFragment
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubConnected

/**
 * The audio chooser in the channel menu: lists what the session offers right now, ticks the device
 * voice goes to and hands a tap to the session. Default and takeover rules belong to `AudioRouter`.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentAudioDeviceTest {

    private val earpiece = CommunicationDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel")
    private val speaker = CommunicationDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel")
    private val headset = CommunicationDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Jabra Evolve")

    private lateinit var app: Application
    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelListFragment
    private lateinit var service: IMumlaService
    private lateinit var session: IHumlaSession

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()

        service = mockk(relaxed = true)
        session = mockk(relaxed = true)
        service.stubConnected(session)
        every { session.audioDevices } returns listOf(earpiece, speaker, headset)
        every { session.activeAudioDevice } returns headset
        every { session.isEchoCancellationEnabled } returns false

        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        controller.get().bind(service)
        val parent = ChatTargetParentFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(parent, "parent").commitNow()
        fragment = ChannelListFragment()
        fragment.arguments = Bundle().apply { putBoolean("pinned", false) }
        parent.childFragmentManager.beginTransaction().add(fragment, "list").commitNow()
    }

    private val activity: ServiceHostActivity get() = controller.get()

    /** The real menu resource, inflated and prepared the way the action bar does it. */
    @Suppress("DEPRECATION")
    private fun prepared(): Menu {
        val menu = PopupMenu(activity, View(activity)).menu
        activity.menuInflater.inflate(R.menu.fragment_channel_list, menu)
        fragment.onPrepareMenu(menu)
        return menu
    }

    private fun Menu.chooser(): MenuItem = findItem(R.id.menu_audio_device)

    private fun Menu.choices(): List<MenuItem> {
        val sub = chooser().subMenu!!
        return (0 until sub.size()).map { sub.getItem(it) }
            .filter { it.groupId == R.id.menu_audio_device_group }
    }

    private fun Menu.echo(): MenuItem = chooser().subMenu!!.findItem(R.id.menu_audio_echo)

    @Test
    fun theChooserHasATitleAndAnIcon() {
        val chooser = prepared().chooser()

        assertThat(chooser.title.toString()).isEqualTo(app.getString(R.string.audio_device))
        assertThat(chooser.icon).isNotNull()
    }

    @Test
    fun itListsWhatTheSessionOffersWithReadableNames() {
        val titles = prepared().choices().map { it.title.toString() }

        assertThat(titles).containsExactly(
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
            "Jabra Evolve",
        ).inOrder()
    }

    /**
     * Single choice with one tick, on the device voice goes to. `MenuItemImpl` stores `isChecked`
     * even on non-checkable items, so checkability is asserted too.
     */
    @Test
    fun theDeviceVoiceGoesToIsTheOneTicked() {
        val choices = prepared().choices()

        assertThat(choices.all { it.isCheckable }).isTrue()
        assertThat(choices.filter { it.isChecked }.map { it.itemId }).containsExactly(7)
    }

    @Test
    fun tappingADeviceHandsItToTheSessionAndRedrawsTheTick() {
        val before = activity.menuInvalidations
        val speakerItem = prepared().choices().single { it.itemId == 2 }

        @Suppress("DEPRECATION")
        val consumed = fragment.onMenuItemSelected(speakerItem)

        assertThat(consumed).isTrue()
        verify(exactly = 1) { session.selectAudioDevice(2) }
        assertThat(activity.menuInvalidations).isGreaterThan(before)
    }

    /**
     * Devices are read when the chooser opens. The tap on the chooser is not consumed, so the
     * platform opens the refilled submenu.
     */
    @Test
    fun openingTheChooserReadsTheDevicesAgain() {
        every { session.audioDevices } returns listOf(earpiece, speaker)
        every { session.activeAudioDevice } returns speaker
        val menu = prepared()
        assertThat(menu.choices()).hasSize(2)

        every { session.audioDevices } returns listOf(earpiece, speaker, headset)
        every { session.activeAudioDevice } returns headset
        @Suppress("DEPRECATION")
        val consumed = fragment.onMenuItemSelected(menu.chooser())

        assertThat(consumed).isFalse()
        assertThat(menu.choices().map { it.itemId }).containsExactly(1, 2, 7).inOrder()
        assertThat(menu.choices().single { it.isChecked }.itemId).isEqualTo(7)
    }

    @Test
    fun withoutAConnectionTheChooserIsHidden() {
        every { service.isConnected } returns false

        val menu = prepared()

        assertThat(menu.chooser().isVisible).isFalse()
        assertThat(menu.choices()).isEmpty()
        verify(exactly = 0) { session.audioDevices }
    }

    @Test
    fun withoutAServiceTheChooserIsHidden() {
        activity.bind(null)

        assertThat(prepared().chooser().isVisible).isFalse()
    }

    /** A platform that refused the device list shows nothing. */
    @Test
    fun anEmptyDeviceListHidesTheChooser() {
        every { session.audioDevices } returns emptyList()

        assertThat(prepared().chooser().isVisible).isFalse()
    }

    @Test
    fun withAConnectionTheChooserIsShown() {
        assertThat(prepared().chooser().isVisible).isTrue()
    }

    /** A tap after the connection went away does nothing and does not crash. */
    @Test
    fun aTapAfterTheConnectionWentAwayIsIgnored() {
        val speakerItem = prepared().choices().single { it.itemId == 2 }
        every { service.isConnected } returns false

        @Suppress("DEPRECATION")
        fragment.onMenuItemSelected(speakerItem)

        verify(exactly = 0) { session.selectAudioDevice(any()) }
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

        @Suppress("DEPRECATION")
        val consumed = fragment.onMenuItemSelected(prepared().echo())

        assertThat(consumed).isTrue()
        assertThat(settings.echoCancellationOverrides)
            .containsExactly(AudioDeviceCategory.BLUETOOTH, true)
        assertThat(activity.menuInvalidations).isGreaterThan(before)

        every { session.isEchoCancellationEnabled } returns true
        every { session.activeAudioDevice } returns speaker
        @Suppress("DEPRECATION")
        fragment.onMenuItemSelected(prepared().echo())

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
        assertThat(menu.chooser().subMenu!!.size()).isEqualTo(4) // three devices and the switch
    }

    /** The chooser replaced the old checkable "Bluetooth" item. */
    @Test
    fun thereIsNoSeparateBluetoothItemAnyMore() {
        val menu = prepared()
        val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }

        assertThat(titles).doesNotContain("Bluetooth")
    }
}
