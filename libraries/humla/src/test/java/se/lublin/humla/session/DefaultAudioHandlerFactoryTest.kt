package se.lublin.humla.session

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.testutil.SilentLogger
import android.content.Context
import se.lublin.humla.Constants
import java.lang.reflect.Modifier

/**
 * The real factory needs a microphone, so what a JVM test can reach is the config-to-builder
 * mapping and the part of AudioHandler's constructor that runs before the RECORD_AUDIO check.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultAudioHandlerFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val factory = DefaultAudioHandlerFactory()
    private val params = AudioSessionParams(
        User(1, "me"), 72_000, HumlaUDPMessageType.UDPVoiceOpus, 0, ContinuousInputMode(),
    )
    private val encodeListener = object : AudioHandler.AudioEncodeListener {
        override fun onAudioEncoded(data: ByteArray, length: Int) = Unit
        override fun onTalkingStateChanged(talking: Boolean) = Unit
    }
    private val outputListener = object : AudioOutput.AudioOutputListener {
        override fun onUserTalkStateUpdated(user: User) = Unit
        override fun getUser(session: Int): User? = null
    }

    private fun create(config: AudioConfig) =
        factory.create(context, SilentLogger, config, params, encodeListener, outputListener)

    @Test
    fun withoutTheRecordAudioPermissionCreationFailsWithAnAudioException() {
        val e = assertThrows(AudioException::class.java) { create(AudioConfig()) }
        assertThat(e).hasMessageThat().contains("RECORD_AUDIO")
    }

    /**
     * The canceller the route decided on reaches the builder as the value `AudioHandler` knows:
     * on is WebRTC's AEC3, and `AudioHandler` puts the manager into communication mode for it
     * before the permission check - the one effect a JVM test can read.
     */
    @Test
    fun echoCancellationReachesTheBuilderAsTheWebRtcCanceller() {
        fun method(config: AudioConfig) =
            AudioHandler.Builder::class.java.getDeclaredField("mEchoCancellationMethod")
                .apply { isAccessible = true }
                .get(factory.builder(context, SilentLogger, config, params, encodeListener, outputListener))

        assertThat(method(AudioConfig(echoCancellation = true))).isEqualTo("webrtc")
        assertThat(method(AudioConfig(echoCancellation = false))).isEqualTo("none")

        assertThrows(AudioException::class.java) { create(AudioConfig(echoCancellation = true)) }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    /**
     * Enumerated from `AudioHandler.Builder`'s own fields rather than written out, so a setter
     * stream B adds and forgets to wire fails here the moment the field exists (spec 4.04: pin the
     * set, not the member). Every value below is distinct from every other and from the Java
     * default, so a cross-wiring is visible and not only an omission.
     */
    @Test
    fun everyBuilderFieldIsSetFromTheConfigAndTheSessionParams() {
        val inputMode = ContinuousInputMode()
        val config = AudioConfig(
            audioStream = AudioManager.STREAM_ALARM,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            inputSampleRate = 16_000,
            targetBitrate = 24_000,
            targetFramesPerPacket = 4,
            amplitudeBoost = 2.5f,
            transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
            halfDuplexRequested = true,
            preprocessorEnabled = true,
            echoCancellation = true,
            routedDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            noiseSuppression = "rnnoise",
            speexNoiseSuppressDb = -35,
            androidNoiseSuppressor = true,
            androidAgc = false,
        )

        val builder = factory.builder(
            context, SilentLogger, config, params.copy(inputMode = inputMode),
            encodeListener, outputListener,
        )

        val expected = mapOf(
            "mContext" to context,
            "mLogger" to SilentLogger,
            // Not STREAM_ALARM: a routed device moves playback to the voice-call stream, see
            // aRoutedDevicePlaysOnTheVoiceCallStreamAndOnlyBluetoothIsBluetooth.
            "mAudioStream" to AudioManager.STREAM_VOICE_CALL,
            "mAudioSource" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "mInputSampleRate" to 16_000,
            "mTargetBitrate" to 24_000,
            "mTargetFramesPerPacket" to 4,
            "mAmplitudeBoost" to 2.5f,
            "mBluetoothEnabled" to true,
            "mHalfDuplexEnabled" to true,
            "mPreprocessorEnabled" to true,
            "mEchoCancellationMethod" to "webrtc",
            "mNoiseSuppressionMethod" to "rnnoise",
            "mSpeexNoiseSuppressDb" to -35,
            "mAndroidNoiseSuppressor" to true,
            "mAndroidAutomaticGainControl" to false,
            "mInputMode" to inputMode,
            "mEncodeListener" to encodeListener,
            "mTalkingListener" to outputListener,
        )
        val declared = AudioHandler.Builder::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        assertThat(declared.map { it.name }).containsExactlyElementsIn(expected.keys)
        for (field in declared) {
            field.isAccessible = true
            assertThat(field.get(builder)).isEqualTo(expected.getValue(field.name))
        }
    }

    /**
     * The three boolean fields cannot all be distinct inside one fixture, so the fixture above
     * cannot tell them apart - measured: reading `preprocessorEnabled` into `setBluetoothEnabled`
     * survived it, because both were true there. Three patterns, chosen so that every pair differs
     * in at least one of them. This is spec 4.04's fixture rule, applied to a test of my own.
     */
    @Test
    fun theThreeBooleanBuilderFieldsAreNotInterchangeable() {
        fun booleansOf(config: AudioConfig): Triple<Any?, Any?, Any?> {
            val b = factory.builder(context, SilentLogger, config, params, encodeListener, outputListener)
            fun read(name: String) =
                AudioHandler.Builder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(b)
            return Triple(read("mBluetoothEnabled"), read("mPreprocessorEnabled"), read("mHalfDuplexEnabled"))
        }

        assertThat(
            booleansOf(
                AudioConfig(
                    routedDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, preprocessorEnabled = false,
                    halfDuplexRequested = true, transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
                ),
            ),
        ).isEqualTo(Triple(true, false, true))
        assertThat(
            booleansOf(AudioConfig(routedDeviceType = null, preprocessorEnabled = true, halfDuplexRequested = false)),
        ).isEqualTo(Triple(false, true, false))
        assertThat(
            booleansOf(
                AudioConfig(
                    routedDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, preprocessorEnabled = false,
                    halfDuplexRequested = false, transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
                ),
            ),
        ).isEqualTo(Triple(true, false, false))
    }

    /**
     * The stream and the Bluetooth flag both depend on the routed device, which the fixture above
     * can only show one value of. Unrouted, the configured stream goes through as it is; routed to
     * a device that is not a headset, playback moves to the voice-call stream - the only one that
     * follows the communication device - and Bluetooth stays off.
     */
    @Test
    fun aRoutedDevicePlaysOnTheVoiceCallStreamAndOnlyBluetoothIsBluetooth() {
        fun streamAndBluetooth(config: AudioConfig): Pair<Any?, Any?> {
            val b = factory.builder(context, SilentLogger, config, params, encodeListener, outputListener)
            fun read(name: String) =
                AudioHandler.Builder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(b)
            return read("mAudioStream") to read("mBluetoothEnabled")
        }

        assertThat(streamAndBluetooth(AudioConfig(audioStream = AudioManager.STREAM_ALARM)))
            .isEqualTo(AudioManager.STREAM_ALARM to false)
        assertThat(
            streamAndBluetooth(
                AudioConfig(
                    audioStream = AudioManager.STREAM_ALARM,
                    routedDeviceType = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                ),
            ),
        ).isEqualTo(AudioManager.STREAM_VOICE_CALL to false)
    }

    /** The per-session arguments, which are the session identity and not just a setting. */
    @Test
    fun theSessionParamsAreWhatInitializeIsCalledWith() {
        val builder = mockk<AudioHandler.Builder>(relaxed = true)
        val session = params.copy(self = User(9, "someone"), maxBandwidth = 48_000, targetId = 3)

        factory.initialize(builder, session)

        verify(exactly = 1) {
            builder.initialize(session.self, 48_000, HumlaUDPMessageType.UDPVoiceOpus, 3)
        }
    }

    /** Half duplex reaches the builder through the rule, not as the raw request (spec A7). */
    @Test
    fun halfDuplexReachesTheBuilderThroughTheRule() {
        val requestedButNotPushToTalk = AudioConfig(
            halfDuplexRequested = true, transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY,
        )
        val builder = factory.builder(
            context, SilentLogger, requestedButNotPushToTalk, params, encodeListener, outputListener,
        )
        val field = AudioHandler.Builder::class.java.getDeclaredField("mHalfDuplexEnabled")
        field.isAccessible = true
        assertThat(field.get(builder)).isEqualTo(false)
    }
}
