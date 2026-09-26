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

import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.AudioController
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.Constants
import se.lublin.humla.util.MumbleVersion
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The audio pipeline is built and torn down on `humla-audio-control`, never on the main thread,
 * and its failures reach the chat log. `AudioHandler.shutdown()` joins the capture and playback
 * threads, which on the main looper would be an ANR.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceAudioTest {
    private val harnesses = mutableListOf<HumlaServiceHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.destroy() }
    }

    private fun start(): HumlaServiceHarness = HumlaServiceHarness().also { harnesses += it }

    private fun audioUp(h: HumlaServiceHarness, count: Int = 1) =
        awaitUntil(description = "audio pipeline $count") {
            h.mainLooper.idle()
            h.audioFactory.created.size == count
        }

    // ---------------------------------------------------------------- the pipeline's lifecycle

    @Test
    fun audioIsBuiltOnTheControlThreadWithTheSessionUser() {
        val h = start()
        h.connectAndSynchronize()

        audioUp(h)
        assertThat(h.audioFactory.createThreads.single()).isEqualTo(AudioController.THREAD_NAME)
        assertThat(h.audioFactory.sessionParams[0].self.name).isEqualTo("me")
        assertThat(h.audioFactory.sessionParams[0].maxBandwidth).isEqualTo(72_000)
        assertThat(h.service.currentBandwidth).isEqualTo(12_345)
    }

    /** The voice format the server's Version picked reaches the pipeline, before any voice is sent. */
    @Test
    fun thePipelineSendsInTheFormatTheConnectionNegotiated() {
        val h = start()
        h.service.connect()
        val tcp = h.openSocket(0)
        val version = Mumble.Version.newBuilder().setVersionV2(MumbleVersion.v2(1, 5, 0)).build()
        tcp.simulateMessage(HumlaTCPMessageType.Version, version.toByteArray())
        h.synchronize(tcp)

        audioUp(h)
        assertThat(h.service.getConnection()!!.udpProtocol).isEqualTo(UdpProtocol.PROTOBUF)
        assertThat(h.audioFactory.sessionParams[0].udpProtocol).isEqualTo(UdpProtocol.PROTOBUF)
    }

    /**
     * The first pipeline is built from the settings and the input mode in force, not from defaults.
     * Half duplex resolves against the transmit mode the config carries.
     */
    @Test
    fun theFirstPipelineIsBuiltFromTheSettingsInForce() {
        val h = start()
        h.configure {
            copy(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK, halfDuplex = true, inputQuality = 24_000)
        }

        h.connectAndSynchronize()
        audioUp(h)

        val config = h.audioFactory.configs[0]
        assertThat(config.targetBitrate).isEqualTo(24_000)
        assertThat(config.transmitMode).isEqualTo(Constants.TRANSMIT_PUSH_TO_TALK)
        assertThat(config.halfDuplex).isTrue()
        assertThat(config.halfDuplexRequested).isTrue()
        // By identity: the toggle the capture loop consults is the toggle a key press writes.
        assertThat(h.audioFactory.sessionParams[0].inputMode)
            .isSameInstanceAs(inputModeOf(h))
        h.service.setTalkingState(true)
        assertThat(
            (h.audioFactory.sessionParams[0].inputMode as se.lublin.humla.audio.inputmode.ToggleInputMode)
                .isTalkingOn()
        ).isTrue()
    }

    private fun inputModeOf(h: HumlaServiceHarness): Any = h.service.inputMode

    /**
     * A voice target set while the socket is up but before synchronization reaches the pipeline
     * built for that session; this is the only window where it is non-zero at build time.
     */
    @Test
    fun aVoiceTargetSetWhileConnectingReachesTheFirstPipeline() {
        val h = start()
        h.service.connect()
        val tcp = h.openSocket(0)

        h.service.voiceTargetId = 5
        h.synchronize(tcp)

        audioUp(h)
        assertThat(h.audioFactory.sessionParams[0].targetId).isEqualTo(5.toByte())
    }

    /** And the next session starts clean: the slots the old one held are not carried over. */
    @Test
    fun aNewSessionStartsWithoutThePreviousVoiceTarget() {
        val h = start()
        h.service.connect()
        val tcp = h.openSocket(0)
        h.service.voiceTargetId = 5
        h.synchronize(tcp)
        audioUp(h)

        h.service.disconnect()
        h.mainLooper.idle()
        // Set after the disconnect cleared it, so what must clear it again is startSession's own
        // reset.
        h.service.voiceTargetId = 9
        h.service.connect()
        h.synchronize(h.openSocket(1))

        audioUp(h, count = 2)
        assertThat(h.service.voiceTargetId).isEqualTo(0.toByte())
        assertThat(h.audioFactory.sessionParams[1].targetId).isEqualTo(0.toByte())
    }

    /**
     * A ServerSync whose session id names no user leaves the session up and says so, rather than
     * building a pipeline with nothing to stamp packets with.
     */
    @Test
    fun aServerSyncWithoutASessionUserSaysSoAndBuildsNoPipeline() {
        val h = start()
        h.service.connect()
        val tcp = h.openSocket(0)

        // ServerSync for a session the user list never mentioned.
        tcp.simulateMessage(
            se.lublin.humla.net.HumlaTCPMessageType.ServerSync,
            se.lublin.humla.protobuf.Mumble.ServerSync.newBuilder()
                .setSession(4242).setMaxBandwidth(72_000).build().toByteArray(),
        )
        awaitUntil(description = "the sync is reported") {
            h.mainLooper.idle()
            h.service.connectionState == HumlaService.ConnectionState.CONNECTED
        }

        assertThat(h.warnings).contains(h.service.getString(R.string.no_session_user))
        assertThat(h.audioFactory.created).isEmpty()
    }

    /** No main-thread join in disconnect. */
    @Test
    fun disconnectDoesNotBlockTheMainThreadWhileAudioTearsDown() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)
        val audio = h.audioFactory.created[0]
        val gate = CountDownLatch(1)
        audio.shutdownGate = gate

        val started = System.nanoTime()
        h.service.disconnect()
        h.mainLooper.idle()
        val blockedMillis = (System.nanoTime() - started) / 1_000_000

        awaitUntil(description = "shutdown started") { audio.shutdownThread != null }
        assertThat(audio.shutdownThread).isEqualTo(AudioController.THREAD_NAME)
        assertThat(audio.shutdownCalls.get()).isEqualTo(0) // still inside shutdown()

        // The main looper keeps running while the audio thread is stuck in the join.
        val probeRan = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { probeRan.set(true) }
        h.mainLooper.idle()
        assertThat(probeRan.get()).isTrue()
        assertThat(blockedMillis).isLessThan(200)

        gate.countDown()
        awaitUntil(description = "shutdown finished") { audio.shutdownCalls.get() == 1 }
    }

    @Test
    fun aLostConnectionTearsThePipelineDown() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.failConnection(
            0,
            se.lublin.humla.exception.HumlaException(
                "gone",
                se.lublin.humla.exception.HumlaException.HumlaDisconnectReason.CONNECTION_ERROR,
            ),
        )

        awaitUntil(description = "pipeline stopped") { h.audioFactory.created[0].shutdownCalls.get() == 1 }
        assertThat(h.service.currentBandwidth).isEqualTo(-1)
    }

    /**
     * `onDestroy` must stop the control thread. Asserted on the thread object rather than by name,
     * because a name filter over all threads passes if the thread is renamed.
     */
    @Test
    fun destroyingTheServiceStopsTheAudioControlThread() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)
        val controller = controllerOf(h)
        assertThat(controller.thread.isAlive).isTrue()

        h.destroy()
        harnesses.remove(h)

        awaitUntil(description = "the control thread ended") { !controller.thread.isAlive }
    }

    private fun controllerOf(h: HumlaServiceHarness): AudioController = h.service.audioController

    /**
     * A disconnect between the server's sync and its delivery on the main looper: no pipeline is
     * built, since a microphone opened for a dead connection is worse than none.
     */
    @Test
    fun aDisconnectThatBeatsTheSyncCallbackBuildsNoPipeline() {
        val h = start()
        h.service.connect()
        val tcp = h.openSocket(0)
        h.synchronizeWithoutDraining(tcp)

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.audioFactory.created).isEmpty()
        assertThat(h.service.connectionState)
            .isNotEqualTo(HumlaService.ConnectionState.CONNECTED)
    }

    // ---------------------------------------------------------------- problems are visible

    @Test
    fun audioCreationFailureIsLoggedAsAWarning() {
        val h = start()
        h.audioFactory.failWith = AudioInitializationException("no microphone")

        h.connectAndSynchronize()
        awaitUntil(description = "the failure reaches the chat log") {
            h.mainLooper.idle()
            h.warnings.contains("no microphone")
        }

        // A microphone that cannot open is not a reason to drop the session.
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTED)
    }

    @Test
    fun anAudioWarningReachesTheChatLog() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.audioFactory.created[0].warningListener!!.invoke("microphone silenced by the system")
        awaitUntil(description = "the warning reaches the chat log") {
            h.mainLooper.idle()
            h.warnings.contains("microphone silenced by the system")
        }
    }

    @Test
    fun aConnectionWarningReachesTheChatLog() {
        val h = start()
        h.connectAndSynchronize()
        awaitUntil(description = "udp transport") { h.mainLooper.idle(); h.transports.udps.isNotEmpty() }

        h.transports.udps[0].simulateError(IOException("network unreachable"))
        awaitUntil(description = "the warning reaches the chat log") {
            h.mainLooper.idle()
            h.warnings.contains(h.service.getString(R.string.udp_warning_thread_failed))
        }
    }

    // ---------------------------------------------------------------- the settings that rebuild

    @Test
    fun changingAnAudioSettingWhileConnectedRebuildsThePipeline() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.configure { copy(audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION) }

        audioUp(h, count = 2)
        assertThat(h.audioFactory.configs[1].audioSource)
            .isEqualTo(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        awaitUntil(description = "the old pipeline is gone") {
            h.audioFactory.created[0].shutdownCalls.get() == 1
        }
    }

    /**
     * The rebuild is decided by the value: re-applying an unchanged setting must not cost a
     * rebuild with the microphone dead in the middle.
     */
    @Test
    fun aSettingAppliedWithTheSameValueDoesNotRebuildThePipeline() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)
        val sourceInUse = h.audioFactory.configs[0].audioSource

        h.configure { copy(audioSource = sourceInUse) }
        awaitUntil(description = "the reconfigure was processed") { h.service.currentBandwidth == 12_345 }

        assertThat(h.audioFactory.created).hasSize(1)
        assertThat(h.audioFactory.created[0].shutdownCalls.get()).isEqualTo(0)
    }

    /**
     * The VAD configuration reaches the live [se.lublin.humla.audio.inputmode.ActivityInputMode]
     * without tearing down the capture chain.
     */
    @Test
    fun aLiveSettingDoesNotRebuildThePipeline() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.configure { copy(vadConfig = VadConfig.amplitude(0.8f, 120L)) }
        awaitUntil(description = "the reconfigure was processed") { h.service.currentBandwidth == 12_345 }

        assertThat(h.audioFactory.created).hasSize(1)
        assertThat(h.audioFactory.created[0].shutdownCalls.get()).isEqualTo(0)
    }

    /** Half duplex is resolved against the transmit mode in force. */
    @Test
    fun halfDuplexOnlyAppliesToPushToTalk() {
        val h = start()

        h.configure { copy(halfDuplex = true, transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY) }
        assertThat(h.service.getAudioConfigForTest().halfDuplex).isFalse()

        h.configure { copy(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK) }
        assertThat(h.service.getAudioConfigForTest().halfDuplex).isTrue()

        // Both directions: the flag is what the caller wrote, not a constant.
        h.configure { copy(halfDuplex = false) }
        assertThat(h.service.getAudioConfigForTest().halfDuplexRequested).isFalse()
        assertThat(h.service.getAudioConfigForTest().halfDuplex).isFalse()
    }

    // ---------------------------------------------------------------- voice targets

    /**
     * The target reaches the running pipeline and the session behind it, so the next rebuild keeps
     * targeting it. A connect clears the target, and the whisper slots with it.
     */
    @Test
    fun theVoiceTargetReachesTheRunningPipelineAndSurvivesARebuild() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.service.voiceTargetId = 0x1F

        assertThat(h.service.voiceTargetId).isEqualTo(0x1F.toByte())
        awaitUntil(description = "the target reaches the pipeline") {
            h.audioFactory.created[0].targetIds.contains(0x1F.toByte())
        }

        h.configure { copy(audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION) }

        audioUp(h, count = 2)
        assertThat(h.audioFactory.sessionParams[1].targetId).isEqualTo(0x1F.toByte())
    }

    /** Setting one while there is no pipeline is remembered and no longer a crash. */
    @Test
    fun aVoiceTargetSetWhileDisconnectedIsHarmless() {
        val h = start()

        h.service.voiceTargetId = 3

        assertThat(h.service.voiceTargetId).isEqualTo(3.toByte())
        assertThat(h.service.voiceTargetMode)
            .isEqualTo(se.lublin.humla.util.VoiceTargetMode.WHISPER)
    }

    /** A negative byte does not pass the five-bit guard. */
    @Test
    fun aVoiceTargetIdThatDoesNotFitInFiveBitsIsRefused() {
        val h = start()

        for (id in listOf(0x20, 0x80, 0xFF)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                h.service.voiceTargetId = id.toByte()
            }
        }
        assertThat(h.service.voiceTargetId).isEqualTo(0.toByte())
    }
}
