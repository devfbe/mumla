/*
 * Copyright (C) 2026 The Mumla Authors
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
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.TcpMessageHandler
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.net.VoicePacketHandler
import se.lublin.humla.util.HumlaLogger

/** A running audio pipeline as [AudioController] sees it (real: AudioHandler; tests: fakes). */
interface ManagedAudio {
    val tcpHandler: TcpMessageHandler
    val voiceHandler: VoicePacketHandler
    val currentBandwidth: Int
    fun setVoiceTargetId(id: Byte)

    /** Receives user-facing warnings (microphone silenced, decoder errors); null unregisters. */
    fun setWarningListener(listener: ((String) -> Unit)?)

    /**
     * Stops capture and playback. May block for as long as the capture thread takes to notice, so
     * it is only ever called on [AudioController.THREAD_NAME].
     */
    fun shutdown()
}

/** Per-session inputs that only exist after ServerSync. */
data class AudioSessionParams(
    val self: User,
    val maxBandwidth: Int,
    val codec: HumlaUDPMessageType?,
    val targetId: Byte,
    val inputMode: IInputMode,
    /** The connection's voice packet format. */
    val udpProtocol: UdpProtocol = UdpProtocol.LEGACY,
)

interface AudioHandlerFactory {
    @Throws(AudioException::class)
    fun create(
        context: Context,
        logger: HumlaLogger,
        config: AudioConfig,
        params: AudioSessionParams,
        encodeListener: AudioHandler.AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener,
    ): ManagedAudio
}

/**
 * Builds the real [AudioHandler]. [AudioConfig.echoCancellation] maps to the WebRTC canceller or
 * none.
 */
class DefaultAudioHandlerFactory(
    /** The builder [builder] fills; tests pass one that records the setter calls. */
    private val newBuilder: () -> AudioHandler.Builder = { AudioHandler.Builder() },
) : AudioHandlerFactory {
    @Throws(AudioException::class)
    override fun create(
        context: Context,
        logger: HumlaLogger,
        config: AudioConfig,
        params: AudioSessionParams,
        encodeListener: AudioHandler.AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener,
    ): ManagedAudio = AudioHandlerAdapter(
        initialize(builder(context, logger, config, params, encodeListener, outputListener), params),
    )

    /**
     * The per-session arguments. `self` carries the session id stamped on every voice packet, so a
     * wrong one means sending as somebody else.
     */
    @Throws(AudioException::class)
    internal fun initialize(builder: AudioHandler.Builder, params: AudioSessionParams): AudioHandler =
        builder.initialize(params.self, params.maxBandwidth, params.codec, params.targetId)

    /** The config-to-builder mapping, split from `initialize` so JVM tests can inspect it. */
    internal fun builder(
        context: Context,
        logger: HumlaLogger,
        config: AudioConfig,
        params: AudioSessionParams,
        encodeListener: AudioHandler.AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener,
    ): AudioHandler.Builder =
        newBuilder()
            .setContext(context)
            .setLogger(logger)
            .setAudioStream(config.playbackStream)
            .setAudioSource(config.audioSource)
            .setInputSampleRate(config.inputSampleRate)
            .setTargetBitrate(config.targetBitrate)
            .setTargetFramesPerPacket(config.targetFramesPerPacket)
            .setAmplitudeBoost(config.amplitudeBoost)
            .setHalfDuplexEnabled(config.halfDuplex)
            .setPreprocessorEnabled(config.preprocessorEnabled)
            .setEchoCancellationMethod(
                if (config.echoCancellation) EchoCancellationMode.WEBRTC.preferenceValue
                else EchoCancellationMode.NONE.preferenceValue,
            )
            .setInputMode(params.inputMode)
            .setUdpProtocol(params.udpProtocol)
            .setEncodeListener(encodeListener)
            .setTalkingListener(outputListener)
            .setNoiseSuppressionMethod(config.noiseSuppression)
            .setSpeexNoiseSuppressDb(config.speexNoiseSuppressDb)
            .setAndroidNoiseSuppressor(config.androidNoiseSuppressor)
            .setAndroidAutomaticGainControl(config.androidAgc)
}

/** Dresses an [AudioHandler] as a [ManagedAudio]; every member but the warning channel delegates. */
class AudioHandlerAdapter(private val handler: AudioHandler) : ManagedAudio {
    @Volatile private var warningListener: ((String) -> Unit)? = null

    override val tcpHandler: TcpMessageHandler get() = handler
    override val voiceHandler: VoicePacketHandler get() = handler
    override val currentBandwidth: Int get() = handler.currentBandwidth
    override fun setVoiceTargetId(id: Byte) = handler.setVoiceTargetId(id)

    override fun setWarningListener(listener: ((String) -> Unit)?) {
        warningListener = listener
    }

    /**
     * Reports a user-facing audio problem; [AudioController] posts it to the main thread and
     * `HumlaService` logs it to chat.
     *
     * Intended for `CaptureState.Silenced`/`Error` from `handler.captureState`; nothing calls this
     * in production yet.
     */
    fun reportWarning(message: String) {
        warningListener?.invoke(message)
    }

    override fun shutdown() = handler.shutdown()
}
