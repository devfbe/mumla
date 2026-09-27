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

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import se.lublin.humla.audio.AudioConfig
import se.lublin.humla.audio.AudioSettings
import se.lublin.humla.audio.PipelineSettings
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Server
import se.lublin.humla.net.ConnectionWarning
import se.lublin.humla.net.TestCertificates
import se.lublin.humla.session.ConnectionConfig
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.EventRecorder
import se.lublin.humla.testutil.HumlaSessionHarness
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.testutil.testSession
import se.lublin.humla.util.VoiceTargetMode

/**
 * Characterization of [HumlaSession] without a live connection: construction, configuration, the
 * reasons a connection ends with, and the disconnected arm of the session API.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaSessionCharacterizationTest {
    private val server = Server(-1, "test", "127.0.0.1", 64738, "me", "")
    private val sessions = mutableListOf<HumlaSession>()
    private val harnesses = mutableListOf<HumlaSessionHarness>()

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        harnesses.forEach { it.close() }
    }

    private fun session(config: SessionConfig = SessionConfig()): HumlaSession =
        testSession(config).also { sessions += it }

    private fun harness(): HumlaSessionHarness = HumlaSessionHarness().also { harnesses += it }

    @Test
    fun startsDisconnectedWithNothingBuilt() {
        val session = session(SessionConfig(ConnectionConfig(server = server)))

        assertThat(session.state.value).isEqualTo(SessionState.Disconnected())
        assertThat(session.connection).isNull()
        assertThat(session.modelHandler).isNull()
        assertThat(session.targetServer).isSameInstanceAs(server)
        assertThat(session.audioRoute.value).isNull()
    }

    @Test
    fun startsWithVoiceActivityTransmitAndNoVoiceTarget() {
        val session = session()

        assertThat(session.transmitMode).isEqualTo(TransmitMode.VOICE_ACTIVITY)
        assertThat(session.voiceTargetId).isEqualTo(0.toByte())
        assertThat(session.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(session.whisperTarget).isNull()
        assertThat(session.isTalking).isFalse()
    }

    /** SCO state comes from `AudioRouter`, not from an `ACTION_SCO_AUDIO_STATE_UPDATED` receiver. */
    @Test
    fun noScoBroadcastReceiverIsRegistered() {
        testSession(devices = null).also { sessions += it }

        @Suppress("DEPRECATION")
        val scoReceivers = shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers
            .filter { it.intentFilter.hasAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) }

        assertThat(scoReceivers).isEmpty()
    }

    /** The wake lock is built with the session but not taken until synchronization. */
    @Test
    fun theWakeLockIsCreatedUnheldWithTheSessionTag() {
        session()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertThat(lock).isNotNull()
        assertThat(lock.isHeld).isFalse()
        assertThat(shadowOf(lock).getTag()).isEqualTo("Humla:Session")
    }

    /** The configured server is the one the connection opens a socket to. */
    @Test
    fun connectOpensASocketToTheConfiguredServer() {
        val h = harness()

        h.session.connect()

        assertThat(h.session.state.value).isEqualTo(SessionState.Connecting)
        awaitUntil(description = "socket opened") {
            h.mainLooper.idle()
            h.transports.tcps.firstOrNull()?.connectThread != null
        }
        assertThat(h.transports.tcps[0].connectHost).isEqualTo("127.0.0.1")
        assertThat(h.transports.tcps[0].connectPort).isEqualTo(64738)
    }

    /** The pipeline settings reach the audio config as they are; nothing is copied field by field. */
    @Test
    fun thePipelineSettingsReachTheAudioConfigUnchanged() {
        val session = session()
        val pipeline = PipelineSettings(
            audioStream = 3,
            audioSource = 7,
            inputSampleRate = 16_000,
            bitrate = 24_000,
            framesPerPacket = 4,
            amplitudeBoost = 1.5f,
            noiseSuppression = NoiseSuppressionMode.RNNOISE,
            speexNoiseSuppressDb = -40,
            androidEffects = AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = true),
        )

        session.configure(
            SessionConfig(
                audio = AudioSettings(transmitMode = TransmitMode.PUSH_TO_TALK, halfDuplex = true, pipeline = pipeline),
            ),
        )

        val config = session.audio.config
        assertThat(config.settings).isSameInstanceAs(pipeline)
        assertThat(config).isEqualTo(AudioConfig(pipeline, halfDuplex = true))
    }

    /** The transmit mode picks one of the three input modes by identity, also at construction. */
    @Test
    fun theTransmitModeSelectsTheInputModeByIdentity() {
        val expected = mapOf(
            TransmitMode.PUSH_TO_TALK to ToggleInputMode::class.java,
            TransmitMode.CONTINUOUS to ContinuousInputMode::class.java,
            TransmitMode.VOICE_ACTIVITY to ActivityInputMode::class.java,
        )

        for ((mode, type) in expected) {
            val configured = session()
            configured.configure(SessionConfig(audio = AudioSettings(transmitMode = mode)))
            val constructed = session(SessionConfig(audio = AudioSettings(transmitMode = mode)))

            assertThat(configured.transmitMode).isEqualTo(mode)
            assertThat(configured.audio.inputMode).isInstanceOf(type)
            assertThat(constructed.audio.inputMode).isInstanceOf(type)
        }
    }

    /** The chosen input mode is the instance `isTalking` reads, not a fresh copy. */
    @Test
    fun thePushToTalkModeHandedToTheAudioPipelineIsTheOneIsTalkingReads() {
        val session = session(SessionConfig(audio = AudioSettings(transmitMode = TransmitMode.PUSH_TO_TALK)))

        session.setTalkingState(true)

        val mode = session.audio.inputMode as ToggleInputMode
        assertThat(mode.isTalkingOn).isTrue()
        assertThat(session.isTalking).isTrue()
    }

    /** Access tokens are kept even with no connection to send them on; nothing throws. */
    @Test
    fun accessTokensAreKeptWithoutAConnection() {
        val session = session()

        assertThat(session.configure(SessionConfig(accessTokens = listOf("a", "b")))).isFalse()

        assertThat(session.config.accessTokens).isEqualTo(listOf("a", "b"))
    }

    /** Only a change to the connection settings asks for a reconnect. */
    @Test
    fun onlyAConnectionChangeNeedsAReconnect() {
        val session = session()

        assertThat(session.configure(SessionConfig(audio = AudioSettings(halfDuplex = true)))).isFalse()
        assertThat(session.configure(SessionConfig(ConnectionConfig(forceTcp = true)))).isTrue()
    }

    @Test
    fun aCleanDisconnectGoesToDisconnectedAndNeverReconnects() {
        for (autoReconnect in listOf(false, true)) {
            val session = session(SessionConfig(autoReconnect = autoReconnect))

            session.onConnectionDisconnected(null)

            assertThat(session.state.value).isEqualTo(SessionState.Disconnected())
        }
    }

    /** What ended a live connection reaches the state as a typed reason, with no protocol types. */
    @Test
    fun theReasonOfAnEndedConnectionIsInTheState() {
        val h = harness()
        h.connectAndSynchronize()

        h.session.onConnectionDisconnected(HumlaException("gone", HumlaException.HumlaDisconnectReason.OTHER_ERROR))

        assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected(DisconnectReason.Failed("gone", null)))
    }

    /** A certificate the connection refused ends the session with the chain, for the trust prompt. */
    @Test
    fun anUntrustedCertificateEndsTheSessionWithItsChain() {
        val h = harness()
        val chain = TestCertificates.leaf().chain
        h.session.connect()
        awaitUntil(description = "socket opened") {
            h.mainLooper.idle()
            h.transports.tcps.firstOrNull()?.connectThread != null
        }

        h.transports.tcps[0].simulateHandshakeFailure(chain)
        awaitUntil(description = "the end is reported") {
            h.mainLooper.idle()
            h.session.state.value is SessionState.Disconnected
        }

        val reason = (h.session.state.value as SessionState.Disconnected).reason
        assertThat(reason).isEqualTo(DisconnectReason.TlsUntrusted(chain.toList()))
    }

    /** A disconnect drops the voice target and empties the whisper slots. */
    @Test
    fun aDisconnectClearsTheVoiceTargetAndTheWhisperSlots() {
        val session = session()

        session.onConnectionDisconnected(null)

        assertThat(session.voiceTargetId).isEqualTo(0.toByte())
        assertThat(session.whisperTarget).isNull()
    }

    /** Info is dropped before synchronization; warnings and errors are not. */
    @Test
    fun onlyInfoLoggingIsSuppressedBeforeSynchronization() {
        val session = session()
        val recorder = EventRecorder(session)

        session.logger.logInfo("info")
        session.logger.logWarning("warning")
        session.logger.logError("error")

        assertThat(recorder.of<HumlaEvent.LogMessage>()).containsExactly(
            HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "warning"),
            HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, "error"),
        ).inOrder()
    }

    /** Chat log notices follow the same rule: info waits for synchronization, warnings do not. */
    @Test
    fun onlyInfoNoticesAreSuppressedBeforeSynchronization() {
        val session = session()
        val recorder = EventRecorder(session)

        session.emit(HumlaEvent.UserJoinedServer("Ann"))
        session.emit(HumlaEvent.SelfKicked("Mod", "spam", ban = false))
        session.emit(HumlaEvent.UserConnected(se.lublin.humla.model.User(2, "Ann")))

        assertThat(recorder.events.map { it::class }).containsExactly(
            HumlaEvent.SelfKicked::class,
            HumlaEvent.UserConnected::class,
        ).inOrder()
    }

    /** A [ConnectionWarning] is resolved to a string through the library's resources. */
    @Test
    fun aConnectionWarningIsResolvedAgainstTheResources() {
        val session = session()
        val recorder = EventRecorder(session)

        session.onConnectionWarning(ConnectionWarning.UDP_UNAVAILABLE)

        val warnings = recorder.of<HumlaEvent.LogMessage>().map { it.text }
        val expected = RuntimeEnvironment.getApplication().getString(ConnectionWarning.UDP_UNAVAILABLE.messageRes)
        assertThat(warnings).containsExactly(expected)
        assertThat(warnings[0]).isNotEmpty()
    }

    @Test
    fun aCancelledCollectorStopsHearingWarnings() {
        val session = session()
        val recorder = EventRecorder(session)
        session.logger.logWarning("first")

        recorder.job.cancel()
        session.logger.logWarning("second")

        assertThat(recorder.of<HumlaEvent.LogMessage>().map { it.text }).containsExactly("first")
    }

    /** Freeing a slot that was never taken is harmless, and whispering is off while disconnected. */
    @Test
    fun unregisteringAWhisperTargetThatWasNeverRegisteredIsHarmless() {
        val session = session()

        session.unregisterWhisperTarget(3)

        assertThat(session.whisperTarget).isNull()
        assertThat(session.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
    }

    /** Every model and request call fails with the same, explicit error while disconnected. */
    @Test
    fun everySessionCallThrowsIllegalStateWhileDisconnected() {
        val session = session()

        val calls = listOf<Pair<String, () -> Unit>>(
            "tcpLatency" to { session.tcpLatency },
            "udpLatency" to { session.udpLatency },
            "maxBandwidth" to { session.maxBandwidth },
            "serverVersion" to { session.serverVersion },
            "serverRelease" to { session.serverRelease },
            "serverOSName" to { session.serverOSName },
            "serverOSVersion" to { session.serverOSVersion },
            "sessionId" to { session.sessionId },
            "codec" to { session.codec },
            "moveUserToChannel" to { session.moveUserToChannel(1, 2) },
            "joinChannel" to { session.joinChannel(2) },
            "createChannel" to { session.createChannel(0, "n", "d", 0, false) },
            "sendAccessTokens" to { session.sendAccessTokens(listOf("t")) },
            "requestPermissions" to { session.requestPermissions(0) },
            "requestComment" to { session.requestComment(1) },
            "requestAvatar" to { session.requestAvatar(1) },
            "requestChannelDescription" to { session.requestChannelDescription(0) },
            "registerUser" to { session.registerUser(1) },
            "kickBanUser" to { session.kickBanUser(1, "r", false) },
            "setUserComment" to { session.setUserComment(1, "c") },
            "setPrioritySpeaker" to { session.setPrioritySpeaker(1, true) },
            "removeChannel" to { session.removeChannel(1) },
            "setMuteDeafState" to { session.setMuteDeafState(1, true, false) },
            "setSelfMuteDeafState" to { session.setSelfMuteDeafState(true, false) },
            "sessionUser" to { session.sessionUser },
            "sessionChannel" to { session.sessionChannel },
            "getUser" to { session.getUser(1) },
            "getChannel" to { session.getChannel(1) },
            "rootChannel" to { session.rootChannel },
            "permissions" to { session.permissions },
            "serverSettings" to { session.serverSettings },
            "sendUserTextMessage" to { session.sendUserTextMessage(1, "m") },
            "sendChannelTextMessage" to { session.sendChannelTextMessage(1, "m", false) },
        )

        val wrong = calls.mapNotNull { (name, call) ->
            val thrown = try {
                call()
                null
            } catch (t: Throwable) {
                t
            }
            when (thrown) {
                null -> "$name threw nothing"
                is IllegalStateException -> null
                else -> "$name threw ${thrown.javaClass.simpleName}"
            }
        }

        assertThat(wrong).isEmpty()
    }

    /** The calls that answer while disconnected instead of throwing. */
    @Test
    fun theSessionCallsThatDoNotDependOnAConnectionStillAnswer() {
        val session = session()

        assertThat(session.transmitMode).isEqualTo(TransmitMode.VOICE_ACTIVITY)
        assertThat(session.isTalking).isFalse()
        assertThat(session.voiceTargetId).isEqualTo(0.toByte())
        assertThat(session.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(session.whisperTarget).isNull()
        session.setTalkingState(true)
        assertThat(session.isTalking).isTrue()
        // The pipeline is asynchronous: -1 while none is up.
        assertThat(session.currentBandwidth).isEqualTo(-1)
        assertThat(session.audioDevices).isEmpty()
        assertThat(session.activeAudioDevice).isNull()
        session.disconnect()
        session.cancelReconnect()
        assertThat(session.state.value).isEqualTo(SessionState.Disconnected())
    }
}
