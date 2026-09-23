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
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.humla.session.AndroidCommunicationDevicesTest
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.HumlaException
import java.util.concurrent.TimeUnit

/**
 * Spec A4: the user's wish for a Bluetooth headset and the route the platform actually holds are
 * two different questions, and the first survives everything the second does not.
 *
 * `BluetoothScoReceiver` and the deprecated `startBluetoothSco()` are gone; the route goes through
 * [se.lublin.humla.session.AudioRouter] over [se.lublin.humla.session.CommunicationDevices], which is
 * the API this module's minSdk of 31 has. No permission is consulted before routing and none can
 * be - see the spec 4.1 ruling quoted on `AndroidCommunicationDevices`.
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

    // ---------------------------------------------------------------- wanted vs active

    @Test
    fun theWishAndTheRouteAreDifferentQuestions() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()

        assertThat(h.service.usingBluetoothSco()).isFalse()
        assertThat(h.service.isBluetoothScoActive()).isFalse()

        h.service.enableBluetoothSco()

        assertThat(h.devices!!.selectCalls).containsExactly(7)
        assertThat(h.service.usingBluetoothSco()).isTrue()
        assertThat(h.service.isBluetoothScoActive()).isTrue()

        h.service.disableBluetoothSco()

        assertThat(h.devices!!.clearCalls).isEqualTo(1)
        assertThat(h.service.usingBluetoothSco()).isFalse()
        assertThat(h.service.isBluetoothScoActive()).isFalse()
    }

    /**
     * A platform that refuses the route is one chat line, not one per attempt. The router raises
     * `onRouteRefused` per `apply()` on purpose - it has no clock and no chat log - so the
     * de-duplication is this service's, against the last line it delivered, the way
     * `HumlaConnection.warn` does it.
     */
    @Test
    fun aRefusedRouteIsOneChatLine() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.devices!!.selectResult = false
        h.connectAndSynchronize()

        h.service.enableBluetoothSco()
        h.service.enableBluetoothSco()

        val line = h.service.getString(R.string.audio_route_refused)
        assertThat(h.warnings.filter { it == line }).hasSize(1)

        // A different line in between ends the suppression: the rule is about repetition, not
        // about the message ever appearing twice in a session.
        h.service.logWarning("something else")
        h.service.enableBluetoothSco()
        assertThat(h.warnings.filter { it == line }).hasSize(2)
    }

    /** No headset is not a failure any more: the default without one is the phone itself. */
    @Test
    fun wantingAHeadsetThatIsNotThereSaysNothing() {
        val h = start()
        h.connectAndSynchronize()

        h.service.enableBluetoothSco()

        assertThat(h.warnings).isEmpty()
    }

    // ---------------------------------------------------------------- the route across a session

    /**
     * Spec section 6 regression test: "Bluetooth after reconnect". The route is dropped with every
     * connection, the *wish* is not, and a synchronized session restores it. This is the half of
     * the screen-off complaint that is audible: the headset went silent after a reconnect and only
     * a manual toggle brought it back.
     */
    @Test
    fun bluetoothScoIsRestartedAfterAReconnect() {
        val h = start(autoReconnect = true)
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.service.enableBluetoothSco()
        assertThat(h.devices!!.selectCalls).containsExactly(7)
        assertThat(h.service.isBluetoothScoActive()).isTrue()

        h.failConnection(0, connectionError())

        assertThat(h.service.getSessionState().value)
            .isInstanceOf(SessionState.ConnectionLost::class.java)
        assertThat(h.service.usingBluetoothSco()).isTrue() // the wish survives the loss
        assertThat(h.service.isBluetoothScoActive()).isFalse() // the route does not

        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer
        h.synchronize(h.openSocket(1))

        assertThat(h.devices!!.selectCalls).containsExactly(7, 7).inOrder()
        assertThat(h.service.isBluetoothScoActive()).isTrue()
    }

    @Test
    fun aUserDisconnectReleasesTheRouteAndKeepsTheWish() {
        val h = start(autoReconnect = true)
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        h.service.enableBluetoothSco()

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Disconnected())
        assertThat(h.devices!!.clearCalls).isEqualTo(1)
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
        assertThat(h.devices!!.listener).isNotNull()

        h.destroy()
        harnesses.remove(h)

        assertThat(h.devices!!.clearCalls).isEqualTo(1)
        assertThat(h.devices!!.listener).isNull()
    }

    /**
     * Routing voice with no voice to route holds an SCO link open for nothing. This test used to pin
     * the opposite - a route taken and held without a session, which `onDestroy` then had to give
     * back - and the release it pinned is now the router's: nothing is taken before a session is
     * synchronized, so there is nothing for the destroy to find.
     */
    @Test
    fun noRouteIsTakenWithoutASession() {
        val h = HumlaServiceHarness().also { harnesses += it }
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        h.service.enableBluetoothSco()
        h.devices!!.deviceArrives(9, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        assertThat(h.devices!!.selectCalls).isEmpty()
        assertThat(h.service.usingBluetoothSco()).isTrue()

        h.destroy()
        harnesses.remove(h)

        assertThat(h.devices!!.clearCalls).isEqualTo(0)
    }

    // ---------------------------------------------------------------- the chooser

    private fun HumlaServiceHarness.phone() {
        devices!!.available[1] = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        devices!!.available[2] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    /** The chooser's three calls, through the session interface the UI holds. */
    @Test
    fun theUserPicksTheDeviceThroughTheSession() {
        val h = start()
        h.phone()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.devices!!.names[7] = "Jabra"
        h.connectAndSynchronize()
        val session: IHumlaSession = h.service

        assertThat(session.audioDevices.map { it.id }).containsExactly(1, 2, 7).inOrder()
        assertThat(session.activeAudioDevice?.id).isEqualTo(2)

        session.selectAudioDevice(1)

        assertThat(h.devices!!.selectedId).isEqualTo(1)
        assertThat(session.activeAudioDevice?.id).isEqualTo(1)
    }

    @Test
    fun theChooserIsEmptyWithoutASession() {
        val h = start()
        h.phone()

        assertThat(h.service.audioDevices).isEmpty()
        assertThat(h.service.activeAudioDevice).isNull()
    }

    /** Handset mode is the voice-call stream, and there the default shown is the earpiece. */
    @Test
    fun theDefaultFollowsThePlaybackStream() {
        val h = start()
        h.phone()
        h.configure { putInt(HumlaService.EXTRAS_AUDIO_STREAM, AudioManager.STREAM_VOICE_CALL) }
        h.connectAndSynchronize()

        assertThat(h.service.activeAudioDevice?.id).isEqualTo(1)
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

        assertThat(h.devices!!.selectedId).isEqualTo(1)
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

        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Disconnected())
        h.service.connect()
        h.connectAndSynchronize(1)
        assertThat(h.devices!!.selectCalls).containsExactly(1)
        assertThat(h.service.activeAudioDevice?.id).isEqualTo(2)
    }

    /**
     * A routed device only carries the voice when the track is on the voice-call stream: a
     * media-stream track does not follow the communication device. So choosing one rebuilds the
     * pipeline onto that stream, and giving the route back rebuilds it onto the stream the
     * settings chose.
     */
    @Test
    fun aChosenDeviceMovesPlaybackToTheVoiceCallStream() {
        val h = start()
        h.phone()
        h.connectAndSynchronize()
        awaitUntil(description = "audio created") { h.mainLooper.idle(); h.audioFactory.created.size == 1 }
        assertThat(h.audioFactory.configs[0].playbackStream).isEqualTo(AudioManager.STREAM_MUSIC)

        h.service.selectAudioDevice(1)

        awaitUntil(description = "audio rebuilt for the earpiece") { h.mainLooper.idle(); h.audioFactory.created.size == 2 }
        assertThat(h.audioFactory.configs[1].routedDeviceType).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        assertThat(h.audioFactory.configs[1].playbackStream).isEqualTo(AudioManager.STREAM_VOICE_CALL)

        h.service.selectAudioDevice(2)

        awaitUntil(description = "audio rebuilt for the default") { h.mainLooper.idle(); h.audioFactory.created.size == 3 }
        assertThat(h.audioFactory.configs[2].routedDeviceType).isNull()
        assertThat(h.audioFactory.configs[2].playbackStream).isEqualTo(AudioManager.STREAM_MUSIC)
    }

    // ---------------------------------------------------------------- the route and the pipeline

    @Test
    fun theScoRouteBecomingActiveRebuildsThePipelineOffTheMainThread() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()
        awaitUntil(description = "audio created") { h.mainLooper.idle(); h.audioFactory.created.size == 1 }
        assertThat(h.audioFactory.configs[0].bluetoothActive).isFalse()

        h.service.enableBluetoothSco()

        awaitUntil(description = "audio rebuilt for sco") { h.mainLooper.idle(); h.audioFactory.created.size == 2 }
        assertThat(h.audioFactory.configs[1].bluetoothActive).isTrue()
        assertThat(h.audioFactory.createThreads.distinct())
            .containsExactly(se.lublin.humla.session.AudioController.THREAD_NAME)
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

        h.devices!!.systemSelects(null) // the headset walked away

        awaitUntil(description = "audio rebuilt without sco") { h.mainLooper.idle(); h.audioFactory.created.size == 3 }
        assertThat(h.audioFactory.configs[2].bluetoothActive).isFalse()
        assertThat(h.service.isBluetoothScoActive()).isFalse()
        assertThat(h.service.usingBluetoothSco()).isTrue() // still wanted; the headset is not there
    }

    // ---------------------------------------------------------------- the extra

    @Test
    fun theBluetoothExtraDrivesTheWishAndNeedsNoReconnect() {
        val h = start()
        h.devices!!.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        h.connectAndSynchronize()

        assertThat(
            h.service.configureExtras(
                Bundle().apply { putBoolean(HumlaService.EXTRAS_BLUETOOTH_WANTED, true) },
            )
        ).isFalse()

        assertThat(h.service.usingBluetoothSco()).isTrue()
        assertThat(h.devices!!.selectCalls).containsExactly(7)

        h.service.configureExtras(
            Bundle().apply { putBoolean(HumlaService.EXTRAS_BLUETOOTH_WANTED, false) },
        )

        assertThat(h.service.usingBluetoothSco()).isFalse()
        assertThat(h.devices!!.clearCalls).isEqualTo(1)
    }

    /**
     * The device seam is reachable after `onCreate`, whether a test set it or the service wrapped
     * the platform itself. The next task on this line - "take the Bluetooth headphones, or the
     * speaker, and if headphones are plugged in take those by themselves" - is a chooser over
     * `available`/`select`, and this is the handle it docks onto. Without this line the
     * only reference lived inside the router's constructor call.
     */
    @Test
    fun theDeviceSeamIsReachableAfterOnCreate() {
        val withFake = start()
        assertThat(withFake.service.communicationDevices).isSameInstanceAs(withFake.devices)

        val withPlatform = HumlaServiceHarness(devices = null).also { harnesses += it }
        assertThat(withPlatform.service.communicationDevices)
            .isInstanceOf(se.lublin.humla.session.AndroidCommunicationDevices::class.java)
    }

    // ---------------------------------------------------------------- the platform refusing

    /**
     * Spec 4.1, second half: "absent from the annotation database" is not "throws nowhere", so an
     * OEM that enforces `BLUETOOTH_CONNECT` on `android.media` gets a caught `SecurityException`,
     * an answer that reads as "no headset", and **one** chat line per service life. This runs
     * against the real `AndroidCommunicationDevices` the service builds when no seam is set - the
     * `onSecurityDenial` callback has no default precisely so that it cannot be forgotten here.
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
        assertThat(h.service.isBluetoothScoActive()).isFalse()
    }
}
