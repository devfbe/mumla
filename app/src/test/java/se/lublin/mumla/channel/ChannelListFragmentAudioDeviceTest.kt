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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.preference.PreferenceManager
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
import se.lublin.humla.session.CommunicationDevice
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * The audio chooser in the channel menu - the phone app's "Bluetooth / speaker / earpiece / wired
 * headset" picker. It lists what the session offers **right now**, ticks the device voice goes to
 * and hands a tap to the session; the decision what the default is and when a headset takes over
 * belongs to `AudioRouter` and is pinned there. It replaces the "Bluetooth" item, whose standing
 * wish lives on in the settings screen.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentAudioDeviceTest {

    /**
     * Same contract as `ChannelListFragmentTest.HostActivity`, plus a count of the menu
     * invalidations -- the only way the fragment's "redraw the tick" effect can be read back.
     */
    class RecordingHostActivity : AppCompatActivity(), HumlaServiceProvider, DatabaseProvider {
        private var bound: IMumlaService? = null
        private val db: MumlaDatabase = mockk(relaxed = true)
        private var invalidations = 0

        fun bind(service: IMumlaService?) {
            bound = service
        }

        fun invalidationCount(): Int = invalidations

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }

        override fun invalidateOptionsMenu() {
            invalidations++
            super.invalidateOptionsMenu()
        }

        override fun getService(): IMumlaService? = bound
        override fun addServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun removeServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun getDatabase(): MumlaDatabase = db
    }

    private val earpiece = CommunicationDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel")
    private val speaker = CommunicationDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel")
    private val headset = CommunicationDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Jabra Evolve")

    private lateinit var app: Application
    private lateinit var controller: ActivityController<RecordingHostActivity>
    private lateinit var fragment: ChannelListFragment
    private lateinit var service: IMumlaService
    private lateinit var session: IHumlaSession

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()

        service = mockk(relaxed = true)
        session = mockk(relaxed = true)
        every { service.isConnected } returns true
        every { service.HumlaSession() } returns session
        every { session.audioDevices } returns listOf(earpiece, speaker, headset)
        every { session.activeAudioDevice } returns headset

        controller = Robolectric.buildActivity(RecordingHostActivity::class.java).setup()
        controller.get().bind(service)
        val parent = ChannelListFragmentTest.HostParent()
        controller.get().supportFragmentManager.beginTransaction()
            .add(parent, "parent").commitNow()
        fragment = ChannelListFragment()
        fragment.arguments = Bundle().apply { putBoolean("pinned", false) }
        parent.childFragmentManager.beginTransaction().add(fragment, "list").commitNow()
    }

    private val activity: RecordingHostActivity get() = controller.get()

    /** The real menu resource, inflated and prepared the way the action bar does it. */
    @Suppress("DEPRECATION")
    private fun prepared(): Menu {
        val menu = PopupMenu(activity, View(activity)).menu
        activity.menuInflater.inflate(R.menu.fragment_channel_list, menu)
        fragment.onPrepareOptionsMenu(menu)
        return menu
    }

    private fun Menu.chooser(): MenuItem = findItem(R.id.menu_audio_device)

    private fun Menu.choices(): List<MenuItem> {
        val sub = chooser().subMenu!!
        return (0 until sub.size()).map { sub.getItem(it) }
    }

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
     * Single choice with exactly one tick, on the device voice goes to. `isChecked` alone would
     * stay green on an item that draws no tick at all - `MenuItemImpl` stores the flag whether or
     * not the item is checkable - so the checkable flag is asserted with it.
     */
    @Test
    fun theDeviceVoiceGoesToIsTheOneTicked() {
        val choices = prepared().choices()

        assertThat(choices.all { it.isCheckable }).isTrue()
        assertThat(choices.filter { it.isChecked }.map { it.itemId }).containsExactly(7)
    }

    @Test
    fun tappingADeviceHandsItToTheSessionAndRedrawsTheTick() {
        val before = activity.invalidationCount()
        val speakerItem = prepared().choices().single { it.itemId == 2 }

        @Suppress("DEPRECATION")
        val consumed = fragment.onOptionsItemSelected(speakerItem)

        assertThat(consumed).isTrue()
        verify(exactly = 1) { session.selectAudioDevice(2) }
        assertThat(activity.invalidationCount()).isGreaterThan(before)
    }

    /**
     * The devices are read when the chooser is opened, not when the menu was last drawn: a headset
     * switched on since then has to be there when the user looks. Tapping the chooser itself is
     * not consumed, so the platform goes on to open the submenu that was just refilled.
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
        val consumed = fragment.onOptionsItemSelected(menu.chooser())

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

    /** Nothing to choose from - a platform that refused the device list - is nothing to show. */
    @Test
    fun anEmptyDeviceListHidesTheChooser() {
        every { session.audioDevices } returns emptyList()

        assertThat(prepared().chooser().isVisible).isFalse()
    }

    @Test
    fun withAConnectionTheChooserIsShown() {
        assertThat(prepared().chooser().isVisible).isTrue()
    }

    /** A tap that arrives after the connection went away does nothing and does not crash. */
    @Test
    fun aTapAfterTheConnectionWentAwayIsIgnored() {
        val speakerItem = prepared().choices().single { it.itemId == 2 }
        every { service.isConnected } returns false

        @Suppress("DEPRECATION")
        fragment.onOptionsItemSelected(speakerItem)

        verify(exactly = 0) { session.selectAudioDevice(any()) }
    }

    /** The chooser replaces the old checkable "Bluetooth" item; there is no second way in. */
    @Test
    fun thereIsNoSeparateBluetoothItemAnyMore() {
        val menu = prepared()
        val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }

        assertThat(titles).doesNotContain(app.getString(R.string.bluetooth))
    }
}
