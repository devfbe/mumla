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
        assertThat(session.audio.route.value).isNull()
    }

    @Test
    fun startsWithVoiceActivityTransmitAndNoVoiceTarget() {
        val session = session()

        assertThat(session.audio.transmitMode).isEqualTo(TransmitMode.VOICE_ACTIVITY)
        assertThat(session.voiceTargetId).isEqualTo(0.toByte())
        assertThat(session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(session.actions.whisperTarget).isNull()
        assertThat(session.audio.isTalking).isFalse()
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
            h.transports.tcps.firstOrNull()?.isConnectCalled == true
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

        val config = session.audioSession.config
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

            assertThat(configured.audio.transmitMode).isEqualTo(mode)
            assertThat(configured.audioSession.inputMode).isInstanceOf(type)
            assertThat(constructed.audioSession.inputMode).isInstanceOf(type)
        }
    }

    /** The chosen input mode is the instance `isTalking` reads, not a fresh copy. */
    @Test
    fun thePushToTalkModeHandedToTheAudioPipelineIsTheOneIsTalkingReads() {
        val session = session(SessionConfig(audio = AudioSettings(transmitMode = TransmitMode.PUSH_TO_TALK)))

        session.audio.setTalking(true)

        val mode = session.audioSession.inputMode as ToggleInputMode
        assertThat(mode.isTalkingOn).isTrue()
        assertThat(session.audio.isTalking).isTrue()
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
            h.transports.tcps.firstOrNull()?.isConnectCalled == true
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
        assertThat(session.actions.whisperTarget).isNull()
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
        session.emit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.NORMAL))

        assertThat(recorder.events.map { it::class }).containsExactly(
            HumlaEvent.SelfKicked::class,
            HumlaEvent.VoiceTargetChanged::class,
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

    /** Stopping a whisper that never started is harmless, and whispering is off while disconnected. */
    @Test
    fun stoppingAWhisperThatNeverStartedIsHarmless() {
        val session = session()

        session.actions.stopWhispering()

        assertThat(session.actions.whisperTarget).isNull()
        assertThat(session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
    }

    /** The calls that answer while disconnected instead of throwing. */
    @Test
    fun theSessionCallsThatDoNotDependOnAConnectionStillAnswer() {
        val session = session()

        assertThat(session.audio.transmitMode).isEqualTo(TransmitMode.VOICE_ACTIVITY)
        assertThat(session.audio.isTalking).isFalse()
        assertThat(session.voiceTargetId).isEqualTo(0.toByte())
        assertThat(session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(session.actions.whisperTarget).isNull()
        session.audio.setTalking(true)
        assertThat(session.audio.isTalking).isTrue()
        // The pipeline is asynchronous: -1 while none is up.
        assertThat(session.audio.currentBandwidth).isEqualTo(-1)
        assertThat(session.audio.devices).isEmpty()
        assertThat(session.audio.activeDevice).isNull()
        session.disconnect()
        session.cancelReconnect()
        assertThat(session.state.value).isEqualTo(SessionState.Disconnected())
    }
}
