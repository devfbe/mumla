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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.audio.capture.NoopPreprocessor
import se.lublin.humla.audio.encoder.IEncoder
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.net.UdpProtocol
import se.lublin.humla.testutil.AllocationMeter.HALF_AN_OBJECT
import se.lublin.humla.testutil.AllocationMeter.worstPerCall

class VoiceTransmitterTest {
    /** One frame per packet; the payload is the frame's first sample. Allocation-free. */
    private class FirstSampleEncoder : IEncoder {
        private var sample: Short = 0
        override var bufferedFrames = 0
            private set
        override val isReady: Boolean get() = bufferedFrames > 0
        override val encodedLength = 2
        override val isTerminator = false

        override fun encode(input: ShortArray, inputSize: Int): Int {
            sample = input[0]
            bufferedFrames = 1
            return 1
        }

        override fun getEncodedData(packetBuffer: PacketBuffer) {
            packetBuffer.append(sample.toLong() shr 8)
            packetBuffer.append(sample.toLong())
            bufferedFrames = 0
        }

        override fun terminate() = Unit
        override fun close() = Unit
    }

    /**
     * The contract of the Opus encoder with [framesPerPacket] frames per packet: a packet is ready
     * once full, [terminate] pads a partly filled packet with silence and flags it, and an empty
     * buffer at [terminate] yields no packet. Each frame is encoded as its first sample, two bytes.
     */
    private class BufferingEncoder(private val framesPerPacket: Int) : IEncoder {
        private val samples = ShortArray(framesPerPacket)
        private var ready = false
        private var terminated = false
        override var bufferedFrames = 0
            private set
        override val isReady: Boolean get() = ready
        override val encodedLength: Int get() = if (ready) 2 * framesPerPacket else 0
        override val isTerminator: Boolean get() = terminated

        override fun encode(input: ShortArray, inputSize: Int): Int {
            check(!ready) { "a ready packet was not taken" }
            terminated = false
            samples[bufferedFrames++] = input[0]
            ready = bufferedFrames == framesPerPacket
            return encodedLength
        }

        override fun getEncodedData(packetBuffer: PacketBuffer) {
            check(ready)
            for (sample in samples) {
                packetBuffer.append(sample.toLong() shr 8)
                packetBuffer.append(sample.toLong())
            }
            bufferedFrames = 0
            ready = false
            terminated = false
        }

        override fun terminate() {
            terminated = true
            if (bufferedFrames > 0 && !ready) {
                samples.fill(0, bufferedFrames)
                bufferedFrames = framesPerPacket
                ready = true
            }
        }

        override fun close() = Unit
    }

    /** Copies what it is handed, as the network layer must, since the buffer is reused. */
    private class RecordingListener : AudioHandler.AudioEncodeListener {
        val packets = mutableListOf<ByteArray>()
        val buffers = mutableListOf<ByteArray>()
        val talking = mutableListOf<Boolean>()
        override fun onAudioEncoded(data: ByteArray, length: Int) {
            packets += data.copyOf(length)
            buffers += data
        }
        override fun onTalkingStateChanged(talking: Boolean) {
            this.talking += talking
        }
    }

    private fun transmitter(listener: AudioHandler.AudioEncodeListener, mode: IInputMode = ContinuousInputMode()) =
        VoiceTransmitter(CapturePipeline(null, NoopPreprocessor, mode), listener).apply {
            setCodec(HumlaUDPMessageType.UDPVoiceOpus) { FirstSampleEncoder() }
        }

    private fun frame(first: Int) = ShortArray(FRAME) { if (it == 0) first.toShort() else 0 }

    /** A transmitter whose encoder puts two frames into a packet, as the default Opus setup does. */
    private fun bufferingTransmitter(
        listener: AudioHandler.AudioEncodeListener,
        mode: IInputMode,
        protocol: UdpProtocol,
    ) =
        VoiceTransmitter(CapturePipeline(null, NoopPreprocessor, mode), listener).apply {
            udpProtocol = protocol
            setCodec(HumlaUDPMessageType.UDPVoiceOpus) { BufferingEncoder(framesPerPacket = 2) }
        }

    private fun bytes(vararg values: Int) = values.map { it.toByte() }

