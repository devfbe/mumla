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

package se.lublin.humla.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.lang.management.ManagementFactory
import org.junit.Test
import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.capture.NoopPreprocessor
import se.lublin.humla.audio.encoder.IEncoder
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer

class VoiceTransmitterTest {
    /** One frame per packet; the payload is the frame's first sample. Allocation-free. */
    private class FirstSampleEncoder : IEncoder {
        private var sample: Short = 0
        override var bufferedFrames = 0
            private set
        override val isReady: Boolean get() = bufferedFrames > 0

        override fun encode(input: ShortArray, inputSize: Int): Int {
            sample = input[0]
            bufferedFrames = 1
            return 1
        }

        override fun getEncodedData(packetBuffer: PacketBuffer) {
            packetBuffer.writeLong(2)
            packetBuffer.append(sample.toLong() shr 8)
            packetBuffer.append(sample.toLong())
            bufferedFrames = 0
        }

        override fun terminate() = Unit
        override fun destroy() = Unit
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
        VoiceTransmitter(CapturePipeline(null, NoopPreprocessor, mode), mode, listener).apply {
            setCodec(HumlaUDPMessageType.UDPVoiceOpus) { FirstSampleEncoder() }
        }

    private fun frame(first: Int) = ShortArray(FRAME) { if (it == 0) first.toShort() else 0 }

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
    fun `every packet is handed over in the same reused buffer`() {
        val listener = RecordingListener()
        val transmitter = transmitter(listener)

        repeat(3) { transmitter.onAudioInputReceived(frame(it), FRAME) }

        assertThat(listener.buffers).hasSize(3)
        assertThat(listener.buffers.distinct()).hasSize(1)
    }

    @Test
    fun `encoding and sending a frame allocates under half an object`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()
        val silent = object : AudioHandler.AudioEncodeListener {
            var bytes = 0
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                bytes += length
            }
            override fun onTalkingStateChanged(talking: Boolean) = Unit
        }
        val transmitter = transmitter(silent)
        val input = frame(7)
        val self = Thread.currentThread().threadId()
        val bytesPerFrame = { warmups: Int, iterations: Int ->
            repeat(warmups) { transmitter.onAudioInputReceived(input, FRAME) }
            val before = threads.getThreadAllocatedBytes(self)
            repeat(iterations) { transmitter.onAudioInputReceived(input, FRAME) }
            (threads.getThreadAllocatedBytes(self) - before).toDouble() / iterations
        }

        val cold = bytesPerFrame(1_000, 8_000)
        val hot = bytesPerFrame(100_000, 100_000)

        println(
            "allocation per 10 ms frame (cold / hot): " +
                "VoiceTransmitter ${"%.3f".format(cold)} / ${"%.3f".format(hot)} B",
        )

        assertWithMessage("the send path allocates on the capture thread").that(maxOf(cold, hot)).isLessThan(8.0)
        assertThat(silent.bytes).isGreaterThan(0)
    }

    private companion object {
        const val FRAME = 480
    }
}
