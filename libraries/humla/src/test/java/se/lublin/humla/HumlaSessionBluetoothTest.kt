/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.routing.AndroidCommunicationDevicesTest
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.setBluetoothAutomatic
import se.lublin.humla.testutil.HumlaSessionHarness
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.TimeUnit

/**
 * The user's wish for a Bluetooth headset and the route the platform actually holds are separate:
 * the wish survives everything the route does not. Routing goes through
 * [se.lublin.humla.audio.routing.AudioRouter] over [se.lublin.humla.audio.routing.CommunicationDevices].
 */
@RunWith(RobolectricTestRunner::class)
class HumlaSessionBluetoothTest {
    private val harnesses = mutableListOf<HumlaSessionHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
    }

    private fun start(autoReconnect: Boolean = false): HumlaSessionHarness =
        HumlaSessionHarness(autoReconnect = autoReconnect).also { harnesses += it }

    private fun connectionError() =
        HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

    @Test
    fun theWishAndTheRouteAreDifferentQuestions() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()

        assertThat(h.session.audio.router.bluetoothAutomatic).isFalse()
        assertThat(h.session.audio.router.isBluetoothActive).isFalse()

        h.session.setBluetoothAutomatic(true)

        assertThat(h.devices.selectCalls).containsExactly(7)
        assertThat(h.session.audio.router.bluetoothAutomatic).isTrue()
        assertThat(h.session.audio.router.isBluetoothActive).isTrue()

        h.session.setBluetoothAutomatic(false)

        assertThat(h.devices.clearCalls).isEqualTo(1)
        assertThat(h.session.audio.router.bluetoothAutomatic).isFalse()
        assertThat(h.session.audio.router.isBluetoothActive).isFalse()
    }

    /**
     * A platform that refuses the route is one chat line, not one per attempt. The router reports
     * every refusal; the session de-duplicates against the last line it delivered.
     */
    @Test
    fun aRefusedRouteIsOneChatLine() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.devices.selectResult = false
        h.connectAndSynchronize()

        h.session.setBluetoothAutomatic(true)
        h.session.setBluetoothAutomatic(false)
        h.session.setBluetoothAutomatic(true)

        val line = h.string(R.string.audio_route_refused)
        assertThat(h.warnings.filter { it == line }).hasSize(1)

        // A different line in between ends the suppression: the rule is about repetition.
        h.session.logger.logWarning("something else")
        h.session.setBluetoothAutomatic(false)
        h.session.setBluetoothAutomatic(true)
        assertThat(h.warnings.filter { it == line }).hasSize(2)
    }

    /** Without a headset the default route is the phone itself, not a failure. */
    @Test
    fun wantingAHeadsetThatIsNotThereSaysNothing() {
        val h = start()
        h.connectAndSynchronize()

        h.session.setBluetoothAutomatic(true)

        assertThat(h.warnings).isEmpty()
    }

    /**
     * The route is dropped with every connection, the wish is not, and a synchronized session
     * restores the route.
     */
    @Test
    fun bluetoothScoIsRestartedAfterAReconnect() {
        val h = start(autoReconnect = true)
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.session.setBluetoothAutomatic(true)
        assertThat(h.devices.selectCalls).containsExactly(7)
        assertThat(h.session.audio.router.isBluetoothActive).isTrue()

        h.failConnection(0, connectionError())

        assertThat(h.session.state.value)
            .isInstanceOf(SessionState.ConnectionLost::class.java)
        assertThat(h.session.audio.router.bluetoothAutomatic).isTrue() // the wish survives the loss
        assertThat(h.session.audio.router.isBluetoothActive).isFalse() // the route does not

        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer
        h.synchronize(h.openSocket(1))

        assertThat(h.devices.selectCalls).containsExactly(7, 7).inOrder()
        assertThat(h.session.audio.router.isBluetoothActive).isTrue()
    }

    @Test
    fun aUserDisconnectReleasesTheRouteAndKeepsTheWish() {
        val h = start(autoReconnect = true)
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.session.setBluetoothAutomatic(true)

        h.session.disconnect()
        h.mainLooper.idle()

        assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.devices.clearCalls).isEqualTo(1)
        // The wish is not a session resource: it is the user's setting until they change it.
        assertThat(h.session.audio.router.bluetoothAutomatic).isTrue()
    }

    /** Closing the session gives the route back, and takes the listener off the platform. */
    @Test
    fun closingTheSessionReleasesTheRouteAndTheListener() {
        val h = HumlaSessionHarness().also { harnesses += it }
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.session.setBluetoothAutomatic(true)
        assertThat(h.devices.listener).isNotNull()

        h.close()
        harnesses.remove(h)

        assertThat(h.devices.clearCalls).isEqualTo(1)
        assertThat(h.devices.listener).isNull()
    }

    /**
     * Nothing is routed before a session is synchronized: routing voice with no voice holds an SCO
     * link open for nothing.
     */
    @Test
    fun noRouteIsTakenWithoutASession() {
        val h = HumlaSessionHarness().also { harnesses += it }
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        h.session.setBluetoothAutomatic(true)
        h.devices.deviceArrives(9, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        assertThat(h.devices.selectCalls).isEmpty()
        assertThat(h.session.audio.router.bluetoothAutomatic).isTrue()

        h.close()
        harnesses.remove(h)

        assertThat(h.devices.clearCalls).isEqualTo(0)
    }

    private fun HumlaSessionHarness.phone() {
        devices!!.available[1] = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        devices.available[2] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    /** The chooser's three calls, through the session interface the UI holds. */
    @Test
    fun theUserPicksTheDeviceThroughTheSession() {
        val h = start()
        h.phone()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.devices.names[7] = "Jabra"
        h.connectAndSynchronize()
        val session: IHumlaSession = h.session

        assertThat(session.audioDevices.map { it.id }).containsExactly(1, 2, 7).inOrder()
        assertThat(session.activeAudioDevice?.id).isEqualTo(2)

        session.selectAudioDevice(1)

        assertThat(h.devices.selectedId).isEqualTo(1)
        assertThat(session.activeAudioDevice?.id).isEqualTo(1)
    }

    @Test
    fun theChooserIsEmptyWithoutASession() {
        val h = start()
        h.phone()

        assertThat(h.session.audioDevices).isEmpty()
        assertThat(h.session.activeAudioDevice).isNull()
    }

    /** The saved device reaches the router with the config, live. */
    @Test
    fun theSavedDeviceIsTheDefaultWhenTheConfigNamesIt() {
        val h = start()
        h.phone()
        h.configureAudio { copy(preferredDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) }
        h.connectAndSynchronize()

        assertThat(h.session.activeAudioDevice?.id).isEqualTo(1)

        h.configureAudio { copy(preferredDevice = null) }

        assertThat(h.devices!!.selectedId).isEqualTo(2)
    }

    /** Saving a device while disconnected must not touch the platform: that would duck other apps. */
    @Test
    fun aSavedDeviceIsNotRoutedWithoutASession() {
        val h = start()
        h.phone()

        h.configureAudio { copy(preferredDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) }

        assertThat(h.devices!!.selectCalls).isEmpty()
        assertThat(h.devices.modeCalls).isEmpty()
    }

    @Test
    fun theUserCanGoBackToAutomaticThroughTheSession() {
        val h = start()
        h.phone()
        h.connectAndSynchronize()
        val session: IHumlaSession = h.session
        session.selectAudioDevice(1)

        session.selectAutomaticAudioDevice()

        assertThat(h.devices!!.selectedId).isEqualTo(2)
    }

    /**
     * The communication mode is the session's: without it the platform does not route voice by the
     * communication device, and left behind it keeps the phone in call mode after a disconnect.
     */
    @Test
    fun theSessionHoldsTheCommunicationMode() {
        val h = start()
        h.phone()
        h.connectAndSynchronize()
        assertThat(h.devices!!.inCommunicationMode).isTrue()

        h.session.disconnect()
        h.mainLooper.idle()

        assertThat(h.devices.inCommunicationMode).isFalse()
    }

    /** The choice is the wish, and like the headset wish it outlives a dropped connection. */
    @Test
    fun aChoiceSurvivesAReconnect() {
        val h = start(autoReconnect = true)
        h.phone()
        h.connectAndSynchronize()
        h.session.selectAudioDevice(1)

        h.failConnection(0, connectionError())
        assertThat(h.devices!!.selectedId).isNull() // the route is a session resource
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer
        h.synchronize(h.openSocket(1))

        assertThat(h.devices.selectedId).isEqualTo(1)
    }

    /** ...but not the end of the session: the next call starts from the default, as on a phone. */
    @Test
    fun aChoiceIsForgottenWhenTheSessionEnds() {
        val h = start()
        h.phone()
        h.connectAndSynchronize()
        h.session.selectAudioDevice(1)

        h.session.disconnect()
        h.mainLooper.idle()

        assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected())
        h.session.connect()
        h.connectAndSynchronize(1)
        assertThat(h.devices!!.selectCalls).containsExactly(2, 1, 2).inOrder()
        assertThat(h.session.activeAudioDevice?.id).isEqualTo(2)
    }

    /**
     * A routed device only carries the voice when the track is on the voice-call stream: a
     * media-stream track does not follow the communication device. The first pipeline is built
     * for the route the session starts on, and a chosen device rebuilds it for its own.
     */
    @Test
    fun thePipelineIsBuiltForTheRoutedDevice() {
        val h = start()
        h.phone()
        h.connectAndSynchronize()
        awaitUntil(description = "audio created") { h.mainLooper.idle(); h.audioFactory.created.size == 1 }
        assertThat(h.audioFactory.configs[0].routedDeviceType).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertThat(h.audioFactory.configs[0].playbackStream).isEqualTo(AudioManager.STREAM_VOICE_CALL)

        h.session.selectAudioDevice(1)

        awaitUntil(description = "audio rebuilt for the earpiece") {
            h.mainLooper.idle()
            h.audioFactory.created.size == 2
        }
        assertThat(h.audioFactory.configs[1].routedDeviceType).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        assertThat(h.audioFactory.configs[1].playbackStream).isEqualTo(AudioManager.STREAM_VOICE_CALL)
    }

    @Test
    fun theScoRouteBecomingActiveRebuildsThePipelineOffTheMainThread() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        awaitUntil(description = "audio created") { h.mainLooper.idle(); h.audioFactory.created.size == 1 }
        assertThat(h.audioFactory.configs[0].routedDeviceType).isNull()

        h.session.setBluetoothAutomatic(true)

        awaitUntil(description = "audio rebuilt for sco") { h.mainLooper.idle(); h.audioFactory.created.size == 2 }
        assertThat(h.audioFactory.configs[1].routedDeviceType).isEqualTo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertThat(h.audioFactory.createThreads.distinct())
            .containsExactly(se.lublin.humla.audio.AudioController.THREAD_NAME)
    }

    /** The system taking the route away is the same event as us taking it: one rebuild, no more. */
    @Test
    fun aRouteTheSystemChangesRebuildsThePipelineToo() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        awaitUntil(description = "audio created") { h.mainLooper.idle(); h.audioFactory.created.size == 1 }
        h.session.setBluetoothAutomatic(true)
        awaitUntil(description = "audio rebuilt for sco") { h.mainLooper.idle(); h.audioFactory.created.size == 2 }

        h.devices.systemSelects(null) // the headset walked away

        awaitUntil(description = "audio rebuilt without sco") { h.mainLooper.idle(); h.audioFactory.created.size == 3 }
        assertThat(h.audioFactory.configs[2].routedDeviceType).isNull()
        assertThat(h.session.audio.router.isBluetoothActive).isFalse()
        assertThat(h.session.audio.router.bluetoothAutomatic).isTrue() // still wanted; the headset is not there
    }

    /** The canceller follows the routed device: on for the phone's own speakers, off on a headset. */
    @Test
    fun echoCancellationFollowsTheRoutedDevice() {
        val h = start()
        h.phone()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        assertThat(h.echo).isEqualTo(EchoCancellationMode.WEBRTC) // speaker

        h.session.setBluetoothAutomatic(true)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.NONE)
        assertThat(h.session.isEchoCancellationEnabled).isFalse()

        h.session.selectAudioDevice(1)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.WEBRTC) // earpiece
        assertThat(h.session.isEchoCancellationEnabled).isTrue()
    }

    /**
     * The user's override is kept per kind of device and applied whenever that kind is routed
     * again; a kind without one keeps its default. Live, through the ordinary reconfigure.
     */
    @Test
    fun anOverrideAppliesToItsKindOfDeviceOnly() {
        val h = start()
        h.phone()
        h.connectAndSynchronize()
        awaitUntil(description = "audio created") { h.mainLooper.idle(); h.audioFactory.created.size == 1 }
        assertThat(h.audioFactory.configs[0].echoCancellation).isEqualTo(EchoCancellationMode.WEBRTC)

        h.configureAudio { copy(echoCancellationOverrides = mapOf(AudioDeviceCategory.SPEAKER to false)) }

        awaitUntil(description = "audio rebuilt without echo") { h.mainLooper.idle(); h.audioFactory.created.size == 2 }
        assertThat(h.audioFactory.configs[1].echoCancellation).isEqualTo(EchoCancellationMode.NONE)

        h.session.selectAudioDevice(1)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.WEBRTC) // earpiece: default

        h.session.selectAudioDevice(2)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.NONE) // speaker: override
    }

    @Test
    fun withoutARouteThereIsNoEchoCancellation() {
        val h = start()
        h.phone()

        assertThat(h.session.isEchoCancellationEnabled).isFalse()
    }

    /**
     * An OEM that enforces `BLUETOOTH_CONNECT` on `android.media` gets a caught `SecurityException`,
     * an answer that reads as "no headset", and one chat line per session. Runs against the
     * real `AndroidCommunicationDevices`.
     */
    @Test
    @Config(shadows = [AndroidCommunicationDevicesTest.DenyingAudioManagerShadow::class])
    fun aPlatformRefusalIsReportedOnceAsAChatLine() {
        val h = HumlaSessionHarness(devices = null).also { harnesses += it }
        h.connectAndSynchronize()

        h.session.setBluetoothAutomatic(true)
        h.session.setBluetoothAutomatic(false)
        h.session.setBluetoothAutomatic(true)

        val line = h.string(R.string.bluetooth_sco_denied)
        assertThat(h.warnings.filter { it == line }).hasSize(1)
        // And the wish stands: the user asked for a headset, the platform said no.
        assertThat(h.session.audio.router.bluetoothAutomatic).isTrue()
        assertThat(h.session.audio.router.isBluetoothActive).isFalse()
    }
}

private val HumlaSessionHarness.echo: EchoCancellationMode
    get() = session.audio.config.echoCancellation
