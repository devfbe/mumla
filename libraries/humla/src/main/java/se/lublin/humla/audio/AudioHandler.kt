/*
 * Copyright (C) 2014 Andrew Comminos
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
package se.lublin.humla.audio

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioManager
import android.util.Log
import com.google.protobuf.MessageLite
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.AudioSourcePolicy
import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.SpeexPreprocessor
import se.lublin.humla.audio.encoder.IEncoder
import se.lublin.humla.audio.encoder.OpusEncoder
import se.lublin.humla.exception.AudioException
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.TcpMessageHandler
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.net.VoicePacket
import se.lublin.humla.net.VoicePacketHandler
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.R
import se.lublin.humla.util.HumlaLogger

/**
 * Bridges the protocol's audio messages to the input and output threads. Audio playback and
 * recording are controlled exclusively by the protocol. [shutdown] cleans up both threads;
 * restarting afterwards is safe. Built with [Builder].
 */
class AudioHandler private constructor(builder: Builder, targetId: Byte) :
    TcpMessageHandler, VoicePacketHandler {

    private val context: Context = builder.context
    private val logger: HumlaLogger = builder.logger
    private val encodeListener: AudioEncodeListener = builder.encodeListener
    private val inputMode: IInputMode = builder.inputMode
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val input: AudioInput
    private val output: AudioOutput

    /** Capture chain in front of the encoder. Touched only by the capture thread, released in [shutdown]. */
    private val capturePipeline: CapturePipeline

    /** Captured frames to voice packets; the capture thread's listener. */
    private val transmitter: VoiceTransmitter

    private var session = 0

    val codec: HumlaUDPMessageType? get() = transmitter.codec

    val audioStream: Int = builder.audioStream
    val audioSource: Int
    val sampleRate: Int = builder.inputSampleRate
    var bitrate: Int = builder.targetBitrate
        private set
    var framesPerPacket: Int = builder.targetFramesPerPacket
        private set
    val amplitudeBoost: Float = builder.amplitudeBoost

    /** True if outgoing audio mutes playback while talking. */
    val isHalfDuplex: Boolean = builder.halfDuplexEnabled

    /** True once playback and recording run. */
    var isInitialized = false
        private set

    init {
        val effects = AndroidAudioEffects(builder.androidNoiseSuppressor, builder.androidAutomaticGainControl)
        val echo = EchoCancellationMode.fromPreferenceValue(builder.echoCancellationMethod)
        // Recording source and audio mode must agree: AudioSourcePolicy also resolves the source
        // inside PcmCaptureSource, so the WebRTC canceller never captures on VOICE_COMMUNICATION
        // while the manager is still in MODE_NORMAL. In a session AudioRouter already holds
        // MODE_IN_COMMUNICATION; this is a second request for the same mode.
        if (AudioSourcePolicy.needsCommunicationMode(effects, echo)) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        audioSource = AudioSourcePolicy.resolve(builder.audioSource, effects, echo)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioInitializationException("RECORD_AUDIO permission not granted")
        }
        // The listener is the transmitter below; AudioInput only calls it once recording starts.
        input = AudioInput(
            { frame, size -> transmitter.onAudioInputReceived(frame, size) },
            audioSource, sampleRate, echo.preferenceValue, effects,
        )
        // `preprocessor_enabled` means "suppress noise" (RNNoise). Echo cancellation is AEC3 or
        // none, per routed device. Both ends of the canceller come from one call: the chain goes
        // to the capture thread, the far-end tap to AudioOutput's playback thread, which must feed
        // the reference for AEC to work.
        val noise = builder.noiseSuppressionMethod?.let(NoiseSuppressionMode::fromPreferenceValue)
            ?: if (builder.preprocessorEnabled) NoiseSuppressionMode.RNNOISE else NoiseSuppressionMode.NONE
        val wiring = CaptureWiring.wire(
            input.sampleRate, inputMode, amplitudeBoost, noise, echo, builder.speexNoiseSuppressDb, logger,
        )
        capturePipeline = wiring.pipeline
        transmitter = VoiceTransmitter(capturePipeline, encodeListener) { talking ->
            if (isHalfDuplex) {
                val direction = if (talking) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(audioStream, direction, 0)
            }
        }
        transmitter.targetId = targetId
        transmitter.udpProtocol = builder.udpProtocol
        output = AudioOutput(builder.talkingListener, wiring.farEnd)
    }

    /**
     * Starts playback and recording, unless already running.
     * @throws AudioException if a thread is already recording or the encoder cannot be created.
     */
    @Synchronized
    fun initialize(self: User, maxBandwidth: Int, codec: HumlaUDPMessageType?) {
        if (isInitialized) return
        session = self.session
        setMaxBandwidth(maxBandwidth)
        transmitter.setCodec(codec, ::createEncoder)
        transmitter.muteState = SelfMuteState(self.isMuted || self.isLocalMuted, self.isSelfMuted, self.isSuppressed)
        synchronized(input) {
            if (input.isRecording()) throw AudioException("Attempted to start recording while recording!")
            input.startRecording()
        }
        output.startPlaying(audioStream)
        isInitialized = true
    }

    val isPlaying: Boolean
        get() = synchronized(output) { output.isPlaying() }

    /**
     * The encoder for [codec], or null (input disabled) for one it cannot encode.
     * @throws NativeAudioException if the encoder cannot be created.
     */
    private fun createEncoder(codec: HumlaUDPMessageType): IEncoder? = when (codec) {
        // Resampling and preprocessing happen in capturePipeline, before the voice detector.
        HumlaUDPMessageType.UDPVoiceOpus ->
            OpusEncoder(SAMPLE_RATE, 1, FRAME_SIZE, framesPerPacket, bitrate, MAX_BUFFER_SIZE)
        else -> {
            Log.w(TAG, "Unsupported codec, input disabled.")
            null
        }
    }

    /** Fits the bitrate and frames per packet to the server's maximum bandwidth in bps; -1 means no limit. */
    private fun setMaxBandwidth(maxBandwidth: Int) {
        if (maxBandwidth == -1) return
        val (newBitrate, newFramesPerPacket) = fitToBandwidth(bitrate, framesPerPacket, maxBandwidth)
        if (newBitrate != bitrate || newFramesPerPacket != framesPerPacket) {
            bitrate = newBitrate
            framesPerPacket = newFramesPerPacket
            val kbps = maxBandwidth / BPS_PER_KBPS
            logger.logInfo(
                context.getString(R.string.audio_max_bandwidth, kbps, kbps, newFramesPerPacket * MS_PER_FRAME),
            )
        }
    }

    val currentBandwidth: Int
        get() = HumlaConnection.calculateAudioBandwidth(bitrate, framesPerPacket)

    /** Halts input and output. */
    @Synchronized
    fun shutdown() {
        synchronized(input) { input.shutdown() }
        // After the capture thread is joined, because every stage in it is single-threaded and
        // release() frees native state the loop reaches on every frame.
        capturePipeline.release()
        synchronized(output) { output.stopPlaying() }
        transmitter.releaseEncoder()
        isInitialized = false
        encodeListener.onTalkingStateChanged(false)
    }

    override fun onMessage(msg: MessageLite) {
        when (msg) {
            is Mumble.CodecVersion -> onCodecVersion(msg)
            is Mumble.ServerSync -> setMaxBandwidth(if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1)
            // Stop audio input if the user is muted, and resume if the user has set talking enabled.
            // Before ServerSync there is nothing to update.
            is Mumble.UserState ->
                if (isInitialized && msg.hasSession() && msg.session == session) transmitter.muteState.update(msg)
        }
    }

    private fun onCodecVersion(msg: Mumble.CodecVersion) {
        if (!isInitialized) return // Only listen to change events in this handler.

        // Without Opus there is no codec to encode with; null turns input off.
        val newCodec = if (msg.opus) HumlaUDPMessageType.UDPVoiceOpus else null
        try {
            transmitter.setCodecIfChanged(newCodec, ::createEncoder)
        } catch (e: NativeAudioException) {
            Log.e(TAG, "Could not create the encoder", e)
        }
    }

    override fun onVoicePacket(packet: VoicePacket) {
        synchronized(output) { output.queueVoiceData(packet) }
    }

    fun setVoiceTargetId(id: Byte) {
        transmitter.targetId = id
    }

    interface AudioEncodeListener {
        /** [data] is reused for the next packet: valid only until this call returns. */
        fun onAudioEncoded(data: ByteArray, length: Int)
        fun onTalkingStateChanged(talking: Boolean)
    }

    /** Configures and creates an [AudioHandler]. The values are read back only by the handler. */
    @Suppress("TooManyFunctions")
    open class Builder {
        internal lateinit var context: Context
            private set
        internal lateinit var logger: HumlaLogger
            private set
        internal var audioStream = 0
            private set
        internal var audioSource = 0
            private set
        internal var targetBitrate = 0
            private set
        internal var targetFramesPerPacket = 0
            private set
        internal var inputSampleRate = 0
            private set
        internal var amplitudeBoost = 0f
            private set
        internal var halfDuplexEnabled = false
            private set
        internal var preprocessorEnabled = false
            private set
        internal var noiseSuppressionMethod: String? = null
            private set
        internal var echoCancellationMethod: String? = null
            private set
        internal var speexNoiseSuppressDb = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB
            private set
        internal var androidNoiseSuppressor = false
            private set
        internal var androidAutomaticGainControl = false
            private set
        internal lateinit var inputMode: IInputMode
            private set
        internal var udpProtocol = UdpProtocol.LEGACY
            private set
        internal lateinit var encodeListener: AudioEncodeListener
            private set
        internal lateinit var talkingListener: AudioOutput.AudioOutputListener
            private set

        open fun setContext(context: Context): Builder = apply { this.context = context }
        open fun setLogger(logger: HumlaLogger): Builder = apply { this.logger = logger }
        open fun setAudioStream(audioStream: Int): Builder = apply { this.audioStream = audioStream }
        open fun setAudioSource(audioSource: Int): Builder = apply { this.audioSource = audioSource }
        open fun setTargetBitrate(targetBitrate: Int): Builder = apply { this.targetBitrate = targetBitrate }
        open fun setTargetFramesPerPacket(framesPerPacket: Int): Builder =
            apply { targetFramesPerPacket = framesPerPacket }
        open fun setInputSampleRate(sampleRate: Int): Builder = apply { inputSampleRate = sampleRate }
        open fun setAmplitudeBoost(boost: Float): Builder = apply { amplitudeBoost = boost }
        open fun setHalfDuplexEnabled(enabled: Boolean): Builder = apply { halfDuplexEnabled = enabled }
        open fun setNoiseSuppressionMethod(method: String?): Builder = apply { noiseSuppressionMethod = method }
        open fun setPreprocessorEnabled(enabled: Boolean): Builder = apply { preprocessorEnabled = enabled }
        open fun setEchoCancellationMethod(method: String?): Builder = apply { echoCancellationMethod = method }
        open fun setSpeexNoiseSuppressDb(db: Int): Builder = apply { speexNoiseSuppressDb = db }
        open fun setAndroidNoiseSuppressor(enabled: Boolean): Builder = apply { androidNoiseSuppressor = enabled }
        open fun setAndroidAutomaticGainControl(enabled: Boolean): Builder =
            apply { androidAutomaticGainControl = enabled }
        open fun setEncodeListener(listener: AudioEncodeListener): Builder = apply { encodeListener = listener }
        open fun setTalkingListener(listener: AudioOutput.AudioOutputListener): Builder =
            apply { talkingListener = listener }
        open fun setInputMode(mode: IInputMode): Builder = apply { inputMode = mode }
        open fun setUdpProtocol(protocol: UdpProtocol): Builder = apply { udpProtocol = protocol }

        /**
         * Creates an AudioHandler for the given session and starts playback and recording.
         * @throws AudioException if the audio devices or the encoder cannot be set up.
         */
        open fun initialize(self: User, maxBandwidth: Int, codec: HumlaUDPMessageType?, targetId: Byte): AudioHandler =
            AudioHandler(this, targetId).also { it.initialize(self, maxBandwidth, codec) }
    }

    companion object {
        private val TAG = AudioHandler::class.java.name

        const val SAMPLE_RATE = 48_000
        const val FRAME_SIZE = SAMPLE_RATE / 100
        const val MAX_BUFFER_SIZE = 960

        private const val BPS_PER_KBPS = 1000
        private const val MS_PER_FRAME = 10
    }
}

/**
 * The bitrate and frames per packet that fit [maxBandwidth] bps, as desktop Mumble's
 * `AudioInput::adjustBandwidth` picks them.
 */
@Suppress("MagicNumber") // Desktop Mumble's thresholds.
internal fun fitToBandwidth(bitrate: Int, framesPerPacket: Int, maxBandwidth: Int): Pair<Int, Int> {
    var newBitrate = bitrate
    var newFramesPerPacket = framesPerPacket
    if (HumlaConnection.calculateAudioBandwidth(newBitrate, newFramesPerPacket) > maxBandwidth) {
        newFramesPerPacket = when {
            newFramesPerPacket <= 4 && maxBandwidth <= 32_000 -> 4
            newFramesPerPacket == 1 && maxBandwidth <= 64_000 -> 2
            newFramesPerPacket == 2 && maxBandwidth <= 48_000 -> 4
            else -> newFramesPerPacket
        }
        while (HumlaConnection.calculateAudioBandwidth(newBitrate, newFramesPerPacket) > maxBandwidth &&
            newBitrate > 8_000
        ) {
            newBitrate -= 1_000
        }
    }
    return maxOf(8_000, newBitrate) to newFramesPerPacket
}
