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
import android.os.Bundle
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
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import java.util.concurrent.TimeUnit

/**
 * Characterization of [HumlaService] without a live connection: lifecycle, extras, and the
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
     * A service whose observer cancels every connection attempt from inside `onConnecting`, which
     * is delivered inline, so `connect()` can be driven without opening a socket.
     */
    private fun cancellingService(): HumlaService = service().also { service ->
        service.registerObserver(object : HumlaObserver() {
            override fun onConnecting() = service.disconnect()
        })
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

    // ---------------------------------------------------------------- onStartCommand input space

    /** The four corners of `onStartCommand`: intent, extras, action CONNECT, EXTRAS_SERVER. */
    @Test
    fun aStartCommandWithoutAnIntentDoesNothingAndIsNotSticky() {
        val service = service()

        assertThat(service.onStartCommand(null, 0, 0)).isEqualTo(Service.START_NOT_STICKY)
        assertThat(service.getConnection()).isNull()
        assertThat(service.getTargetServer()).isNull()
    }

    @Test
    fun anIntentWithoutExtrasConfiguresNothingAndIsNotSticky() {
        val service = service()

        assertThat(service.onStartCommand(Intent(), 0, 0)).isEqualTo(Service.START_NOT_STICKY)
        assertThat(service.getTargetServer()).isNull()
    }

    @Test
    fun anIntentWithExtrasButNoConnectActionOnlyConfigures() {
        val service = service()
        val intent = Intent().putExtra(HumlaService.EXTRAS_SERVER, server)

        assertThat(service.onStartCommand(intent, 0, 0)).isEqualTo(Service.START_NOT_STICKY)

        assertThat(service.getTargetServer()!!.host).isEqualTo("127.0.0.1")
        assertThat(service.getConnection()).isNull()
    }

    @Test
    fun aConnectActionWithoutAServerExtraThrows() {
        val service = service()
        val intent = Intent().setAction(HumlaService.ACTION_CONNECT)

        val e = assertThrows(RuntimeException::class.java) { service.onStartCommand(intent, 0, 0) }

        assertThat(e).hasMessageThat().contains("requires a server provided in extras")
    }

    @Test
    fun aConnectActionWithExtrasThatCarryNoServerThrowsToo() {
        val service = service()
        val intent = Intent().setAction(HumlaService.ACTION_CONNECT)
            .putExtra(HumlaService.EXTRAS_AUTO_RECONNECT, true)

        assertThrows(RuntimeException::class.java) { service.onStartCommand(intent, 0, 0) }
    }

    /**
     * Extras are applied before `connect()` reads them, and `mConnectionState` is already
     * CONNECTING when `onConnecting` runs.
     */
    @Test
    fun aConnectActionAppliesTheExtrasBeforeItConnects() {
        val service = service()
        val stateInsideOnConnecting = mutableListOf<HumlaService.ConnectionState>()
        val serverInsideOnConnecting = mutableListOf<String?>()
        service.registerObserver(object : HumlaObserver() {
            override fun onConnecting() {
                stateInsideOnConnecting += service.getConnectionState()
                serverInsideOnConnecting += service.getTargetServer()?.host
                service.disconnect()
            }
        })
        val intent = Intent().setAction(HumlaService.ACTION_CONNECT)
            .putExtra(HumlaService.EXTRAS_SERVER, server)

        service.onStartCommand(intent, 0, 0)

        assertThat(stateInsideOnConnecting).containsExactly(HumlaService.ConnectionState.CONNECTING)
        assertThat(serverInsideOnConnecting).containsExactly("127.0.0.1")
        assertThat(service.getConnection()).isNotNull()
    }

    // ---------------------------------------------------------------- configureExtras

    /** Every `EXTRAS_*` constant, found by reflection, is classified in the reconnect table. */
    @Test
    fun everyExtraIsClassifiedForReconnect() {
        val declared = HumlaService::class.java.fields
            .filter { it.name.startsWith("EXTRAS_") && it.type == String::class.java }
            .map { it.get(null) as String }

        assertThat(declared).containsExactlyElementsIn(HumlaServiceExtrasReconnectTest.RECONNECT_NEEDED.keys)
    }

    /** Every audio extra lands in its [AudioConfig] field; two write into live objects instead. */
    @Test
    fun everyAudioExtraLandsInTheAudioConfig() {
        val service = service()
        val extras = Bundle().apply {
            putFloat(HumlaService.EXTRAS_AMPLITUDE_BOOST, 1.5f)
            putInt(HumlaService.EXTRAS_INPUT_RATE, 48000)
            putInt(HumlaService.EXTRAS_INPUT_QUALITY, 40000)
            putInt(HumlaService.EXTRAS_AUDIO_SOURCE, 7)
            putInt(HumlaService.EXTRAS_AUDIO_STREAM, 3)
            putInt(HumlaService.EXTRAS_FRAMES_PER_PACKET, 4)
            putBoolean(HumlaService.EXTRAS_ENABLE_PREPROCESSOR, true)
            putString(HumlaService.EXTRAS_NOISE_SUPPRESSION_METHOD, "rnnoise")
            putInt(HumlaService.EXTRAS_SPEEX_NOISE_SUPPRESS_DB, -40)
            putBoolean(HumlaService.EXTRAS_ANDROID_NOISE_SUPPRESSOR, true)
            putBoolean(HumlaService.EXTRAS_ANDROID_AGC, true)
            putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK)
            putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, true)
        }

        service.configureExtras(extras)

        assertThat(service.getAudioConfigForTest()).isEqualTo(
            AudioConfig(
                amplitudeBoost = 1.5f,
                inputSampleRate = 48000,
                targetBitrate = 40000,
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
        // The fields no extra writes: the route decides them, not a setting.
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
            service.configureExtras(Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, mode) })

            assertThat(service.getTransmitMode()).isEqualTo(mode)
            assertThat(inputMode(service)).isInstanceOf(type)
        }
    }

    @Test
    fun anUnknownTransmitModeIsRefused() {
        val service = service()

        assertThrows(IllegalArgumentException::class.java) {
            service.configureExtras(Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, 99) })
        }
    }

    /** The chosen input mode is the instance `isTalking()` reads, not a fresh copy. */
    @Test
    fun thePushToTalkModeHandedToTheAudioPipelineIsTheOneIsTalkingReads() {
        val service = service()
        service.configureExtras(
            Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK) }
        )

        service.setTalkingState(true)

        val mode = inputMode(service) as ToggleInputMode
        assertThat(mode.isTalkingOn()).isTrue()
        assertThat(service.isTalking()).isTrue()
    }

    /** TCP is forced by either extra, recomputed from the last value each of them was given. */
    @Test
    fun tcpIsForcedWhileEitherForceTcpOrTorIsOn() {
        val service = service()
        fun configure(key: String, value: Boolean) =
            service.configureExtras(Bundle().apply { putBoolean(key, value) })

        configure(HumlaService.EXTRAS_FORCE_TCP, false)
        assertThat(service.isTcpForced).isFalse()

        configure(HumlaService.EXTRAS_USE_TOR, true)
        assertThat(service.isTcpForced).isTrue()

        configure(HumlaService.EXTRAS_FORCE_TCP, false)
        assertThat(service.isTcpForced).isTrue()

        configure(HumlaService.EXTRAS_USE_TOR, false)
        assertThat(service.isTcpForced).isFalse()

        configure(HumlaService.EXTRAS_FORCE_TCP, true)
        configure(HumlaService.EXTRAS_USE_TOR, false)
        assertThat(service.isTcpForced).isTrue()

        configure(HumlaService.EXTRAS_FORCE_TCP, false)
        assertThat(service.isTcpForced).isFalse()
    }

    /** Access tokens are stored even with no connection to send them on; nothing throws. */
    @Test
    fun accessTokensAreStoredWithoutAConnection()  {
        val service = service()

        assertThat(
            service.configureExtras(
                Bundle().apply {
                    putStringArrayList(HumlaService.EXTRAS_ACCESS_TOKENS, arrayListOf("a", "b"))
                }
            )
        ).isFalse()

        assertThat(service.mAccessTokens).isEqualTo(listOf("a", "b"))
    }

    // ---------------------------------------------------------------- disconnection

    /**
     * `onConnectionDisconnected` over its input space: the error, its reason, and `mAutoReconnect`.
     * Four corners decide whether the service goes reconnecting; the state itself is decided by the
     * error alone.
     */
    @Test
    fun aCleanDisconnectGoesToDisconnectedAndNeverReconnects() {
        for (autoReconnect in listOf(false, true)) {
            val service = service()
            service.configureExtras(
                Bundle().apply { putBoolean(HumlaService.EXTRAS_AUTO_RECONNECT, autoReconnect) }
            )

            service.onConnectionDisconnected(null)

            assertThat(service.getConnectionState())
                .isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
            assertThat(service.isReconnecting()).isFalse()
        }
    }

    /** The error object reaches the observer unchanged, and the state is already set when it does. */
    @Test
    fun theDisconnectReportCarriesTheSameErrorAndAStateThatIsAlreadySet() {
        val h = HumlaServiceHarness()
        h.connectAndSynchronize()
        val service = h.service
        val error = HumlaException("gone", HumlaException.HumlaDisconnectReason.REJECT)
        val seen = mutableListOf<Pair<HumlaException?, HumlaService.ConnectionState>>()
        service.registerObserver(object : HumlaObserver() {
            override fun onDisconnected(e: HumlaException?) {
                seen += e to service.getConnectionState()
            }
        })

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
        val infos = mutableListOf<String?>()
        val warnings = mutableListOf<String?>()
        val errors = mutableListOf<String?>()
        service.registerObserver(object : HumlaObserver() {
            override fun onLogInfo(message: String?) { infos += message }
            override fun onLogWarning(message: String?) { warnings += message }
            override fun onLogError(message: String?) { errors += message }
        })

        service.logInfo("info")
        service.logWarning("warning")
        service.logError("error")

        assertThat(infos).isEmpty()
        assertThat(warnings).containsExactly("warning")
        assertThat(errors).containsExactly("error")
    }

    /** A [ConnectionWarning] is resolved to a string through this service's resources. */
    @Test
    fun aConnectionWarningIsResolvedAgainstTheServicesResources() {
        val service = service()
        val warnings = mutableListOf<String?>()
        service.registerObserver(object : HumlaObserver() {
            override fun onLogWarning(message: String?) { warnings += message }
        })

        service.onConnectionWarning(ConnectionWarning.UDP_UNAVAILABLE)

        assertThat(warnings)
            .containsExactly(service.getString(ConnectionWarning.UDP_UNAVAILABLE.messageRes))
        assertThat(warnings[0]).isNotEmpty()
    }

    /** An unregistered observer hears nothing further. */
    @Test
    fun anUnregisteredObserverStopsHearingWarnings() {
        val service = service()
        val warnings = mutableListOf<String?>()
        val observer = object : HumlaObserver() {
            override fun onLogWarning(message: String?) { warnings += message }
        }
        service.registerObserver(observer)
        service.logWarning("first")

        service.unregisterObserver(observer)
        service.logWarning("second")

        assertThat(warnings).containsExactly("first")
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
            "getMaxBandwidth" to { service.getMaxBandwidth() },
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
            "getPermissions" to { service.getPermissions() },
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
