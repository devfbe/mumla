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
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.TimeUnit

/**
 * The user's wish for a Bluetooth headset and the route the platform actually holds are separate:
 * the wish survives everything the route does not. Routing goes through
 * [se.lublin.humla.audio.routing.AudioRouter] over [se.lublin.humla.audio.routing.CommunicationDevices].
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceBluetoothTest {
    private val harnesses = mutableListOf<HumlaServiceHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.destroy() }
    }

    private fun start(autoReconnect: Boolean = false): HumlaServiceHarness =
        HumlaServiceHarness(autoReconnect = autoReconnect).also { harnesses += it }

    private fun connectionError() =
        HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

    @Test
    fun theWishAndTheRouteAreDifferentQuestions() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()

        assertThat(h.service.usingBluetoothSco()).isFalse()
        assertThat(h.service.isBluetoothScoActive).isFalse()

        h.service.enableBluetoothSco()

        assertThat(h.devices.selectCalls).containsExactly(7)
        assertThat(h.service.usingBluetoothSco()).isTrue()
        assertThat(h.service.isBluetoothScoActive).isTrue()

        h.service.disableBluetoothSco()

        assertThat(h.devices.clearCalls).isEqualTo(1)
        assertThat(h.service.usingBluetoothSco()).isFalse()
        assertThat(h.service.isBluetoothScoActive).isFalse()
    }

    /**
     * A platform that refuses the route is one chat line, not one per attempt. The router reports
     * every refusal; this service de-duplicates against the last line it delivered.
     */
    @Test
    fun aRefusedRouteIsOneChatLine() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.devices.selectResult = false
        h.connectAndSynchronize()

        h.service.enableBluetoothSco()
        h.service.enableBluetoothSco()

        val line = h.service.getString(R.string.audio_route_refused)
        assertThat(h.warnings.filter { it == line }).hasSize(1)

        // A different line in between ends the suppression: the rule is about repetition.
        h.service.logWarning("something else")
        h.service.enableBluetoothSco()
        assertThat(h.warnings.filter { it == line }).hasSize(2)
    }

    /** Without a headset the default route is the phone itself, not a failure. */
    @Test
    fun wantingAHeadsetThatIsNotThereSaysNothing() {
        val h = start()
        h.connectAndSynchronize()

        h.service.enableBluetoothSco()

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
        h.service.enableBluetoothSco()
        assertThat(h.devices.selectCalls).containsExactly(7)
        assertThat(h.service.isBluetoothScoActive).isTrue()

        h.failConnection(0, connectionError())

        assertThat(h.service.sessionState.value)
            .isInstanceOf(SessionState.ConnectionLost::class.java)
        assertThat(h.service.usingBluetoothSco()).isTrue() // the wish survives the loss
        assertThat(h.service.isBluetoothScoActive).isFalse() // the route does not

        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer
        h.synchronize(h.openSocket(1))

        assertThat(h.devices.selectCalls).containsExactly(7, 7).inOrder()
        assertThat(h.service.isBluetoothScoActive).isTrue()
    }

    @Test
    fun aUserDisconnectReleasesTheRouteAndKeepsTheWish() {
        val h = start(autoReconnect = true)
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.service.enableBluetoothSco()

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.devices.clearCalls).isEqualTo(1)
        // The wish is not a session resource: it is the user's setting until they change it.
        assertThat(h.service.usingBluetoothSco()).isTrue()
    }

    /** Destroying the service gives the route back, and takes the listener off the platform. */
    @Test
    fun destroyingTheServiceReleasesTheRouteAndTheListener() {
        val h = HumlaServiceHarness().also { harnesses += it }
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.service.enableBluetoothSco()
        assertThat(h.devices.listener).isNotNull()

        h.destroy()
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
        val h = HumlaServiceHarness().also { harnesses += it }
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        h.service.enableBluetoothSco()
        h.devices.deviceArrives(9, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        assertThat(h.devices.selectCalls).isEmpty()
        assertThat(h.service.usingBluetoothSco()).isTrue()

        h.destroy()
        harnesses.remove(h)

        assertThat(h.devices.clearCalls).isEqualTo(0)
    }

    private fun HumlaServiceHarness.phone() {
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
        val session: IHumlaSession = h.service

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

        assertThat(h.service.audioDevices).isEmpty()
        assertThat(h.service.activeAudioDevice).isNull()
    }

    /** The saved device reaches the router with the config, live. */
    @Test
    fun theSavedDeviceIsTheDefaultWhenTheConfigNamesIt() {
        val h = start()
        h.phone()
        h.configureAudio { copy(preferredDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) }
        h.connectAndSynchronize()

        assertThat(h.service.activeAudioDevice?.id).isEqualTo(1)

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
        val session: IHumlaSession = h.service
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

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.devices.inCommunicationMode).isFalse()
    }

    /** The choice is the wish, and like the headset wish it outlives a dropped connection. */
    @Test
    fun aChoiceSurvivesAReconnect() {
        val h = start(autoReconnect = true)
        h.phone()
        h.connectAndSynchronize()
        h.service.selectAudioDevice(1)

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
        h.service.selectAudioDevice(1)

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Disconnected())
        h.service.connect()
        h.connectAndSynchronize(1)
        assertThat(h.devices!!.selectCalls).containsExactly(2, 1, 2).inOrder()
        assertThat(h.service.activeAudioDevice?.id).isEqualTo(2)
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

        h.service.selectAudioDevice(1)

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

        h.service.enableBluetoothSco()

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
        h.service.enableBluetoothSco()
        awaitUntil(description = "audio rebuilt for sco") { h.mainLooper.idle(); h.audioFactory.created.size == 2 }

        h.devices.systemSelects(null) // the headset walked away

        awaitUntil(description = "audio rebuilt without sco") { h.mainLooper.idle(); h.audioFactory.created.size == 3 }
        assertThat(h.audioFactory.configs[2].routedDeviceType).isNull()
        assertThat(h.service.isBluetoothScoActive).isFalse()
        assertThat(h.service.usingBluetoothSco()).isTrue() // still wanted; the headset is not there
    }

    /** The device seam is reachable after `onCreate`, whether a test set it or the service built it. */
    @Test
    fun theDeviceSeamIsReachableAfterOnCreate() {
        val withFake = start()
        assertThat(withFake.service.communicationDevices).isSameInstanceAs(withFake.devices)

        val withPlatform = HumlaServiceHarness(devices = null).also { harnesses += it }
        assertThat(withPlatform.service.communicationDevices)
            .isInstanceOf(se.lublin.humla.audio.routing.AndroidCommunicationDevices::class.java)
    }

    /** The canceller follows the routed device: on for the phone's own speakers, off on a headset. */
    @Test
    fun echoCancellationFollowsTheRoutedDevice() {
        val h = start()
        h.phone()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        assertThat(h.echo).isEqualTo(EchoCancellationMode.WEBRTC) // speaker

        h.service.enableBluetoothSco()
        assertThat(h.echo).isEqualTo(EchoCancellationMode.NONE)
        assertThat(h.service.isEchoCancellationEnabled).isFalse()

        h.service.selectAudioDevice(1)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.WEBRTC) // earpiece
        assertThat(h.service.isEchoCancellationEnabled).isTrue()
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

        h.service.selectAudioDevice(1)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.WEBRTC) // earpiece: default

        h.service.selectAudioDevice(2)
        assertThat(h.echo).isEqualTo(EchoCancellationMode.NONE) // speaker: override
    }

    @Test
    fun withoutARouteThereIsNoEchoCancellation() {
        val h = start()
        h.phone()

        assertThat(h.service.isEchoCancellationEnabled).isFalse()
    }

    /**
     * An OEM that enforces `BLUETOOTH_CONNECT` on `android.media` gets a caught `SecurityException`,
     * an answer that reads as "no headset", and one chat line per service life. Runs against the
     * real `AndroidCommunicationDevices`.
     */
    @Test
    @Config(shadows = [AndroidCommunicationDevicesTest.DenyingAudioManagerShadow::class])
    fun aPlatformRefusalIsReportedOnceAsAChatLine() {
        val h = HumlaServiceHarness(devices = null).also { harnesses += it }
        h.connectAndSynchronize()

        h.service.enableBluetoothSco()
        h.service.enableBluetoothSco()

        val line = h.service.getString(R.string.bluetooth_sco_denied)
        assertThat(h.warnings.filter { it == line }).hasSize(1)
        // And the wish stands: the user asked for a headset, the platform said no.
        assertThat(h.service.usingBluetoothSco()).isTrue()
        assertThat(h.service.isBluetoothScoActive).isFalse()
    }
}

private val HumlaServiceHarness.echo: EchoCancellationMode
    get() = service.getAudioConfigForTest().echoCancellation
