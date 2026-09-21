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
import se.lublin.humla.audio.BluetoothScoReceiver
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.model.Server
import se.lublin.humla.net.ConnectionWarning
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import java.util.concurrent.TimeUnit

/**
 * Characterization of [HumlaService] as it behaves **before** the Kotlin conversion (task A9a).
 *
 * This file exists for one purpose and has one rule: **it is written against the Java service,
 * run green against it, and then run again, unchanged, against the Kotlin one.** A test written
 * after the conversion describes what the conversion produced, not what was there before, and
 * characterizes nothing. Every statement below was therefore committed before `HumlaService.kt`
 * existed.
 *
 * Two consequences for how it is written:
 *
 * - **Every accessor is called as a function, never as a Kotlin synthetic property.**
 *   `service.getConnectionState()`, not `service.connectionState`; `service.isTalking()`, not
 *   `service.isTalking`. A Java getter can be reached either way, a getter *declared in Kotlin*
 *   only as a property, and a Kotlin function only as a call — so the call form is the only form
 *   that compiles against both, and it is what forces the conversion to keep these members
 *   functions rather than turning them into `val`s. (Turning one into a `val` is also how spec
 *   §4.05's `var`/`setX` platform clash gets in: `IHumlaSession` declares `isTalking()` and
 *   `setTalkingState(boolean)` as methods.)
 * - **Assertions are about observable results, not about the shape of the code.** Where the
 *   observable is a value written into an object the service does not own — the fifteen
 *   `AudioHandler.Builder` setters, the threshold on [ActivityInputMode] — it is read back by
 *   reflection over that object's fields, because those objects have no getters. Spec §4.04's
 *   effect pass: for every call into a foreign object, name the test that reads the result back.
 *   Fifteen one-line delegations are exactly what a diff-derived mutation list omits.
 *
 * What this file deliberately does **not** cover: everything that needs a live connection
 * (`onConnectionEstablished`, `onConnectionSynchronized`, `createAudioHandler`, the SCO reload
 * paths, and every `IHumlaSession` call in its *connected* arm). Reaching `CONNECTED` from a
 * Robolectric service means opening a socket to a real server. Those paths are characterized here
 * only in their disconnected arm, which is the arm the conversion can break silently.
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

    /** The builder the service configures. Every extra that touches audio lands in one of its fields. */
    private fun builder(service: HumlaService): Any = field(service, "mAudioBuilder")!!

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
     * A service whose only observer cancels every connection attempt from inside `onConnecting`.
     * `onConnecting` is delivered inline on the handler's own thread, so the cancellation lands
     * before `HumlaConnection.connect` opens anything - the trick
     * [HumlaServiceConnectCancellationTest] uses, and the only way to drive `connect()` in a unit
     * test without a socket.
     */
    private fun cancellingService(): HumlaService = service().also { service ->
        service.registerObserver(object : HumlaObserver() {
            override fun onConnecting() = service.disconnect()
        })
    }

    private fun connectivityManager() = RuntimeEnvironment.getApplication()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    private fun sendConnectivityBroadcast() {
        RuntimeEnvironment.getApplication()
            .sendBroadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---------------------------------------------------------------- lifecycle and initial state

    /**
     * The brief's first characterization: a freshly created service is disconnected and has no
     * session. `HumlaSession()` is the gate the whole binder API sits behind.
     */
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

    /**
     * Effect pass: `onCreate` registers the SCO receiver on the *system*, and `onDestroy` takes it
     * off again. Neither is visible anywhere on the service, so the registry is the only reader.
     */
    @Test
    fun theScoReceiverIsRegisteredForTheLifetimeOfTheService() {
        val controller = Robolectric.buildService(HumlaService::class.java).create()
        controllers += controller

        assertThat(scoReceivers()).hasSize(1)

        controller.destroy()

        assertThat(scoReceivers()).isEmpty()
    }

    private fun scoReceivers() = shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers
        .filter { it.broadcastReceiver is BluetoothScoReceiver }
        .filter { it.intentFilter.hasAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) }

    /**
     * Effect pass: the wake lock is built in `onCreate` with a fixed tag and is *not* taken until
     * synchronization. A conversion that acquires it earlier keeps the CPU awake forever.
     */
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

    /**
     * The four corners of `onStartCommand`'s input: intent present or not, extras present or not,
     * action CONNECT or not, and — under CONNECT — EXTRAS_SERVER present or not. Spec §4.04: for a
     * compound condition over k inputs the requirement is 2^k inputs, not k mutations.
     */
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
     * The ordering inside `onStartCommand`: the extras are applied *before* `connect()` reads them,
     * which is the only reason CONNECT-with-a-server works at all. The observer cancels the attempt
     * from inside `onConnecting` so that nothing opens a socket — the same trick
     * `HumlaServiceConnectCancellationTest` uses, and it doubles as the characterization that
     * `mConnectionState` is already CONNECTING when that callback runs.
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
     * Which extras demand a reconnect, as a table over **every** EXTRAS_ constant the class
     * declares — enumerated by reflection, not written out. Spec §4.04 handle 2, "pin the set, not
     * the member": an extra added later fails this test until someone decides which half it is in.
     */
    @Test
    fun exactlyTheseExtrasRequireAReconnect() {
        val reconnectNeeded = mapOf(
            HumlaService.EXTRAS_SERVER to true,
            HumlaService.EXTRAS_AUTO_RECONNECT to false,
            HumlaService.EXTRAS_AUTO_RECONNECT_DELAY to false,
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
            HumlaService.EXTRAS_ECHO_CANCELLATION_METHOD to false,
            HumlaService.EXTRAS_NOISE_SUPPRESSION_METHOD to false,
            HumlaService.EXTRAS_SPEEX_NOISE_SUPPRESS_DB to false,
            HumlaService.EXTRAS_ANDROID_NOISE_SUPPRESSOR to false,
            HumlaService.EXTRAS_ANDROID_AGC to false,
            HumlaService.EXTRAS_VAD_CONFIG to false,
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
            HumlaService.EXTRAS_AUTO_RECONNECT_DELAY -> putInt(key, 5000)
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
            HumlaService.EXTRAS_ENABLE_PREPROCESSOR -> putBoolean(key, true)
            else -> putString(key, "value-for-$key")
        }
    }

    /**
     * Effect pass over the fifteen `AudioHandler.Builder` setters: four wired in `onCreate` and
     * eleven driven by extras. The builder has no getters, so its fields are the only reader — and
     * without this test every one of those lines is a one-line delegation nothing reads back.
     */
    @Test
    fun everyAudioExtraLandsInTheAudioBuilder() {
        val service = service()
        val extras = Bundle().apply {
            putFloat(HumlaService.EXTRAS_AMPLITUDE_BOOST, 1.5f)
            putInt(HumlaService.EXTRAS_INPUT_RATE, 48000)
            putInt(HumlaService.EXTRAS_INPUT_QUALITY, 40000)
            putInt(HumlaService.EXTRAS_AUDIO_SOURCE, 7)
            putInt(HumlaService.EXTRAS_AUDIO_STREAM, 3)
            putInt(HumlaService.EXTRAS_FRAMES_PER_PACKET, 4)
            putBoolean(HumlaService.EXTRAS_ENABLE_PREPROCESSOR, true)
            putString(HumlaService.EXTRAS_ECHO_CANCELLATION_METHOD, "speex")
            putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_CONTINUOUS)
            putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, true)
        }

        service.configureExtras(extras)

        val b = builder(service)
        // onCreate's four.
        assertThat(field(b, "mContext")).isSameInstanceAs(service)
        assertThat(field(b, "mLogger")).isSameInstanceAs(service)
        assertThat(field(b, "mEncodeListener")).isNotNull()
        assertThat(field(b, "mTalkingListener")).isNotNull()
        // configureExtras's eleven.
        assertThat(field(b, "mAmplitudeBoost")).isEqualTo(1.5f)
        assertThat(field(b, "mInputSampleRate")).isEqualTo(48000)
        assertThat(field(b, "mTargetBitrate")).isEqualTo(40000)
        assertThat(field(b, "mAudioSource")).isEqualTo(7)
        assertThat(field(b, "mAudioStream")).isEqualTo(3)
        assertThat(field(b, "mTargetFramesPerPacket")).isEqualTo(4)
        assertThat(field(b, "mPreprocessorEnabled")).isEqualTo(true)
        assertThat(field(b, "mEchoCancellationMethod")).isEqualTo("speex")
        assertThat(field(b, "mInputMode")).isSameInstanceAs(field(service, "mContinuousInputMode"))
        // The fifteenth, mBluetoothEnabled, is written only by the SCO callbacks; see below.
        assertThat(field(b, "mBluetoothEnabled")).isEqualTo(false)
    }

    /**
     * The transmit mode picks one of the three input modes the service owns, by identity, and a
     * fourth value is refused. Four arms, one test each side of the `switch`.
     */
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
            assertThat(field(builder(service), "mInputMode")).isInstanceOf(type)
        }
    }

    @Test
    fun anUnknownTransmitModeIsRefused() {
        val service = service()

        assertThrows(IllegalArgumentException::class.java) {
            service.configureExtras(Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, 99) })
        }
    }

    /**
     * The chosen input mode is the *same instance* the service answers `isTalking()` from, not a
     * fresh one: the toggle the audio thread consults is the toggle a key press writes.
     */
    @Test
    fun thePushToTalkModeHandedToTheBuilderIsTheOneIsTalkingReads() {
        val service = service()
        service.configureExtras(
            Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK) }
        )

        service.setTalkingState(true)

        val mode = field(builder(service), "mInputMode") as ToggleInputMode
        assertThat(mode.isTalkingOn()).isTrue()
        assertThat(service.isTalking()).isTrue()
    }

    /** Effect pass: the detection threshold is written into the service's own ActivityInputMode. */
    @Test
    fun theDetectionThresholdReachesTheActivityInputMode() {
        val service = service()

        service.configureExtras(Bundle().apply { putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD, 0.25f) })

        val mode = field(service, "mActivityInputMode") as ActivityInputMode
        assertThat(mode.vadConfig.startThreshold).isEqualTo(0.25f)
    }

    /**
     * `mUseTor` and `mForceTcp` accumulate with `|=`: Tor turns TCP on, and neither flag can be
     * turned off again by a later write of `false`. Four corners over the two booleans, read back
     * through the fields, because nothing else exposes them until a connection is built.
     */
    @Test
    fun torForcesTcpAndNeitherFlagCanBeClearedAgain() {
        val service = service()

        service.configureExtras(Bundle().apply { putBoolean(HumlaService.EXTRAS_FORCE_TCP, false) })
        assertThat(field(service, "mForceTcp")).isEqualTo(false)

        service.configureExtras(Bundle().apply { putBoolean(HumlaService.EXTRAS_USE_TOR, true) })
        assertThat(field(service, "mUseTor")).isEqualTo(true)
        assertThat(field(service, "mForceTcp")).isEqualTo(true)

        // Neither `false` takes: the writes are `|=`, not `=`.
        service.configureExtras(Bundle().apply { putBoolean(HumlaService.EXTRAS_FORCE_TCP, false) })
        assertThat(field(service, "mForceTcp")).isEqualTo(true)

        service.configureExtras(Bundle().apply { putBoolean(HumlaService.EXTRAS_USE_TOR, false) })
        assertThat(field(service, "mUseTor")).isEqualTo(false)
        assertThat(field(service, "mForceTcp")).isEqualTo(true)
    }

    /**
     * Half duplex reads `EXTRAS_TRANSMIT_MODE` out of **the same bundle**, not out of the mode the
     * service is in. A settings write that carries only the half-duplex flag therefore always
     * resolves to `false`, because a bundle without the key answers 0 (= voice activity).
     *
     * This is a pre-existing defect, not a decision. It is characterized here rather than fixed:
     * task A9a is behaviour-preserving, and the repair is handed to A9b, which already owns the
     * same finding for `AudioConfig.halfDuplex` (stream core contracts, task 7 block).
     */
    @Test
    fun halfDuplexReadsTheTransmitModeOfItsOwnBundleAndNotTheServiceState() {
        val service = service()
        service.configureExtras(
            Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK) }
        )

        // In push-to-talk, but the bundle does not say so: resolves to false.
        service.configureExtras(Bundle().apply { putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, true) })
        assertThat(field(builder(service), "mHalfDuplexEnabled")).isEqualTo(false)

        // The same write, with the mode repeated in the bundle: resolves to true.
        service.configureExtras(
            Bundle().apply {
                putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, true)
                putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK)
            }
        )
        assertThat(field(builder(service), "mHalfDuplexEnabled")).isEqualTo(true)
    }

    /** The brief's third characterization: a transmit mode change is visible without a reconnect. */
    @Test
    fun transmitModeExtraIsReflectedImmediately() {
        val service = service()
        val extras = Bundle().apply {
            putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK)
        }

        assertThat(service.configureExtras(extras)).isFalse()
        assertThat(service.getTransmitMode()).isEqualTo(Constants.TRANSMIT_PUSH_TO_TALK)
    }

    /** The brief's second characterization: the server extra lands and demands a reconnect. */
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

    @Test
    fun onlyAConnectionErrorWithAutoReconnectOnStartsReconnecting() {
        val corners = listOf(
            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR to true,
            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR to false,
            HumlaException.HumlaDisconnectReason.REJECT to true,
            HumlaException.HumlaDisconnectReason.OTHER_ERROR to true,
        )

        for ((reason, autoReconnect) in corners) {
            val service = service()
            service.configureExtras(
                Bundle().apply {
                    putBoolean(HumlaService.EXTRAS_AUTO_RECONNECT, autoReconnect)
                    putParcelable(HumlaService.EXTRAS_SERVER, server)
                    // Far enough out that the retry never fires inside this test.
                    putInt(HumlaService.EXTRAS_AUTO_RECONNECT_DELAY, 600_000)
                }
            )

            service.onConnectionDisconnected(HumlaException("gone", reason))

            assertThat(service.getConnectionState())
                .isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
            val shouldReconnect =
                autoReconnect && reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
            assertThat(service.isReconnecting()).isEqualTo(shouldReconnect)
            service.cancelReconnect()
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

    /** Effect pass: a disconnect asks the platform to drop SCO, through the receiver it owns. */
    @Test
    fun aDisconnectHaltsBluetoothSco() {
        val service = service()
        val audioManager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        service.onConnectionDisconnected(null)

        @Suppress("DEPRECATION")
        assertThat(audioManager.isBluetoothScoOn).isFalse()
    }

    // ---------------------------------------------------------------- reconnect and connectivity

    /**
     * With connectivity, a reconnect **polls**: the retry is posted to the main looper with
     * `EXTRAS_AUTO_RECONNECT_DELAY` as its delay, and no connectivity receiver is registered. The
     * delay is read back by idling the looper up to one millisecond short of it and then over it,
     * so this pins the delay itself and not merely that something was queued.
     *
     * Robolectric's ConnectivityManager reports a *connected* active network by default, which is
     * the corner this arm needs; the other arm has to set it to null, and does, below. Spec §4.04's
     * fake pass: for each corner, name the double that produces it.
     */
    @Test
    fun aReconnectWithConnectivityPollsAfterTheConfiguredDelay() {
        val service = cancellingService()
        service.configureExtras(Bundle().apply {
            putParcelable(HumlaService.EXTRAS_SERVER, server)
            putInt(HumlaService.EXTRAS_AUTO_RECONNECT_DELAY, 5_000)
        })

        service.setReconnecting(true)

        assertThat(service.isReconnecting()).isTrue()
        assertThat(connectivityReceivers()).isEmpty()
        assertThat(service.getConnection()).isNull()

        shadowOf(Looper.getMainLooper()).idleFor(4_999, TimeUnit.MILLISECONDS)
        assertThat(service.getConnection()).isNull()

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertThat(service.getConnection()).isNotNull()
    }

    /**
     * Without connectivity the service does **not** poll: it registers the connectivity receiver
     * and waits. Idling a full minute past the (zero) delay shows that nothing was queued at all.
     */
    @Test
    fun aReconnectWithoutConnectivityWaitsForTheNetworkInstead() {
        val service = cancellingService()
        service.configureExtras(Bundle().apply { putParcelable(HumlaService.EXTRAS_SERVER, server) })
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)

        service.setReconnecting(true)

        assertThat(service.isReconnecting()).isTrue()
        assertThat(connectivityReceivers()).hasSize(1)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertThat(service.getConnection()).isNull()
    }

    /**
     * The connectivity receiver's own input space: it reconnects only while the service still wants
     * to reconnect **and** the network is back. All three corners that reach it.
     */
    @Test
    fun theConnectivityReceiverReconnectsOnlyWhenTheNetworkIsBack() {
        val service = cancellingService()
        service.configureExtras(Bundle().apply { putParcelable(HumlaService.EXTRAS_SERVER, server) })
        @Suppress("DEPRECATION")
        val connected = connectivityManager().activeNetworkInfo
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        service.setReconnecting(true)

        // Still no network: the broadcast changes nothing and the receiver stays registered.
        sendConnectivityBroadcast()
        assertThat(service.getConnection()).isNull()
        assertThat(connectivityReceivers()).hasSize(1)

        // Network back: the receiver reconnects.
        shadowOf(connectivityManager()).setActiveNetworkInfo(connected)
        sendConnectivityBroadcast()
        assertThat(service.getConnection()).isNotNull()
    }

    /**
     * The receiver's first guard: once reconnecting has been cancelled, a late broadcast
     * unregisters the receiver instead of reconnecting. `cancelReconnect` already unregisters it,
     * so this arm is only reachable when the broadcast beats the unregistration — which is why the
     * guard cannot be dropped as redundant.
     */
    @Test
    fun aBroadcastThatArrivesAfterReconnectingWasClearedUnregistersTheReceiver() {
        val service = cancellingService()
        service.configureExtras(Bundle().apply { putParcelable(HumlaService.EXTRAS_SERVER, server) })
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        service.setReconnecting(true)
        val receiver = connectivityReceivers().single().broadcastReceiver

        // Clear the flag without going through setReconnecting(), so the receiver is still registered.
        field(service, "mReconnecting")
        setField(service, "mReconnecting", false)
        receiver.onReceive(RuntimeEnvironment.getApplication(), Intent())

        assertThat(service.getConnection()).isNull()
        assertThat(connectivityReceivers()).isEmpty()
    }

    /**
     * A retry that fires without a target server crashes on the main looper: `connect()` hands
     * `mServer` straight to `HumlaConnection.connect(Server)`, which is non-null in Kotlin, so a
     * null gets the parameter check rather than a reported failure.
     *
     * Unreachable from the app today — `onConnectionDisconnected` is the only thing that starts a
     * reconnect and it implies a connection, hence a server — but it is what pins that `connect()`
     * has **no** null check on `mServer`. A conversion that writes `checkNotNull(mServer)` turns
     * this NullPointerException into an IllegalStateException; that is A9b's decision, not A9a's.
     */
    @Test
    fun aRetryWithoutATargetServerThrowsOnTheLooperRatherThanReportingAFailure() {
        val service = service()

        service.setReconnecting(true)

        assertThrows(NullPointerException::class.java) {
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun cancelReconnectClearsTheStateAndUnregistersTheConnectivityReceiver() {
        val service = service()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        service.setReconnecting(true)

        service.cancelReconnect()

        assertThat(service.isReconnecting()).isFalse()
        assertThat(connectivityReceivers()).isEmpty()
    }

    /**
     * The `if (mReconnecting == reconnecting) return` guard, both arms. Without it, a second
     * `setReconnecting(true)` registers the receiver twice and posts a second retry — and a
     * `setReconnecting(false)` on a service that never reconnected calls `unregisterReceiver` on an
     * unregistered receiver. (The `catch (IllegalArgumentException)` there makes that survivable,
     * which is why the guard needs a test of its own rather than a crash to prove it.)
     */
    @Test
    fun settingTheSameReconnectStateTwiceDoesNothingTheSecondTime() {
        val service = service()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)

        service.setReconnecting(true)
        service.setReconnecting(true)

        assertThat(connectivityReceivers()).hasSize(1)
    }

    @Test
    fun cancellingAReconnectThatNeverStartedIsHarmless() {
        val service = service()

        service.cancelReconnect()

        assertThat(service.isReconnecting()).isFalse()
        assertThat(connectivityReceivers()).isEmpty()
    }

    private fun connectivityReceivers() = shadowOf(RuntimeEnvironment.getApplication())
        .registeredReceivers
        .filter {
            @Suppress("DEPRECATION")
            it.intentFilter.hasAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }

    // ---------------------------------------------------------------- logging

    /**
     * `logInfo` is dropped before synchronization; `logWarning` and `logError` are not. Three
     * methods of one interface, one guard, and the guard is on exactly one of them.
     */
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

    /**
     * Effect pass: a connection warning is resolved to a string *here*, through this service's
     * resources, and delivered as a warning. The connection raises the enum precisely because it
     * has no Context (see [ConnectionWarning]); this is the other end of that split.
     */
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

    /** The brief's fourth characterization: a voice target id must fit in five bits. */
    @Test
    fun voiceTargetIdMustFitInFiveBits() {
        val service = service()

        assertThrows(IllegalArgumentException::class.java) { service.setVoiceTargetId(0x20) }
    }

    /**
     * Two things this pins that the guard above does not, and both are pre-existing defects that
     * A9a carries over unchanged and hands to A9b:
     *
     * 1. **The guard is `(targetId & ~0x1F) > 0`, not `!= 0`.** For a *negative* byte the masked
     *    value is negative, so the comparison is false and the id passes — `0x80` is accepted as a
     *    voice target although it is nowhere near five bits. `require(masked == 0)` would refuse
     *    it, which is why the conversion must not "tidy" this line.
     * 2. **`mAudioHandler.setVoiceTargetId` is dereferenced unconditionally.** Setting a voice
     *    target while disconnected throws NullPointerException rather than doing nothing, so the
     *    NPE below is the evidence that the id got *past* the guard — and a conversion that writes
     *    `mAudioHandler?.setVoiceTargetId(...)` silently turns this crash into a no-op.
     */
    @Test
    fun aNegativeVoiceTargetIdPassesTheFiveBitGuard() {
        val service = service()

        // Past the guard, into the unguarded audio handler: NPE, not IllegalArgumentException.
        assertThrows(NullPointerException::class.java) { service.setVoiceTargetId(0x80.toByte()) }
        // And a legal id takes exactly the same route.
        assertThrows(NullPointerException::class.java) { service.setVoiceTargetId(0x1F) }
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
     * Which exception each session call throws while the service is disconnected — and it is **not**
     * one answer. The calls that go through `getConnection()` dereference a null field and throw
     * **NullPointerException**; the calls that go through `getModelHandler()`, `getAudioHandler()`
     * or `getBluetoothReceiver()` get a `NotSynchronizedException`, which those methods catch and
     * rethrow as **IllegalStateException**; and two are not implemented at all.
     *
     * This is the single most conversion-fragile thing in the file. Replacing `getConnection()` with
     * a `requireConnection()` that throws `IllegalStateException` — the obvious Kotlin tidy-up, and
     * what the task brief's own listing does — changes eleven of these answers at once, silently,
     * and `IHumlaService`'s own documentation ("any call that depends on connection state will throw
     * IllegalStateException if disconnected") makes the change look like a fix rather than a change.
     * It is a change: it is behaviour A9b may take, with a reason, and A9a may not.
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
            // via getModelHandler()/getAudioHandler()/getBluetoothReceiver(): NotSynchronized, rewrapped.
            Triple("getCurrentBandwidth", ise) { service.getCurrentBandwidth() },
            Triple("getSessionUser", ise) { service.getSessionUser() },
            Triple("getSessionChannel", ise) { service.getSessionChannel() },
            Triple("getUser", ise) { service.getUser(1) },
            Triple("getChannel", ise) { service.getChannel(1) },
            Triple("getRootChannel", ise) { service.getRootChannel() },
            Triple("getPermissions", ise) { service.getPermissions() },
            Triple("getServerSettings", ise) { service.getServerSettings() },
            Triple("usingBluetoothSco", ise) { service.usingBluetoothSco() },
            Triple("enableBluetoothSco", ise) { service.enableBluetoothSco() },
            Triple("disableBluetoothSco", ise) { service.disableBluetoothSco() },
            Triple("sendUserTextMessage", ise) { service.sendUserTextMessage(1, "m") },
            Triple("sendChannelTextMessage", ise) { service.sendChannelTextMessage(1, "m", false) },
            // not implemented at all.
            Triple("requestBanList", UnsupportedOperationException::class.java) { service.requestBanList() },
            Triple("requestUserList", UnsupportedOperationException::class.java) { service.requestUserList() },
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
    }
}
