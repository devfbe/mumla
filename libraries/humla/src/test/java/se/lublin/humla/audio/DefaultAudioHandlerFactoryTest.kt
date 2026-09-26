package se.lublin.humla.audio

import android.content.Context
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
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.testutil.SilentLogger
import se.lublin.humla.util.Constants
import se.lublin.humla.util.HumlaLogger

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

    private val host get() = AudioHost(context, SilentLogger, encodeListener, outputListener)

    private fun create(config: AudioConfig) =
        factory.create(host, config, params)

    /** What the factory hands the builder, keyed by setter name. */
    private fun built(config: AudioConfig, session: AudioSessionParams = params): Map<String, Any?> =
        RecordingBuilder().also { recording ->
            DefaultAudioHandlerFactory { recording }
                .builder(host, config, session)
        }.values

    /** Records every setter call; each setter is overridden, which the field-completeness test relies on. */
    private class RecordingBuilder : AudioHandler.Builder() {
        val values = linkedMapOf<String, Any?>()

        private fun rec(name: String, value: Any?): AudioHandler.Builder = apply { values[name] = value }

        override fun setContext(context: Context) = rec("setContext", context).also { super.setContext(context) }
        override fun setLogger(logger: HumlaLogger) = rec("setLogger", logger).also { super.setLogger(logger) }
        override fun setAudioStream(v: Int) = rec("setAudioStream", v).also { super.setAudioStream(v) }
        override fun setAudioSource(v: Int) = rec("setAudioSource", v).also { super.setAudioSource(v) }
        override fun setTargetBitrate(v: Int) = rec("setTargetBitrate", v).also { super.setTargetBitrate(v) }
        override fun setTargetFramesPerPacket(v: Int) =
            rec("setTargetFramesPerPacket", v).also { super.setTargetFramesPerPacket(v) }
        override fun setInputSampleRate(v: Int) = rec("setInputSampleRate", v).also { super.setInputSampleRate(v) }
        override fun setAmplitudeBoost(v: Float) = rec("setAmplitudeBoost", v).also { super.setAmplitudeBoost(v) }
        override fun setHalfDuplexEnabled(v: Boolean) =
            rec("setHalfDuplexEnabled", v).also { super.setHalfDuplexEnabled(v) }
        override fun setNoiseSuppressionMethod(v: String?) =
            rec("setNoiseSuppressionMethod", v).also { super.setNoiseSuppressionMethod(v) }
        override fun setPreprocessorEnabled(v: Boolean) =
            rec("setPreprocessorEnabled", v).also { super.setPreprocessorEnabled(v) }
        override fun setEchoCancellationMethod(v: String?) =
            rec("setEchoCancellationMethod", v).also { super.setEchoCancellationMethod(v) }
        override fun setSpeexNoiseSuppressDb(v: Int) =
            rec("setSpeexNoiseSuppressDb", v).also { super.setSpeexNoiseSuppressDb(v) }
        override fun setAndroidNoiseSuppressor(v: Boolean) =
            rec("setAndroidNoiseSuppressor", v).also { super.setAndroidNoiseSuppressor(v) }
        override fun setAndroidAutomaticGainControl(v: Boolean) =
            rec("setAndroidAutomaticGainControl", v).also { super.setAndroidAutomaticGainControl(v) }
        override fun setEncodeListener(v: AudioHandler.AudioEncodeListener) =
            rec("setEncodeListener", v).also { super.setEncodeListener(v) }
        override fun setTalkingListener(v: AudioOutput.AudioOutputListener) =
            rec("setTalkingListener", v).also { super.setTalkingListener(v) }
        override fun setInputMode(v: IInputMode) = rec("setInputMode", v).also { super.setInputMode(v) }
        override fun setUdpProtocol(v: UdpProtocol) = rec("setUdpProtocol", v).also { super.setUdpProtocol(v) }
    }

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
        fun method(config: AudioConfig) = built(config)["setEchoCancellationMethod"]

        assertThat(method(AudioConfig(echoCancellation = true))).isEqualTo("webrtc")
        assertThat(method(AudioConfig(echoCancellation = false))).isEqualTo("none")

        assertThrows(AudioException::class.java) { create(AudioConfig(echoCancellation = true)) }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    /**
     * Enumerated from `AudioHandler.Builder`'s public setters, so a new setter that is not wired fails
     * here. Every value is distinct from every other and from the default, so a cross-wiring shows.
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

        val expected = mapOf(
            "setContext" to context,
            "setLogger" to SilentLogger,
            // Not STREAM_ALARM: a routed device moves playback to the voice-call stream, see
            // aRoutedDevicePlaysOnTheVoiceCallStream.
            "setAudioStream" to AudioManager.STREAM_VOICE_CALL,
            "setAudioSource" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "setInputSampleRate" to 16_000,
            "setTargetBitrate" to 24_000,
            "setTargetFramesPerPacket" to 4,
            "setAmplitudeBoost" to 2.5f,
            "setHalfDuplexEnabled" to true,
            "setPreprocessorEnabled" to true,
            "setEchoCancellationMethod" to "webrtc",
            "setNoiseSuppressionMethod" to "rnnoise",
            "setSpeexNoiseSuppressDb" to -35,
            "setAndroidNoiseSuppressor" to true,
            "setAndroidAutomaticGainControl" to false,
            "setInputMode" to inputMode,
            "setUdpProtocol" to UdpProtocol.PROTOBUF,
            "setEncodeListener" to encodeListener,
            "setTalkingListener" to outputListener,
        )
        val setters = AudioHandler.Builder::class.java.methods.map { it.name }.filter { it.startsWith("set") }
        assertThat(setters).containsExactlyElementsIn(expected.keys)
        val session = params.copy(inputMode = inputMode, udpProtocol = UdpProtocol.PROTOBUF)
        assertThat(built(config, session)).containsExactlyEntriesIn(expected)
    }

    /** The two boolean fields must not be cross-wired. */
    @Test
    fun theBooleanBuilderFieldsAreNotInterchangeable() {
        fun booleansOf(config: AudioConfig): Pair<Any?, Any?> =
            built(config).let { it["setPreprocessorEnabled"] to it["setHalfDuplexEnabled"] }

        assertThat(
            booleansOf(
                AudioConfig(
                    preprocessorEnabled = false,
                    halfDuplexRequested = true, transmitMode = Constants.TRANSMIT_PUSH_TO_TALK,
                ),
            ),
        ).isEqualTo(false to true)
        assertThat(
            booleansOf(AudioConfig(preprocessorEnabled = true, halfDuplexRequested = false)),
        ).isEqualTo(true to false)
    }

    /**
     * Unrouted, the configured stream goes through as it is; routed to any device, playback moves
     * to the voice-call stream, the only one that follows the communication device.
     */
    @Test
    fun aRoutedDevicePlaysOnTheVoiceCallStream() {
        fun stream(config: AudioConfig): Any? = built(config)["setAudioStream"]

        assertThat(stream(AudioConfig(audioStream = AudioManager.STREAM_ALARM)))
            .isEqualTo(AudioManager.STREAM_ALARM)
        assertThat(
            stream(
                AudioConfig(
                    audioStream = AudioManager.STREAM_ALARM,
                    routedDeviceType = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                ),
            ),
        ).isEqualTo(AudioManager.STREAM_VOICE_CALL)
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

    /** Half duplex reaches the builder through the rule, not as the raw request. */
    @Test
    fun halfDuplexReachesTheBuilderThroughTheRule() {
        val requestedButNotPushToTalk = AudioConfig(
            halfDuplexRequested = true, transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY,
        )
        assertThat(built(requestedButNotPushToTalk)["setHalfDuplexEnabled"]).isEqualTo(false)
    }
}
