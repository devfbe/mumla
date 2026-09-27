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
package se.lublin.mumla.app

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import androidx.appcompat.view.menu.MenuBuilder
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.ChannelFragment
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.PublicServerListFragment
import se.lublin.mumla.testing.stubAudio
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubState

/**
 * The audio chooser is in the toolbar of every main screen, with or without a connection: the
 * server lists offer it while disconnected, where a choice is only saved, and the channel screen
 * while connected, where it also goes to the session.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityAudioDeviceMenuTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val settings get() = Settings.getInstance(app)

    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val audio = session.stubAudio()
    private val state = session.stubState(SessionState.Disconnected())
    private lateinit var activity: MumlaActivity

    @Before
    fun setUp() {
        installDatabase(mockk(relaxed = true))
        shadowOf(audioManager).setAvailableCommunicationDevices(
            listOf(
                platformDevice(11, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
                platformDevice(12, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
        every { audio.devices } returns listOf(
            CommunicationDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel"),
            CommunicationDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel"),
        )
    }

    /** `AudioDeviceInfoBuilder` can set neither an id nor an address. */
    private fun platformDevice(id: Int, type: Int) = mockk<AudioDeviceInfo> {
        every { this@mockk.id } returns id
        every { this@mockk.type } returns type
        every { address } returns ""
        every { productName } returns "Robolectric"
    }

    /** Starts the activity on the drawer screen [screen], with the session current unless [bind] is false. */
    private fun launch(screen: Int, bind: Boolean = true) {
        val intent = Intent(app, MumlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, screen)
        activity = Robolectric.buildActivity(MumlaActivity::class.java, intent).setup().get()
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.dismiss() // the first-run guide
        if (bind) installSession(session)
        idleMainLooper()
    }

    private fun shownScreen() = activity.supportFragmentManager.findFragmentById(R.id.content_frame)

    /** The options menu, created and prepared as the toolbar does it. */
    private fun menu(): Menu {
        val menu = MenuBuilder(activity)
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        return menu
    }

    private fun Menu.visibleTitles(): List<String> =
        (0 until size()).map { getItem(it) }.filter { it.isVisible }.map { it.title.toString() }

    private fun Menu.devices(): List<MenuItem> {
        val sub = findItem(R.id.menu_audio_device).subMenu!!
        return (0 until sub.size()).map { sub.getItem(it) }
            .filter { it.groupId == R.id.menu_audio_device_group && it.itemId != R.id.menu_audio_device_automatic }
    }

    private fun select(item: MenuItem) = activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, item)

    private fun titles(vararg ids: Int) = ids.map { app.getString(it) }

    @Test
    fun theFavouritesOfferTheChooserAfterTheirOwnActionsWhileDisconnected() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        assertThat(shownScreen()).isInstanceOf(FavouriteServerListFragment::class.java)

        assertThat(menu().visibleTitles())
            .containsExactlyElementsIn(titles(R.string.add, R.string.quickConnect, R.string.audio_device))
            .inOrder()
    }

    @Test
    fun thePublicServersOfferTheChooserAfterTheirOwnActionsWhileDisconnected() {
        // No download in a test.
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("useTor", true).commit()
        launch(DrawerAdapter.ITEM_PUBLIC)
        assertThat(shownScreen()).isInstanceOf(PublicServerListFragment::class.java)

        val titles = menu().visibleTitles()

        assertThat(titles.last()).isEqualTo(app.getString(R.string.audio_device))
        assertThat(titles).containsAtLeastElementsIn(titles(R.string.search, R.string.sort, R.string.audio_device))
            .inOrder()
    }

    @Test
    fun withoutAServiceTheChooserListsThePlatformsDevices() {
        launch(DrawerAdapter.ITEM_FAVOURITES, bind = false)

        assertThat(menu().devices().map { it.itemId }).containsExactly(11, 12).inOrder()
    }

    /** Routing without a voice session would duck every other app's audio. */
    @Test
    fun onAServerListATapIsOnlySaved() {
        launch(DrawerAdapter.ITEM_FAVOURITES)

        val consumed = select(menu().devices().single { it.itemId == 12 })

        assertThat(consumed).isTrue()
        assertThat(settings.preferredAudioDevice).isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        verify(exactly = 0) { audio.selectDevice(any()) }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(audioManager.communicationDevice).isNull()
    }

    /** Where it stood in the channel list's menu: after search, the list's items before it. */
    @Test
    fun theChannelScreenHasTheChooserWhereTheListHadIt() {
        state.value = SessionState.Connected
        launch(DrawerAdapter.ITEM_SERVER)
        assertThat(shownScreen()).isInstanceOf(ChannelFragment::class.java)

        val menu = menu()
        val all = (0 until menu.size()).map { menu.getItem(it).title.toString() }

        assertThat(all).containsExactlyElementsIn(
            titles(
                R.string.audioInputMethod, R.string.mute, R.string.deafen, R.string.search,
                R.string.noiseSuppression, R.string.audio_device, R.string.disconnect,
            ),
        ).inOrder()
    }

    @Test
    fun whileConnectedATapGoesToTheSession() {
        state.value = SessionState.Connected
        launch(DrawerAdapter.ITEM_SERVER)

        val consumed = select(menu().devices().single { it.itemId == 2 })

        assertThat(consumed).isTrue()
        verify(exactly = 1) { audio.selectDevice(2) }
        assertThat(settings.preferredAudioDevice).isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}
