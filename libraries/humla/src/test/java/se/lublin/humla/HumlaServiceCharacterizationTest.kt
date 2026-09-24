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

    // ---------------------------------------------------------------- reflection helpers

    /** Reads a private field by name, walking up the hierarchy. The field names are the contract. */
    private fun field(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw AssertionError("no field $name on ${target.javaClass}")
    }

    /** The input mode in force. */
    private fun inputMode(service: HumlaService): Any = field(service, "mInputMode")!!

    /** Writes a private field by name. Used only to reach a state the public API cannot produce. */
    private fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                c.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
                return
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw AssertionError("no field $name on ${target.javaClass}")
    }

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

    /**
     * Which extras demand a reconnect, over every `EXTRAS_*` constant found by reflection: a new
     * extra fails this test until it is classified.
     */
    @Test
    fun exactlyTheseExtrasRequireAReconnect() {
        val reconnectNeeded = mapOf(
            HumlaService.EXTRAS_SERVER to true,
            HumlaService.EXTRAS_AUTO_RECONNECT to false,
            HumlaService.EXTRAS_CERTIFICATE to true,
            HumlaService.EXTRAS_CERTIFICATE_PASSWORD to true,
            HumlaService.EXTRAS_DETECTION_THRESHOLD to false,
            HumlaService.EXTRAS_AMPLITUDE_BOOST to false,
            HumlaService.EXTRAS_TRANSMIT_MODE to false,
            HumlaService.EXTRAS_INPUT_RATE to false,
            HumlaService.EXTRAS_INPUT_QUALITY to false,
            HumlaService.EXTRAS_USE_OPUS to true,
            HumlaService.EXTRAS_USE_TOR to true,
            HumlaService.EXTRAS_FORCE_TCP to true,
            HumlaService.EXTRAS_CLIENT_NAME to true,
            HumlaService.EXTRAS_ACCESS_TOKENS to false,
            HumlaService.EXTRAS_AUDIO_SOURCE to false,
            HumlaService.EXTRAS_AUDIO_STREAM to false,
            HumlaService.EXTRAS_FRAMES_PER_PACKET to false,
            HumlaService.EXTRAS_TRUST_STORE to true,
            HumlaService.EXTRAS_TRUST_STORE_PASSWORD to true,
            HumlaService.EXTRAS_TRUST_STORE_FORMAT to true,
            HumlaService.EXTRAS_HALF_DUPLEX to false,
            HumlaService.EXTRAS_LOCAL_MUTE_HISTORY to true,
            HumlaService.EXTRAS_LOCAL_IGNORE_HISTORY to true,
            HumlaService.EXTRAS_ENABLE_PREPROCESSOR to false,
            HumlaService.EXTRAS_ECHO_CANCELLATION_BY_DEVICE to false,
            HumlaService.EXTRAS_NOISE_SUPPRESSION_METHOD to false,
            HumlaService.EXTRAS_SPEEX_NOISE_SUPPRESS_DB to false,
            HumlaService.EXTRAS_ANDROID_NOISE_SUPPRESSOR to false,
            HumlaService.EXTRAS_ANDROID_AGC to false,
            HumlaService.EXTRAS_VAD_CONFIG to false,
            HumlaService.EXTRAS_BLUETOOTH_WANTED to false,
            HumlaService.EXTRAS_EARPIECE_BY_DEFAULT to false,
        )

        assertThat(declaredExtraKeys()).containsExactlyElementsIn(reconnectNeeded.keys)

        for ((key, expected) in reconnectNeeded) {
            val service = service()
            assertThat(service.configureExtras(bundleFor(key))).isEqualTo(expected)
        }
    }

    /** Every `EXTRAS_*` constant on HumlaService, by reflection, so the table above cannot go stale. */
    private fun declaredExtraKeys(): Set<String> = HumlaService::class.java.declaredFields
        .filter { it.name.startsWith("EXTRAS_") && it.type == String::class.java }
        .map { it.apply { isAccessible = true }.get(null) as String }
        .toSet()

    /** One bundle carrying one key, with a value of the type `configureExtras` reads it back as. */
    private fun bundleFor(key: String): Bundle = Bundle().apply {
        when (key) {
            HumlaService.EXTRAS_SERVER -> putParcelable(key, server)
            HumlaService.EXTRAS_CERTIFICATE -> putByteArray(key, byteArrayOf(1, 2, 3))
            HumlaService.EXTRAS_ACCESS_TOKENS -> putStringArrayList(key, arrayListOf("token"))
            HumlaService.EXTRAS_LOCAL_MUTE_HISTORY -> putIntegerArrayList(key, arrayListOf(7))
            HumlaService.EXTRAS_LOCAL_IGNORE_HISTORY -> putIntegerArrayList(key, arrayListOf(8))
            HumlaService.EXTRAS_DETECTION_THRESHOLD -> putFloat(key, 0.25f)
            HumlaService.EXTRAS_AMPLITUDE_BOOST -> putFloat(key, 1.5f)
            HumlaService.EXTRAS_TRANSMIT_MODE -> putInt(key, Constants.TRANSMIT_CONTINUOUS)
            HumlaService.EXTRAS_INPUT_RATE -> putInt(key, 48000)
            HumlaService.EXTRAS_INPUT_QUALITY -> putInt(key, 40000)
            HumlaService.EXTRAS_AUDIO_SOURCE -> putInt(key, 7)
            HumlaService.EXTRAS_AUDIO_STREAM -> putInt(key, 3)
            HumlaService.EXTRAS_FRAMES_PER_PACKET -> putInt(key, 4)
            HumlaService.EXTRAS_AUTO_RECONNECT,
            HumlaService.EXTRAS_USE_OPUS,
            HumlaService.EXTRAS_USE_TOR,
            HumlaService.EXTRAS_FORCE_TCP,
            HumlaService.EXTRAS_HALF_DUPLEX,
            HumlaService.EXTRAS_BLUETOOTH_WANTED,
            HumlaService.EXTRAS_EARPIECE_BY_DEFAULT,
            HumlaService.EXTRAS_ENABLE_PREPROCESSOR -> putBoolean(key, true)
            HumlaService.EXTRAS_ECHO_CANCELLATION_BY_DEVICE ->
                putBundle(key, Bundle().apply { putBoolean("SPEAKER", false) })
            else -> putString(key, "value-for-$key")
        }
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

    /** The detection threshold is written into the service's own ActivityInputMode. */
    @Test
    fun theDetectionThresholdReachesTheActivityInputMode() {
        val service = service()

        service.configureExtras(Bundle().apply { putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD, 0.25f) })

        val mode = field(service, "mActivityInputMode") as ActivityInputMode
        assertThat(mode.vadConfig.startThreshold).isEqualTo(0.25f)
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

    /** A transmit mode change is visible without a reconnect. */
    @Test
    fun transmitModeExtraIsReflectedImmediately() {
        val service = service()
        val extras = Bundle().apply {
            putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK)
        }

        assertThat(service.configureExtras(extras)).isFalse()
        assertThat(service.getTransmitMode()).isEqualTo(Constants.TRANSMIT_PUSH_TO_TALK)
    }

    /** The server extra lands and demands a reconnect. */
    @Test
    fun serverExtraRequiresAReconnect() {
        val service = service()
        val extras = Bundle().apply { putParcelable(HumlaService.EXTRAS_SERVER, server) }

        assertThat(service.configureExtras(extras)).isTrue()
        assertThat(service.getTargetServer()!!.host).isEqualTo("127.0.0.1")
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

        assertThat(field(service, "mAccessTokens")).isEqualTo(listOf("a", "b"))
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
        val service = service()
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

    /** A voice target id must fit in five bits. */
    @Test
    fun voiceTargetIdMustFitInFiveBits() {
        val service = service()

        assertThrows(IllegalArgumentException::class.java) { service.setVoiceTargetId(0x20) }
    }

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

    /**
     * Which exception each session call throws while disconnected. It is not one answer: calls
     * via `getConnection()` throw NullPointerException, calls via the model handler throw
     * IllegalStateException.
     */
    @Test
    fun everySessionCallThrowsItsOwnExceptionWhileDisconnected() {
        val service = service()
        val npe: Class<out Throwable> = NullPointerException::class.java
        val ise: Class<out Throwable> = IllegalStateException::class.java

        val calls = listOf<Triple<String, Class<out Throwable>, () -> Unit>>(
            // via getConnection(): a null dereference.
            Triple("getTCPLatency", npe) { service.getTCPLatency() },
            Triple("getUDPLatency", npe) { service.getUDPLatency() },
            Triple("getMaxBandwidth", npe) { service.getMaxBandwidth() },
            Triple("getServerVersion", npe) { service.getServerVersion() },
            Triple("getServerRelease", npe) { service.getServerRelease() },
            Triple("getServerOSName", npe) { service.getServerOSName() },
            Triple("getServerOSVersion", npe) { service.getServerOSVersion() },
            Triple("getSessionId", npe) { service.getSessionId() },
            Triple("getCodec", npe) { service.getCodec() },
            Triple("moveUserToChannel", npe) { service.moveUserToChannel(1, 2) },
            Triple("joinChannel", npe) { service.joinChannel(2) },
            Triple("createChannel", npe) { service.createChannel(0, "n", "d", 0, false) },
            Triple("sendAccessTokens", npe) { service.sendAccessTokens(listOf("t")) },
            Triple("requestPermissions", npe) { service.requestPermissions(0) },
            Triple("requestComment", npe) { service.requestComment(1) },
            Triple("requestAvatar", npe) { service.requestAvatar(1) },
            Triple("requestChannelDescription", npe) { service.requestChannelDescription(0) },
            Triple("registerUser", npe) { service.registerUser(1) },
            Triple("kickBanUser", npe) { service.kickBanUser(1, "r", false) },
            Triple("setUserComment", npe) { service.setUserComment(1, "c") },
            Triple("setPrioritySpeaker", npe) { service.setPrioritySpeaker(1, true) },
            Triple("removeChannel", npe) { service.removeChannel(1) },
            Triple("setMuteDeafState", npe) { service.setMuteDeafState(1, true, false) },
            Triple("setSelfMuteDeafState", npe) { service.setSelfMuteDeafState(true, false) },
            // via getModelHandler(): NotSynchronized, rewrapped.
            Triple("getSessionUser", ise) { service.getSessionUser() },
            Triple("getSessionChannel", ise) { service.getSessionChannel() },
            Triple("getUser", ise) { service.getUser(1) },
            Triple("getChannel", ise) { service.getChannel(1) },
            Triple("getRootChannel", ise) { service.getRootChannel() },
            Triple("getPermissions", ise) { service.getPermissions() },
            Triple("getServerSettings", ise) { service.getServerSettings() },
            Triple("sendUserTextMessage", ise) { service.sendUserTextMessage(1, "m") },
            Triple("sendChannelTextMessage", ise) { service.sendChannelTextMessage(1, "m", false) },
        )

        val wrong = calls.mapNotNull { (name, expected, call) ->
            val thrown = try {
                call()
                null
            } catch (t: Throwable) {
                t
            }
            when {
                thrown == null -> "$name threw nothing, expected ${expected.simpleName}"
                !expected.isInstance(thrown) ->
                    "$name threw ${thrown.javaClass.simpleName}, expected ${expected.simpleName}"
                else -> null
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
