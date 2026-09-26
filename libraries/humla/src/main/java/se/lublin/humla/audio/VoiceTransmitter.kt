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

import android.util.Log
import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.encoder.IEncoder
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.net.UdpAudioEncoder
import se.lublin.humla.net.UdpProtocol

/**
 * Turns captured frames into voice packets: runs [pipeline] on every frame, tracks the talking
 * state, encodes while talking and hands each packet to [listener]. Every frame is processed and
 * none is waited on, also while nobody talks: the recorder keeps being drained and the
 * preprocessors stay warm, so the first packet after a push-to-talk press is fresh audio.
 *
 * [onAudioInputReceived] runs on the capture thread and allocates nothing per frame; [setCodec],
 * [releaseEncoder], [muteState] and [targetId] may be used from other threads.
 */
internal class VoiceTransmitter(
    private val pipeline: CapturePipeline,
    private val listener: AudioHandler.AudioEncodeListener,
    /** Called on the capture thread when the talking state flips, after [listener] heard of it. */
    private val onTalkingChanged: (Boolean) -> Unit = {},
) : AudioInput.AudioInputListener {

    @Volatile var muteState = SelfMuteState(serverMuted = false, selfMuted = false, suppressed = false)

    /** Stamped on every packet. */
    @Volatile var targetId: Byte = 0

    /** The wire format of the packets. */
    @Volatile var udpProtocol = UdpProtocol.LEGACY

    /** Written under [encoderLock], read without it. */
    @Volatile var codec: HumlaUDPMessageType? = null
        private set

    private val encoderLock = Any()
    private var encoder: IEncoder? = null
    private var frameCounter = 0

    /** The last observed talking state. False if muted, or the input mode is not active. */
    private var talking = false

    /** One packet, reused: [AudioHandler.AudioEncodeListener.onAudioEncoded] may not keep it. */
    private val packetBytes = ByteArray(PACKET_SIZE)
    private val packet = PacketBuffer(packetBytes, PACKET_SIZE)

    /**
     * Replaces the codec and destroys the old encoder, under the lock encoding holds.
     *
     * @param create builds the encoder for a non-null codec, or returns null if it has none.
     * @throws NativeAudioException from [create]; input stays off until the next [setCodec].
     */
    fun setCodec(codec: HumlaUDPMessageType?, create: (HumlaUDPMessageType) -> IEncoder?) {
        synchronized(encoderLock) {
            this.codec = codec
            encoder?.destroy()
            encoder = null
            if (codec != null) encoder = create(codec) else Log.w(TAG, "No codec, input disabled.")
        }
    }

    /** Like [setCodec], but a no-op when [codec] is already set. @return whether it changed. */
    fun setCodecIfChanged(codec: HumlaUDPMessageType?, create: (HumlaUDPMessageType) -> IEncoder?): Boolean {
        synchronized(encoderLock) {
            if (codec == this.codec) return false
            setCodec(codec, create)
            return true
        }
    }

    fun releaseEncoder() {
        synchronized(encoderLock) {
            encoder?.destroy()
            encoder = null
        }
    }

    override fun onAudioInputReceived(frame: ShortArray, frameSize: Int) {
        // Resample, preprocess every frame, then detect, then boost. The result and its samples
        // belong to the pipeline and are valid only until the next call.
        val processed = pipeline.process(frame, frameSize)
        val nowTalking = processed.transmit && !muteState.isMuted

        if (talking != nowTalking) {
            listener.onTalkingStateChanged(nowTalking)
            onTalkingChanged(nowTalking)
        }

        synchronized(encoderLock) {
            val encoder = encoder ?: return@synchronized
            try {
                if (nowTalking) {
                    // Already boosted by the pipeline; length is the produced frame's, not the array's.
                    encoder.encode(processed.samples, processed.length)
                    frameCounter++
                } else if (talking) {
                    encoder.terminate()
                }
            } catch (e: NativeAudioException) {
                Log.e(TAG, "Encoding failed", e)
            }
            if (encoder.isReady) send(encoder)
        }

        talking = nowTalking
    }

    /** Sends the buffered audio of [encoder] to [listener]. Called under [encoderLock]. */
    private fun send(encoder: IEncoder) {
        val protocol = udpProtocol
        val terminator = encoder.isTerminator
        val frameNumber = (frameCounter - encoder.bufferedFrames).toLong()

        packet.reset(PACKET_SIZE)
        UdpAudioEncoder.writeHeader(protocol, packet, targetId.toInt(), frameNumber, encoder.encodedLength, terminator)
        encoder.getEncodedData(packet)
        UdpAudioEncoder.writeTrailer(protocol, packet, terminator)
        listener.onAudioEncoded(packetBytes, packet.size())
    }

    private companion object {
        const val TAG = "VoiceTransmitter"
        const val PACKET_SIZE = 1024
    }
}
