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

import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.encoder.IEncoder
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.net.UdpAudioEncoder
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.util.HumlaLog

private const val TAG = "VoiceTransmitter"
private const val PACKET_SIZE = 1024

/**
 * Turns captured frames into voice packets: runs [pipeline] on every frame, tracks the talking
 * state, encodes while talking and hands each packet to [listener]. Every frame is processed and
 * none is waited on, also while nobody talks: the recorder keeps being drained and the
 * preprocessors stay warm, so the first packet after a push-to-talk press is fresh audio.
 *
 * A transmission goes to one target: [targetId] is latched when talking starts. A change while
 * talking ends the stream to the old target with a terminator there and starts a new stream to the
 * new one; the tail and terminator after a release stay on the stream's target, however soon the
 * target changes after the release. Every transmission ends with a terminator packet.
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

    /** The target of the next transmission, or of the current one from its next frame on. */
    @Volatile var targetId: Byte = 0

    /** The wire format of the packets. */
    @Volatile var udpProtocol = UdpProtocol.LEGACY

    /** Written under [encoderLock], read without it. */
    @Volatile var codec: HumlaUDPMessageType? = null
        private set

    private val encoderLock = Any()
    private var encoder: IEncoder? = null
    private var frameCounter = 0

    /** The number of the first frame in the encoder's packet. Capture thread, under [encoderLock]. */
    private var packetFrameNumber = 0

    /** The target the current transmission is stamped with. Capture thread, under [encoderLock]. */
    private var streamTarget: Byte = 0

    /** A frame of silence that ends a transmission when the encoder holds nothing; grown on demand. */
    private var silence = ShortArray(0)

    /** An encoder that keeps failing would otherwise log from the capture loop every frame. */
    private var encodeFailureLogged = false

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
            encoder?.close()
            encoder = null
            encodeFailureLogged = false
            if (codec != null) encoder = create(codec) else HumlaLog.w(TAG, "No codec, input disabled.")
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
            encoder?.close()
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
            val requested = targetId
            val encoder = encoder
            if (encoder == null) {
                if (nowTalking) streamTarget = requested
                return@synchronized
            }
            try {
                if (nowTalking) {
                    if (!talking) {
                        streamTarget = requested
                    } else if (requested != streamTarget) {
                        endStream(encoder, processed.length)
                        streamTarget = requested
                    }
                    // Already boosted by the pipeline; length is the produced frame's, not the array's.
                    encodeFrame(encoder, processed.samples, processed.length)
                } else if (talking) {
                    endStream(encoder, processed.length)
                }
            } catch (e: NativeAudioException) {
                if (!encodeFailureLogged) {
                    encodeFailureLogged = true
                    HumlaLog.e(TAG, "Encoding failed", e)
                }
            }
            if (encoder.isReady) send(encoder, streamTarget)
        }

        talking = nowTalking
    }

    /** Encodes one frame and counts it. Called under [encoderLock]. */
    private fun encodeFrame(encoder: IEncoder, samples: ShortArray, length: Int) {
        if (encoder.bufferedFrames == 0) packetFrameNumber = frameCounter
        encoder.encode(samples, length)
        frameCounter++
    }

    /**
     * Terminates the stream to [streamTarget] and sends its last packet there. With nothing buffered
     * a frame of silence, [frameLength] samples, carries the terminator: the frame that noticed the
     * end is not the speaker's to send. Called under [encoderLock].
     */
    private fun endStream(encoder: IEncoder, frameLength: Int) {
        if (encoder.bufferedFrames == 0 && !encoder.isReady) {
            if (silence.size < frameLength) silence = ShortArray(frameLength)
            encodeFrame(encoder, silence, frameLength)
        }
        encoder.terminate()
        if (encoder.isReady) send(encoder, streamTarget)
    }

    /** Sends the buffered audio of [encoder] to [listener], stamped with [target]. Under [encoderLock]. */
    private fun send(encoder: IEncoder, target: Byte) {
        val protocol = udpProtocol
        val terminator = encoder.isTerminator
        val frameNumber = packetFrameNumber
        // A packet the encoder padded with silence spans more frames than were captured; the next
        // packet is numbered after all of them.
        frameCounter = maxOf(frameCounter, frameNumber + encoder.bufferedFrames)

        packet.reset(PACKET_SIZE)
        UdpAudioEncoder.writeHeader(
            protocol, packet, target.toInt(), frameNumber.toLong(), encoder.encodedLength, terminator,
        )
        encoder.getEncodedData(packet)
        UdpAudioEncoder.writeTrailer(protocol, packet, terminator)
        listener.onAudioEncoded(packetBytes, packet.size())
    }
}