    /**
     * Releasing a whisper hold turns talking off and then resets the target; the reset can reach
     * the transmitter before the capture thread notices the release. The buffered last frame and
     * the terminator still belong to the whisper, not to the channel.
     */
    @Test
    fun `the terminator of a released whisper keeps the whisper target`() {
        // LEGACY: type and target; frame number; size 4, with 0x2000 as the terminator flag.
        // PROTOBUF: header 0; target (1); frame_number (4); opus_data (5); is_terminator (16).
        val expected = mapOf(
            UdpProtocol.LEGACY to listOf(
                bytes(OPUS or 5, 0, 4, 1, 1, 2, 2),
                bytes(OPUS or 5, 2, 0xA0, 0x04, 3, 3, 0, 0),
            ),
            UdpProtocol.PROTOBUF to listOf(
                bytes(0, 0x08, 5, 0x2A, 4, 1, 1, 2, 2),
                bytes(0, 0x08, 5, 0x20, 2, 0x2A, 4, 3, 3, 0, 0, 0x80, 0x01, 0x01),
            ),
        )
        for ((protocol, packets) in expected) {
            val listener = RecordingListener()
            val ptt = ToggleInputMode()
            val transmitter = bufferingTransmitter(listener, ptt, protocol)
            transmitter.targetId = 5
            ptt.setTalkingOn(true)
            for (first in listOf(0x0101, 0x0202, 0x0303)) transmitter.onAudioInputReceived(frame(first), FRAME)

            ptt.setTalkingOn(false)
            transmitter.targetId = 0
            transmitter.onAudioInputReceived(frame(0x0404), FRAME)

            assertWithMessage("$protocol").that(listener.packets.map { it.toList() })
                .containsExactlyElementsIn(packets).inOrder()
            assertThat(listener.talking).containsExactly(true, false).inOrder()
        }
    }

    /**
     * A new target while talking ends the stream to the old one with a terminator on the old target,
     * and the frames after the change start a new stream to the new target.
     */
    @Test
    fun `switching the target while talking terminates the old stream on the old target`() {
        val expected = mapOf(
            UdpProtocol.LEGACY to listOf(
                bytes(OPUS or 5, 0, 4, 1, 1, 2, 2),
                bytes(OPUS or 5, 2, 0xA0, 0x04, 3, 3, 0, 0),
                bytes(OPUS, 4, 4, 4, 4, 5, 5),
            ),
            UdpProtocol.PROTOBUF to listOf(
                bytes(0, 0x08, 5, 0x2A, 4, 1, 1, 2, 2),
                bytes(0, 0x08, 5, 0x20, 2, 0x2A, 4, 3, 3, 0, 0, 0x80, 0x01, 0x01),
                bytes(0, 0x08, 0, 0x20, 4, 0x2A, 4, 4, 4, 5, 5),
            ),
        )
        for ((protocol, packets) in expected) {
            val listener = RecordingListener()
            val transmitter = bufferingTransmitter(listener, ContinuousInputMode(), protocol)
            transmitter.targetId = 5
            for (first in listOf(0x0101, 0x0202, 0x0303)) transmitter.onAudioInputReceived(frame(first), FRAME)

            transmitter.targetId = 0
            for (first in listOf(0x0404, 0x0505)) transmitter.onAudioInputReceived(frame(first), FRAME)

            assertWithMessage("$protocol").that(listener.packets.map { it.toList() })
                .containsExactlyElementsIn(packets).inOrder()
            assertThat(listener.talking).containsExactly(true)
        }
    }

    /**
     * A release right after a full packet leaves nothing buffered; the transmission still ends with
     * a terminator, a packet of silence numbered after the last one, and the audio of the frame that
     * noticed the release is not sent.
     */
    @Test
    fun `a release on a packet boundary still sends a terminator on the stream's target`() {
        val expected = mapOf(
            UdpProtocol.LEGACY to listOf(
                bytes(OPUS or 5, 0, 4, 1, 1, 2, 2),
                bytes(OPUS or 5, 2, 0xA0, 0x04, 0, 0, 0, 0),
            ),
            UdpProtocol.PROTOBUF to listOf(
                bytes(0, 0x08, 5, 0x2A, 4, 1, 1, 2, 2),
                bytes(0, 0x08, 5, 0x20, 2, 0x2A, 4, 0, 0, 0, 0, 0x80, 0x01, 0x01),
            ),
        )
        for ((protocol, packets) in expected) {
            val listener = RecordingListener()
            val ptt = ToggleInputMode()
            val transmitter = bufferingTransmitter(listener, ptt, protocol)
            transmitter.targetId = 5
            ptt.setTalkingOn(true)
            for (first in listOf(0x0101, 0x0202)) transmitter.onAudioInputReceived(frame(first), FRAME)

            ptt.setTalkingOn(false)
            transmitter.targetId = 0
            transmitter.onAudioInputReceived(frame(0x0909), FRAME)

            assertWithMessage("$protocol").that(listener.packets.map { it.toList() })
                .containsExactlyElementsIn(packets).inOrder()
        }
    }

