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

package se.lublin.humla.session

import android.content.Context
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.util.HumlaLogger

/** A running audio pipeline as [AudioController] sees it (real: AudioHandler; tests: fakes). */
interface ManagedAudio {
    val tcpListener: HumlaTCPMessageListener
    val udpListener: HumlaUDPMessageListener
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
 * Builds the real [AudioHandler]. Stream B extends the builder chain here (spec 4).
 *
 * Only [AudioConfig.legacyEchoCancellationMethod] reaches the legacy
 * `Builder.setEchoCancellationMethod`; [AudioConfig.echoCancellationMode] is the spec 4 value and
 * belongs to the new pipeline. DefaultAudioHandlerFactoryTest pins which of the two arrives, by
 * way of the AudioManager mode the legacy "system" method sets.
 */
class DefaultAudioHandlerFactory : AudioHandlerFactory {
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
     * The four per-session arguments, separated for the same reason as [builder]: on the far side
     * of this call there is a microphone. `self` carries the session id that every voice packet is
     * stamped with, so a wrong one is not a degraded pipeline but somebody else's audio.
     */
    @Throws(AudioException::class)
    internal fun initialize(builder: AudioHandler.Builder, params: AudioSessionParams): AudioHandler =
        builder.initialize(params.self, params.maxBandwidth, params.codec, params.targetId)

    /**
     * The config-to-builder mapping, split off from `initialize` so that a JVM test can read it
     * back. `initialize` opens a microphone and starts the capture and playback threads, so nothing
     * behind it is reachable without a device - and while these fifteen calls sat on the far side of
     * it, fourteen of them were unpinned: swapping `targetBitrate` for `inputSampleRate`, dropping
     * `setAudioStream` or `setInputMode`, and reading `halfDuplexRequested` instead of `halfDuplex`
     * each left the whole suite green (measured).
     *
     * Stream B extends the chain here.
     */
    internal fun builder(
        context: Context,
        logger: HumlaLogger,
        config: AudioConfig,
        params: AudioSessionParams,
        encodeListener: AudioHandler.AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener,
    ): AudioHandler.Builder =
        AudioHandler.Builder()
            .setContext(context)
            .setLogger(logger)
            .setAudioStream(config.audioStream)
            .setAudioSource(config.audioSource)
            .setInputSampleRate(config.inputSampleRate)
            .setTargetBitrate(config.targetBitrate)
            .setTargetFramesPerPacket(config.targetFramesPerPacket)
            .setAmplitudeBoost(config.amplitudeBoost)
            .setBluetoothEnabled(config.bluetoothActive)
            .setHalfDuplexEnabled(config.halfDuplex)
            .setPreprocessorEnabled(config.preprocessorEnabled)
            .setEchoCancellationMethod(config.legacyEchoCancellationMethod)
            .setInputMode(params.inputMode)
            .setEncodeListener(encodeListener)
            .setTalkingListener(outputListener)
            .setNoiseSuppressionMethod(config.noiseSuppression)
}

/** Dresses an [AudioHandler] as a [ManagedAudio]; every member but the warning channel delegates. */
class AudioHandlerAdapter(private val handler: AudioHandler) : ManagedAudio {
    @Volatile private var warningListener: ((String) -> Unit)? = null

    override val tcpListener: HumlaTCPMessageListener get() = handler
    override val udpListener: HumlaUDPMessageListener get() = handler
    override val currentBandwidth: Int get() = handler.currentBandwidth
    override fun setVoiceTargetId(id: Byte) = handler.setVoiceTargetId(id)

    override fun setWarningListener(listener: ((String) -> Unit)?) {
        warningListener = listener
    }

    /**
     * Reports a user-facing audio problem. Everything downstream belongs to stream A and is in
     * place: [AudioController] posts it to the main thread and `HumlaService` turns it into a
     * chat-log warning (spec A8).
     *
     * **Stream B hook** (<= 6 lines): collect `handler.captureState` (spec 4) and call this for
     * `CaptureState.Silenced` and `CaptureState.Error`. Nothing calls it yet, so today this channel
     * is pinned by AudioHandlerAdapterTest and by nothing in production; decoder-creation failures
     * and UDP failures reach the log through [AudioController.Listener.onAudioFailed] and
     * `ConnectionWarning` instead.
     */
    fun reportWarning(message: String) {
        warningListener?.invoke(message)
    }

    override fun shutdown() = handler.shutdown()
}
