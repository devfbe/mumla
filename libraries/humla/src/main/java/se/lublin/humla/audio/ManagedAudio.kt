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
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.model.UserState
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.TcpMessageHandler
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.net.VoicePacketHandler
import se.lublin.humla.util.HumlaLogger

/** A running audio pipeline as [AudioController] sees it (real: AudioHandler; tests: fakes). */
internal interface ManagedAudio {
    val tcpHandler: TcpMessageHandler
    val voiceHandler: VoicePacketHandler
    val currentBandwidth: Int
    fun setVoiceTargetId(id: Byte)

    /**
     * Stops capture and playback. May block for as long as the capture thread takes to notice, so
     * it is only ever called on [AudioController.THREAD_NAME].
     */
    fun shutdown()
}

/** Per-session inputs that only exist after ServerSync. */
internal data class AudioSessionParams(
    val self: UserState,
    val maxBandwidth: Int,
    val codec: HumlaUDPMessageType?,
    val targetId: Byte,
    val inputMode: IInputMode,
    /** The connection's voice packet format. */
    val udpProtocol: UdpProtocol = UdpProtocol.LEGACY,
)

/** What every pipeline of a session is built with, across its rebuilds. */
internal class AudioHost(
    val context: Context,
    val logger: HumlaLogger,
    val encodeListener: AudioHandler.AudioEncodeListener,
    val outputListener: AudioOutput.AudioOutputListener,
)

internal interface AudioHandlerFactory {
    fun create(
        host: AudioHost,
        config: AudioConfig,
        params: AudioSessionParams,
    ): ManagedAudio
}

/** Builds and starts the real [AudioHandler]. */
internal object DefaultAudioHandlerFactory : AudioHandlerFactory {
    override fun create(host: AudioHost, config: AudioConfig, params: AudioSessionParams): ManagedAudio =
        AudioHandlerAdapter(AudioHandler(host, config, params).apply { start() })
}

/** Dresses an [AudioHandler] as a [ManagedAudio]. */
internal class AudioHandlerAdapter(private val handler: AudioHandler) : ManagedAudio {
    override val tcpHandler: TcpMessageHandler get() = handler
    override val voiceHandler: VoicePacketHandler get() = handler
    override val currentBandwidth: Int get() = handler.currentBandwidth
    override fun setVoiceTargetId(id: Byte) = handler.setVoiceTargetId(id)

    override fun shutdown() = handler.shutdown()
}