    @Test
    fun `a packet carries codec and target, the sequence number and the encoded frame`() {
        val listener = RecordingListener()
        val transmitter = transmitter(listener)
        transmitter.targetId = 3

        transmitter.onAudioInputReceived(frame(0x0102), FRAME)
        transmitter.onAudioInputReceived(frame(0x0304), FRAME)

        val opus = HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5
        assertThat(listener.packets.map { it.toList() }).containsExactly(
            listOf((opus or 3).toByte(), 0, 2, 1, 2),
            listOf((opus or 3).toByte(), 1, 2, 3, 4),
        ).inOrder()
        assertThat(listener.talking).containsExactly(true)
    }

    @Test
    fun `in the protobuf format a packet is an Audio message with target, frame number and opus data`() {
        val listener = RecordingListener()
        val transmitter = transmitter(listener)
        transmitter.targetId = 3
        transmitter.udpProtocol = UdpProtocol.PROTOBUF

        transmitter.onAudioInputReceived(frame(0x0102), FRAME)
        transmitter.onAudioInputReceived(frame(0x0304), FRAME)

        // Header 0; target (1) 3; frame_number (4) left out while 0; opus_data (5), 2 bytes.
        assertThat(listener.packets.map { it.toList() }).containsExactly(
            listOf<Byte>(0, 0x08, 3, 0x2A, 2, 1, 2),
            listOf<Byte>(0, 0x08, 3, 0x20, 1, 0x2A, 2, 3, 4),
        ).inOrder()
    }

    @Test
    fun `every packet is handed over in the same reused buffer`() {
        val listener = RecordingListener()
        val transmitter = transmitter(listener)

        repeat(3) { transmitter.onAudioInputReceived(frame(it), FRAME) }

        assertThat(listener.buffers).hasSize(3)
        assertThat(listener.buffers.distinct()).hasSize(1)
    }

    /**
     * With push-to-talk released every frame still comes through and returns at once, so the
     * capture loop keeps draining the recorder; at key press the first packet is the frame captured
     * after it, not audio that queued up while the key was released.
     */
    @Test
    fun `push-to-talk keeps draining while released and sends only fresh audio at key press`() {
        val listener = RecordingListener()
        val ptt = ToggleInputMode()
        val transmitter = transmitter(listener, ptt)

        val released = Thread {
            repeat(50) { transmitter.onAudioInputReceived(frame(0x0101), FRAME) }
        }
        released.start()
        released.join(5_000)
        assertWithMessage("the capture thread must not park while the key is released")
            .that(released.isAlive).isFalse()
        assertThat(listener.packets).isEmpty()

        ptt.setTalkingOn(true)
        transmitter.onAudioInputReceived(frame(0x0202), FRAME)

        assertThat(listener.packets.map { it.toList().takeLast(2) }).containsExactly(listOf<Byte>(2, 2))
        assertThat(listener.talking).containsExactly(true)
    }

    @Test
    fun `encoding and sending a frame allocates under half an object`() {
        for (protocol in UdpProtocol.entries) assertSendPathDoesNotAllocate(protocol)
    }

    private fun assertSendPathDoesNotAllocate(protocol: UdpProtocol) {
        val silent = object : AudioHandler.AudioEncodeListener {
            var bytes = 0
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                bytes += length
            }
            override fun onTalkingStateChanged(talking: Boolean) = Unit
        }
        val transmitter = transmitter(silent)
        transmitter.udpProtocol = protocol
        val input = frame(7)

        val perFrame = worstPerCall("VoiceTransmitter, $protocol", HOT_CALLS) {
            transmitter.onAudioInputReceived(input, FRAME)
        }
        assertWithMessage("the send path allocates on the capture thread").that(perFrame).isLessThan(HALF_AN_OBJECT)
        assertThat(silent.bytes).isGreaterThan(0)
    }

    private companion object {
        const val FRAME = 480
        const val HOT_CALLS = 100_000
        val OPUS = HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5
    }
}
