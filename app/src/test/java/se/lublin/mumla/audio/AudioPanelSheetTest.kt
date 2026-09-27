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
package se.lublin.mumla.audio

import android.Manifest
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Menu
import android.view.View
import android.view.Window
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.TestCaptureSource
import se.lublin.humla.testutil.TestPlaybackSink
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.channel.ChannelFragment
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.PublicServerListFragment
import se.lublin.mumla.testing.assertUntouched
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.launchMumlaActivity
import se.lublin.mumla.testing.offerCommunicationDevices
import se.lublin.mumla.testing.platformDevice
import se.lublin.mumla.testing.stubAudio
import se.lublin.mumla.testing.stubState

/**
 * The audio panel opens from the toolbar of every main screen, with or without a connection. Its
 * controls write the settings; its microphone check runs only between a tap and the sheet going
 * away, and only while there is no call to take the microphone from.
 */
@RunWith(RobolectricTestRunner::class)
class AudioPanelSheetTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val settings get() = Settings.getInstance(app)
    private val source = TestCaptureSource(listOf(ShortArray(FRAME) { 1000 }), loopLastFrame = true)
    private val capture = TestCaptureSource.Factory(source)

    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val audio = session.stubAudio()
    private val state = session.stubState(SessionState.Disconnected())
    private lateinit var activity: MumlaActivity

    @Before
    fun setUp() {
        MicCheck.captureFactory = capture
        MicCheck.sinkFactory = TestPlaybackSink.Factory(TestPlaybackSink())
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        audioManager.offerCommunicationDevices(
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

    @After
    fun resetSeams() {
        MicCheck.resetFactories()
    }

    /** Starts the activity on the drawer screen [screen], with the session current. */
    private fun launch(screen: Int) {
        val intent = Intent(app, MumlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, screen)
        activity = launchMumlaActivity(intent)
        installSession(session)
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

    private fun Menu.titles(): List<String> =
        (0 until size()).map { getItem(it) }.filter { it.isVisible }.map { it.title.toString() }

    private fun titles(vararg ids: Int) = ids.map { app.getString(it) }

    private fun openPanel(): AudioPanelSheet {
        val consumed = activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, menu().findItem(R.id.menu_audio_panel))
        assertThat(consumed).isTrue()
        drainMainUntil { sheet()?.view != null }
        return sheet()!!
    }

    private fun sheet() = activity.supportFragmentManager.findFragmentByTag(AudioPanelSheet.TAG) as? AudioPanelSheet

    private fun <T : View> AudioPanelSheet.find(id: Int): T = requireView().findViewById(id)

    private fun AudioPanelSheet.toggle(): MaterialButton = find(R.id.audio_panel_mic_toggle)

    private fun AudioPanelSheet.devices(): List<RadioButton> {
        val group = find<RadioGroup>(R.id.audio_panel_devices)
        return (0 until group.childCount).map { group.getChildAt(it) as RadioButton }
    }

    @Test
    fun theFavouritesOfferThePanelAfterTheirOwnActions() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        assertThat(shownScreen()).isInstanceOf(FavouriteServerListFragment::class.java)

        assertThat(menu().titles())
            .containsExactlyElementsIn(titles(R.string.add, R.string.quickConnect, R.string.audio_panel))
            .inOrder()
    }

    @Test
    fun thePublicServersOfferThePanel() {
        // No download in a test.
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("useTor", true).commit()
        launch(DrawerAdapter.ITEM_PUBLIC)
        assertThat(shownScreen()).isInstanceOf(PublicServerListFragment::class.java)

        assertThat(menu().titles().last()).isEqualTo(app.getString(R.string.audio_panel))
        openPanel()
    }

    @Test
    fun theChannelScreenOffersThePanel() {
        state.value = SessionState.Connected
        launch(DrawerAdapter.ITEM_SERVER)
        assertThat(shownScreen()).isInstanceOf(ChannelFragment::class.java)

        assertThat(menu().titles()).containsAtLeastElementsIn(
            titles(R.string.search, R.string.audio_panel, R.string.disconnect),
        ).inOrder()
        openPanel()
    }

    @Test
    fun theTransmitModeButtonsWriteTheSetting() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val panel = openPanel()
        val group = panel.find<MaterialButtonToggleGroup>(R.id.audio_panel_transmit)
        assertThat(group.checkedButtonId).isEqualTo(R.id.audio_panel_transmit_voice)

        panel.find<MaterialButton>(R.id.audio_panel_transmit_ptt).performClick()

        assertThat(settings.inputMethod).isEqualTo(Settings.ARRAY_INPUT_METHOD_PTT)
        assertThat(group.checkedButtonIds).containsExactly(R.id.audio_panel_transmit_ptt)
    }

    @Test
    fun theNoiseSuppressionButtonsWriteTheSetting() {
        settings.noiseSuppressionMethod = "rnnoise"
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val panel = openPanel()
        assertThat(panel.find<MaterialButtonToggleGroup>(R.id.audio_panel_noise).checkedButtonId)
            .isEqualTo(R.id.audio_panel_noise_rnnoise)

        panel.find<MaterialButton>(R.id.audio_panel_noise_none).performClick()

        assertThat(settings.noiseSuppressionMethod).isEqualTo("none")
    }

    /** Without a connection a choice is only saved: routing would duck every other app's audio. */
    @Test
    fun onAServerListADeviceChoiceIsOnlySaved() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val panel = openPanel()
        assertThat(panel.devices().map { it.text.toString() }).containsExactly(
            app.getString(R.string.audio_device_automatic),
            app.getString(R.string.audio_device_earpiece),
            app.getString(R.string.audio_device_speaker),
        ).inOrder()

        panel.devices()[2].performClick()

        assertThat(settings.preferredAudioDevice).isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        verify(exactly = 0) { audio.selectDevice(any()) }
        audioManager.assertUntouched()
    }

    @Test
    fun whileConnectedADeviceChoiceGoesToTheSession() {
        state.value = SessionState.Connected
        launch(DrawerAdapter.ITEM_SERVER)
        val panel = openPanel()

        panel.devices()[2].performClick()

        verify(exactly = 1) { audio.selectDevice(2) }
        assertThat(settings.preferredAudioDevice).isEqualTo(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun theEchoSwitchWritesTheOverrideForTheSavedDevicesKind() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val echo = openPanel().find<MaterialSwitch>(R.id.audio_panel_echo)
        assertThat(echo.isChecked).isTrue()

        echo.performClick()

        assertThat(settings.echoCancellationOverrides)
            .containsExactly(AudioDeviceCategory.SPEAKER, false)
    }

    @Test
    fun theMicrophoneCheckStartsOnlyOnATapAndStopsWithTheSheet() {
        settings.setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, true)
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val panel = openPanel()
        assertThat(capture.request).isNull()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(panel.toggle().text.toString()).isEqualTo(app.getString(R.string.mic_check_start))

        panel.toggle().performClick()

        assertThat(capture.request).isNotNull()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        assertThat(panel.toggle().text.toString()).isEqualTo(app.getString(R.string.mic_check_stop))
        val meter = panel.find<View>(R.id.audio_panel_meter)
        drainMainUntil(description = "a described reading") { ViewCompat.getStateDescription(meter) != null }
        assertThat(panel.find<TextView>(R.id.audio_panel_transmit_state).text.toString())
            .isAnyOf(
                app.getString(R.string.mic_check_would_transmit),
                app.getString(R.string.mic_check_would_not_transmit),
            )

        panel.dismiss()
        idleMainLooper()

        assertThat(source.events.last()).isEqualTo("release")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun aSecondTapStopsTheCheck() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val panel = openPanel()
        panel.toggle().performClick()

        panel.toggle().performClick()

        assertThat(source.events.last()).isEqualTo("release")
        assertThat(panel.toggle().text.toString()).isEqualTo(app.getString(R.string.mic_check_start))
    }

    /** The check would take the microphone from the call, so a connection shows why it is not there. */
    @Test
    fun whileConnectedTheCheckIsNotOffered() {
        state.value = SessionState.Connected
        launch(DrawerAdapter.ITEM_SERVER)
        val panel = openPanel()

        assertThat(panel.toggle().visibility).isEqualTo(View.GONE)
        assertThat(panel.find<TextView>(R.id.audio_panel_mic_hint).text.toString())
            .isEqualTo(app.getString(R.string.mic_check_connected))
    }

    @Test
    fun connectingStopsARunningCheck() {
        launch(DrawerAdapter.ITEM_FAVOURITES)
        val panel = openPanel()
        panel.toggle().performClick()

        state.value = SessionState.Connecting
        idleMainLooper()

        assertThat(source.events.last()).isEqualTo("release")
        assertThat(panel.toggle().visibility).isEqualTo(View.GONE)
    }

    private companion object {
        const val FRAME = 480
    }
}
