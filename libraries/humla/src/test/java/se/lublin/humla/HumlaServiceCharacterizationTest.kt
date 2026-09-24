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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.model.Server
import se.lublin.humla.net.ConnectionWarning
import se.lublin.humla.session.AudioConfig
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.testutil.EventRecorder
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.onEvents
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import java.util.concurrent.TimeUnit

/**
 * Characterization of [HumlaService] without a live connection: lifecycle, configuration, and the
 * disconnected arm of the session API.
 *
 * Accessors are called as functions (`getConnectionState()`), not as properties, so that they
 * stay functions. Values written into objects without getters are read back by reflection.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceCharacterizationTest {

    private val server = Server(-1, "test", "127.0.0.1", 64738, "me", "")
    private val controllers = mutableListOf<ServiceController<HumlaService>>()

    private fun service(): HumlaService {
        val controller = Robolectric.buildService(HumlaService::class.java).create()
        controllers += controller
        return controller.get()
    }

    /** The input mode in force. */
    private fun inputMode(service: HumlaService): Any = service.mInputMode

    /**
     * A service that cancels every connection attempt on `Connecting`, which a main-thread
     * collector sees inline, so `connect()` can be driven without opening a socket.
     */
    private fun cancellingService(): HumlaService = service().also { service ->
        service.onEvents { if (it == HumlaEvent.Connecting) service.disconnect() }
    }

    private fun connectivityManager() = RuntimeEnvironment.getApplication()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ---------------------------------------------------------------- lifecycle and initial state

    /** A fresh service is disconnected and has no session; `HumlaSession()` gates the binder API. */
    @Test
    fun startsDisconnectedWithoutSession() {
        val service = service()

        assertThat(service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
        assertThat(service.isConnected()).isFalse()
        assertThat(service.isReconnecting()).isFalse()
        assertThat(service.getConnectionError()).isNull()
        assertThat(service.getConnection()).isNull()
        assertThat(service.getTargetServer()).isNull()
        assertThat(service.isSynchronized()).isFalse()
        assertThat(service.isConnectionEstablished()).isFalse()
        assertThrows(HumlaDisconnectedException::class.java) { service.HumlaSession() }
    }

    /** onCreate's own defaults, before any extra is applied. */
    @Test
    fun startsWithVoiceActivityTransmitAndNoVoiceTarget() {
        val service = service()

        assertThat(service.getTransmitMode()).isEqualTo(Constants.TRANSMIT_VOICE_ACTIVITY)
        assertThat(service.getVoiceTargetId()).isEqualTo(0.toByte())
        assertThat(service.getVoiceTargetMode()).isEqualTo(se.lublin.humla.util.VoiceTargetMode.NORMAL)
        assertThat(service.getWhisperTarget()).isNull()
        assertThat(service.isTalking()).isFalse()
    }

    /** SCO state comes from `AudioRouter`, not from an `ACTION_SCO_AUDIO_STATE_UPDATED` receiver. */
    @Test
    fun noScoBroadcastReceiverIsRegisteredAnyMore() {
        val controller = Robolectric.buildService(HumlaService::class.java).create()
        controllers += controller

        @Suppress("DEPRECATION")
        val scoReceivers = shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers
            .filter { it.intentFilter.hasAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) }

        assertThat(scoReceivers).isEmpty()
    }

    /** The wake lock is built in `onCreate` but not taken until synchronization. */
    @Test
    fun theWakeLockIsCreatedUnheldWithTheHumlaTag() {
        service()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertThat(lock).isNotNull()
        assertThat(lock.isHeld).isFalse()
        assertThat(shadowOf(lock).getTag()).isEqualTo("Humla:HumlaService")
    }

    /** The binder hands back the service itself; `HumlaService` is its own `IHumlaService`. */
    @Test
    fun theBinderHandsBackTheService() {
        val service = service()

        val binder = service.onBind(Intent()) as HumlaService.HumlaBinder

        assertThat(binder.getService()).isSameInstanceAs(service)
    }

    // ---------------------------------------------------------------- start and configure

    /** A start command only keeps the service started; connecting goes through the binder. */
    @Test
    fun aStartCommandNeitherConfiguresNorConnectsAndIsNotSticky() {
        val service = service()

        for (intent in listOf(null, Intent(), Intent().setAction("se.lublin.humla.CONNECT"))) {
            assertThat(service.onStartCommand(intent, 0, 0)).isEqualTo(Service.START_NOT_STICKY)
        }
        assertThat(service.getConnection()).isNull()
        assertThat(service.getTargetServer()).isNull()
    }

    /** The configured server is the one connected to, and the state is CONNECTING on `Connecting`. */
    @Test
    fun connectUsesTheConfiguredServer() {
        val service = service()
        val stateInsideOnConnecting = mutableListOf<HumlaService.ConnectionState>()
        val serverInsideOnConnecting = mutableListOf<String?>()
        service.onEvents {
            if (it == HumlaEvent.Connecting) {
                stateInsideOnConnecting += service.getConnectionState()
                serverInsideOnConnecting += service.getTargetServer()?.host
                service.disconnect()
            }
        }

        service.configure(SessionConfig(server = server))
        service.connect()

        assertThat(stateInsideOnConnecting).containsExactly(HumlaService.ConnectionState.CONNECTING)
        assertThat(serverInsideOnConnecting).containsExactly("127.0.0.1")
        assertThat(service.getConnection()).isNotNull()
    }

    /** Every audio setting lands in its [AudioConfig] field; the VAD config reaches a live object instead. */
    @Test
    fun everyAudioSettingLandsInTheAudioConfig() {
        val service = service()

        service.configure(
            SessionConfig(
                amplitudeBoost = 1.5f,
                inputSampleRate = 16_000,
                inputQuality = 24_000,
                audioSource = 7,
                audioStream = 3,
                framesPerPacket = 4,
                preprocessorEnabled = true,
                noiseSuppressionMethod = "rnnoise",
                speexNoiseSuppressDb = -40,
                androidNoiseSuppressor = true,
                androidAgc = true,
                transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
                halfDuplex = true,
            )
        )

        assertThat(service.getAudioConfigForTest()).isEqualTo(
            AudioConfig(
                amplitudeBoost = 1.5f,
                inputSampleRate = 16_000,
                targetBitrate = 24_000,
                audioSource = 7,
                audioStream = 3,
                targetFramesPerPacket = 4,
                preprocessorEnabled = true,
                noiseSuppression = "rnnoise",
                speexNoiseSuppressDb = -40,
                androidNoiseSuppressor = true,
                androidAgc = true,
                transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
                halfDuplexRequested = true,
            )
        )
        // The fields no setting writes: the route decides them.
        assertThat(service.getAudioConfigForTest().routedDeviceType).isNull()
        assertThat(service.getAudioConfigForTest().echoCancellation).isFalse()
    }

    /** The transmit mode picks one of the three input modes by identity; a fourth value is refused. */
    @Test
    fun theTransmitModeSelectsTheInputModeByIdentity() {
        val expected = mapOf(
            Constants.TRANSMIT_PUSH_TO_TALK to ToggleInputMode::class.java,
            Constants.TRANSMIT_CONTINUOUS to ContinuousInputMode::class.java,
            Constants.TRANSMIT_VOICE_ACTIVITY to ActivityInputMode::class.java,
        )

        for ((mode, type) in expected) {
            val service = service()
            service.configure(SessionConfig(transmitMode = mode))

            assertThat(service.getTransmitMode()).isEqualTo(mode)
            assertThat(inputMode(service)).isInstanceOf(type)
        }
    }

    @Test
    fun anUnknownTransmitModeIsRefusedAndChangesNothing() {
        val service = service()

        assertThrows(IllegalArgumentException::class.java) {
            service.configure(SessionConfig(transmitMode = 99, server = server))
        }
        assertThat(service.getSessionConfig()).isEqualTo(SessionConfig())
    }

    /** The chosen input mode is the instance `isTalking()` reads, not a fresh copy. */
    @Test
    fun thePushToTalkModeHandedToTheAudioPipelineIsTheOneIsTalkingReads() {
        val service = service()
        service.configure(SessionConfig(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK))

        service.setTalkingState(true)

        val mode = inputMode(service) as ToggleInputMode
        assertThat(mode.isTalkingOn()).isTrue()
        assertThat(service.isTalking()).isTrue()
    }

    /** Access tokens are kept even with no connection to send them on; nothing throws. */
    @Test
    fun accessTokensAreKeptWithoutAConnection() {
        val service = service()

        assertThat(service.configure(SessionConfig(accessTokens = listOf("a", "b")))).isFalse()

        assertThat(service.getSessionConfig().accessTokens).isEqualTo(listOf("a", "b"))
    }

    // ---------------------------------------------------------------- disconnection

    /**
     * `onConnectionDisconnected` over its input space: the error, its reason, and `autoReconnect`.
     * Four corners decide whether the service goes reconnecting; the state itself is decided by the
     * error alone.
     */
    @Test
    fun aCleanDisconnectGoesToDisconnectedAndNeverReconnects() {
        for (autoReconnect in listOf(false, true)) {
            val service = service()
            service.configure(SessionConfig(autoReconnect = autoReconnect))

            service.onConnectionDisconnected(null)

            assertThat(service.getConnectionState())
                .isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
            assertThat(service.isReconnecting()).isFalse()
        }
    }

    /** The error object reaches collectors unchanged, and the state is already set when it does. */
    @Test
    fun theDisconnectReportCarriesTheSameErrorAndAStateThatIsAlreadySet() {
        val h = HumlaServiceHarness()
        h.connectAndSynchronize()
        val service = h.service
        val error = HumlaException("gone", HumlaException.HumlaDisconnectReason.REJECT)
        val seen = mutableListOf<Pair<HumlaException?, HumlaService.ConnectionState>>()
        service.onEvents {
            if (it is HumlaEvent.Disconnected) seen += it.error to service.getConnectionState()
        }

        service.onConnectionDisconnected(error)

        assertThat(seen).hasSize(1)
        assertThat(seen[0].first).isSameInstanceAs(error)
        assertThat(seen[0].second).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
        h.destroy()
    }

    /** A disconnect drops the voice target and empties the whisper slots. */
    @Test
    fun aDisconnectClearsTheVoiceTargetAndTheWhisperSlots() {
        val service = service()

        service.onConnectionDisconnected(null)

        assertThat(service.getVoiceTargetId()).isEqualTo(0.toByte())
        assertThat(service.getWhisperTarget()).isNull()
    }

    // ---------------------------------------------------------------- logging

    /** `logInfo` is dropped before synchronization; `logWarning` and `logError` are not. */
    @Test
    fun onlyInfoLoggingIsSuppressedBeforeSynchronization() {
        val service = service()
        val recorder = EventRecorder(service)

        service.logInfo("info")
        service.logWarning("warning")
        service.logError("error")

        assertThat(recorder.of<HumlaEvent.LogMessage>()).containsExactly(
            HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "warning"),
            HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, "error"),
        ).inOrder()
    }

    /** Chat log notices follow the same rule: info waits for synchronization, warnings do not. */
    @Test
    fun onlyInfoNoticesAreSuppressedBeforeSynchronization() {
        val service = service()
        val recorder = EventRecorder(service)

        service.emit(HumlaEvent.UserJoinedServer("Ann"))
        service.emit(HumlaEvent.SelfKicked("Mod", "spam", ban = false))
        service.emit(HumlaEvent.UserConnected(se.lublin.humla.model.User(2, "Ann")))

        assertThat(recorder.events.map { it::class }).containsExactly(
            HumlaEvent.SelfKicked::class,
            HumlaEvent.UserConnected::class,
        ).inOrder()
    }

    /** A [ConnectionWarning] is resolved to a string through this service's resources. */
    @Test
    fun aConnectionWarningIsResolvedAgainstTheServicesResources() {
        val service = service()
        val recorder = EventRecorder(service)

        service.onConnectionWarning(ConnectionWarning.UDP_UNAVAILABLE)

        val warnings = recorder.of<HumlaEvent.LogMessage>().map { it.text }
        assertThat(warnings)
            .containsExactly(service.getString(ConnectionWarning.UDP_UNAVAILABLE.messageRes))
        assertThat(warnings[0]).isNotEmpty()
    }

    /** A cancelled collector hears nothing further. */
    @Test
    fun aCancelledCollectorStopsHearingWarnings() {
        val service = service()
        val recorder = EventRecorder(service)
        service.logWarning("first")

        recorder.job.cancel()
        service.logWarning("second")

        assertThat(recorder.of<HumlaEvent.LogMessage>().map { it.text }).containsExactly("first")
    }

    // ---------------------------------------------------------------- voice targets

    /** Freeing a slot that was never taken is harmless, and whispering is off while disconnected. */
    @Test
    fun unregisteringAWhisperTargetThatWasNeverRegisteredIsHarmless() {
        val service = service()

        service.unregisterWhisperTarget(3)

        assertThat(service.getWhisperTarget()).isNull()
        assertThat(service.getVoiceTargetMode())
            .isEqualTo(se.lublin.humla.util.VoiceTargetMode.NORMAL)
    }

    // ---------------------------------------------------------------- the session API, disconnected

    /** Every session call fails with the same, explicit error while disconnected. */
    @Test
    fun everySessionCallThrowsIllegalStateWhileDisconnected() {
        val service = service()

        val calls = listOf<Pair<String, () -> Unit>>(
            "getTCPLatency" to { service.getTCPLatency() },
            "getUDPLatency" to { service.getUDPLatency() },
            "getMaxBandwidth" to { service.maxBandwidth },
            "getServerVersion" to { service.getServerVersion() },
            "getServerRelease" to { service.getServerRelease() },
            "getServerOSName" to { service.getServerOSName() },
            "getServerOSVersion" to { service.getServerOSVersion() },
            "getSessionId" to { service.getSessionId() },
            "getCodec" to { service.getCodec() },
            "moveUserToChannel" to { service.moveUserToChannel(1, 2) },
            "joinChannel" to { service.joinChannel(2) },
            "createChannel" to { service.createChannel(0, "n", "d", 0, false) },
            "sendAccessTokens" to { service.sendAccessTokens(listOf("t")) },
            "requestPermissions" to { service.requestPermissions(0) },
            "requestComment" to { service.requestComment(1) },
            "requestAvatar" to { service.requestAvatar(1) },
            "requestChannelDescription" to { service.requestChannelDescription(0) },
            "registerUser" to { service.registerUser(1) },
            "kickBanUser" to { service.kickBanUser(1, "r", false) },
            "setUserComment" to { service.setUserComment(1, "c") },
            "setPrioritySpeaker" to { service.setPrioritySpeaker(1, true) },
            "removeChannel" to { service.removeChannel(1) },
            "setMuteDeafState" to { service.setMuteDeafState(1, true, false) },
            "setSelfMuteDeafState" to { service.setSelfMuteDeafState(true, false) },
            "getSessionUser" to { service.getSessionUser() },
            "getSessionChannel" to { service.getSessionChannel() },
            "getUser" to { service.getUser(1) },
            "getChannel" to { service.getChannel(1) },
            "getRootChannel" to { service.getRootChannel() },
            "getPermissions" to { service.permissions },
            "getServerSettings" to { service.getServerSettings() },
            "sendUserTextMessage" to { service.sendUserTextMessage(1, "m") },
            "sendChannelTextMessage" to { service.sendChannelTextMessage(1, "m", false) },
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
        val service = service()

        assertThat(service.getTransmitMode()).isEqualTo(Constants.TRANSMIT_VOICE_ACTIVITY)
        assertThat(service.isTalking()).isFalse()
        assertThat(service.getVoiceTargetId()).isEqualTo(0.toByte())
        assertThat(service.getVoiceTargetMode())
            .isEqualTo(se.lublin.humla.util.VoiceTargetMode.NORMAL)
        assertThat(service.getWhisperTarget()).isNull()
        service.setTalkingState(true)
        assertThat(service.isTalking()).isTrue()
        // The pipeline is asynchronous: -1 while none is up.
        assertThat(service.getCurrentBandwidth()).isEqualTo(-1)
        // The Bluetooth wish outlives every session, so these answer while disconnected.
        assertThat(service.usingBluetoothSco()).isFalse()
        assertThat(service.isBluetoothScoActive()).isFalse()
        service.enableBluetoothSco()
        assertThat(service.usingBluetoothSco()).isTrue()
        service.disableBluetoothSco()
        assertThat(service.usingBluetoothSco()).isFalse()
    }
}
