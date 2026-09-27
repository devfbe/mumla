/*
 * Copyright (C) 2026 The Mumla authors
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
package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.protobuf.ByteString
import org.junit.Test
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.testutil.AllocationMeter
import se.lublin.humla.testutil.bytes
import kotlin.random.Random

/** The hand-written UDP audio codec against the generated MumbleUDP classes and the legacy format. */
class UdpAudioCodecTest {
    private val random = Random(1234)

    private fun clientPacket(
        protocol: UdpProtocol,
        target: Int,
        frameNumber: Long,
        opus: ByteArray,
        terminator: Boolean,
    ): ByteArray {
        val buffer = ByteArray(2048)
        val packet = PacketBuffer(buffer, buffer.size)
        UdpAudioEncoder.writeHeader(protocol, packet, target, frameNumber, opus.size, terminator)
        packet.append(opus, opus.size)
        UdpAudioEncoder.writeTrailer(protocol, packet, terminator)
        return buffer.copyOf(packet.size())
    }

    private fun randomFrameNumber() = when (random.nextInt(4)) {
        0 -> 0L
        1 -> random.nextLong(1, 128)
        2 -> random.nextLong(0, 1L shl 40)
        else -> random.nextLong(Long.MAX_VALUE)
    }

    @Test
    fun `protobuf client packets are the header byte and exactly what the generated class serializes`() {
        repeat(2_000) {
            val target = if (random.nextInt(8) == 0) random.nextInt(32, Int.MAX_VALUE) else random.nextInt(32)
            val frameNumber = randomFrameNumber()
            val opus = random.nextBytes(random.nextInt(1, 1_000))
            val terminator = random.nextBoolean()

            val expected = MumbleUDP.Audio.newBuilder()
                .setTarget(target)
                .setFrameNumber(frameNumber)
                .setOpusData(ByteString.copyFrom(opus))
                .setIsTerminator(terminator)
                .build()

            val packet = clientPacket(UdpProtocol.PROTOBUF, target, frameNumber, opus, terminator)
            assertThat(packet[0]).isEqualTo(0.toByte())
            assertThat(packet.copyOfRange(1, packet.size)).isEqualTo(expected.toByteArray())
        }
    }

    @Test
    fun `a protobuf client packet for target 0 and frame 0 still names the target`() {
        val packet = clientPacket(UdpProtocol.PROTOBUF, 0, 0, bytes(0x42), terminator = false)

        assertThat(packet).isEqualTo(bytes(0x00, 0x08, 0x00, 0x2A, 0x01, 0x42))
    }

    @Test
    fun `legacy client packets keep the Opus type, target bits, sequence and 13-bit size with the terminator bit`() {
        val packet = clientPacket(UdpProtocol.LEGACY, 3, 5, bytes(0x11, 0x22, 0x33), terminator = true)

        // 0x83: Opus (4) << 5 | target 3; sequence 5; 3 | 1 << 13 = 0x2003, two-byte varint 0xA0 0x03
        assertThat(packet).isEqualTo(bytes(0x83, 0x05, 0xA0, 0x03, 0x11, 0x22, 0x33))
    }

    private fun serverAudio(fill: MumbleUDP.Audio.Builder.() -> Unit): MumbleUDP.Audio =
        MumbleUDP.Audio.newBuilder().apply(fill).build()

    @Test
    fun `the decoder reads every field the generated class writes`() {
        val decoder = UdpPacketDecoder()
        val out = VoicePacket()
        repeat(2_000) {
            val context = if (random.nextInt(5) == 0) null else random.nextInt(4)
            val session = random.nextInt(0, Int.MAX_VALUE)
            val frameNumber = randomFrameNumber()
            val opus = random.nextBytes(random.nextInt(1, 1_000))
            val terminator = random.nextBoolean()
            val volume = if (random.nextBoolean()) 0f else random.nextFloat() * 4f + 0.01f
            val positional = random.nextBoolean()
            val message = serverAudio {
                if (context != null) setContext(context)
                senderSession = session
                this.frameNumber = frameNumber
                opusData = ByteString.copyFrom(opus)
                isTerminator = terminator
                volumeAdjustment = volume
                if (positional) addAllPositionalData(listOf(1f, -2f, 3.5f))
            }
            val wire = bytes(0) + message.toByteArray()

            assertThat(decoder.decodeProtobuf(wire, 1, wire.size - 1, out)).isTrue()

            assertThat(out.codec).isEqualTo(HumlaUDPMessageType.UDPVoiceOpus)
            assertThat(out.context).isEqualTo(context ?: 0)
            assertThat(out.session).isEqualTo(session)
            assertThat(out.frameNumber).isEqualTo(frameNumber)
            assertThat(out.data.copyOfRange(out.opusOffset, out.opusOffset + out.opusLength)).isEqualTo(opus)
            assertThat(out.isTerminator).isEqualTo(terminator)
            assertThat(out.volumeAdjustment).isEqualTo(if (volume == 0f) 1f else volume)
        }
    }

    @Test
    fun `unknown fields of every wire type are skipped`() {
        val known = serverAudio {
            context = 2
            senderSession = 9
            frameNumber = 4
            opusData = ByteString.copyFrom(bytes(0x55, 0x66))
        }.toByteArray()
        val unknown = bytes(
            0xA0, 0x01, 0xFF, 0x01, // field 20, varint
            0xA9, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, // field 21, fixed64
            0xB2, 0x01, 0x02, 0x00, 0x00, // field 22, length-delimited
            0xBD, 0x01, 1, 2, 3, 4, // field 23, fixed32
            0x35, 0, 0, 0x80, 0x3F, // positional_data (6) unpacked
        )
        val wire = unknown + known
        val out = VoicePacket()

        assertThat(UdpPacketDecoder().decodeProtobuf(wire, 0, wire.size, out)).isTrue()

        assertThat(out.context).isEqualTo(2)
        assertThat(out.session).isEqualTo(9)
        assertThat(out.frameNumber).isEqualTo(4)
        assertThat(out.data.copyOfRange(out.opusOffset, out.opusOffset + out.opusLength)).isEqualTo(bytes(0x55, 0x66))
    }

    @Test
    fun `a message without audio, with a group, an overlong varint or a truncated field is refused`() {
        val decoder = UdpPacketDecoder()
        val out = VoicePacket()
        fun decodes(vararg wire: Int) = bytes(*wire).let { decoder.decodeProtobuf(it, 0, it.size, out) }

        assertThat(decodes(0x10, 0x01, 0x18, 0x02)).isFalse() // no opus_data
        assertThat(decodes(0x2A, 0x00)).isFalse() // empty opus_data
        assertThat(decodes(0x2A, 0x01, 0x42, 0x0B)).isFalse() // start group, not proto3
        assertThat(decodes(0x18, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x2A, 0x01, 0x42))
            .isFalse()
        assertThat(decodes(0x2A, 0x01, 0x42)).isTrue()

        val valid = serverAudio {
            context = 1
            senderSession = 3
            frameNumber = 77
            opusData = ByteString.copyFrom(bytes(1, 2, 3, 4))
            isTerminator = true
            volumeAdjustment = 0.5f
            addAllPositionalData(listOf(1f, 2f, 3f))
        }.toByteArray()
        for (length in 0 until valid.size) {
            // Truncated anywhere, the opus data either is complete or the packet is refused.
            if (decoder.decodeProtobuf(valid, 0, length, out)) {
                assertThat(out.opusOffset + out.opusLength).isAtMost(length)
            }
        }
    }

    private fun legacyServerPacket(type: HumlaUDPMessageType, context: Int, session: Long, seq: Long, body: ByteArray) =
        PacketBuffer.allocate(64).run {
            append(((type.ordinal shl 5) or context).toLong())
            writeLong(session)
            writeLong(seq)
            append(body, body.size)
            val length = size()
            rewind()
            dataBlock(length)
        }

    @Test
    fun `a legacy packet yields context, session, sequence, opus data and terminator, ignoring positional data`() {
        val body = bytes(0xA0, 0x03, 0x11, 0x22, 0x33) + ByteArray(12) // 3 | terminator, opus, x/y/z
        val wire = legacyServerPacket(HumlaUDPMessageType.UDPVoiceOpus, 2, 300, 70_000, body)
        val out = VoicePacket()

        assertThat(UdpPacketDecoder().decodeLegacy(wire, wire.size, out)).isTrue()

        assertThat(out.codec).isEqualTo(HumlaUDPMessageType.UDPVoiceOpus)
        assertThat(out.context).isEqualTo(2)
        assertThat(out.session).isEqualTo(300)
        assertThat(out.frameNumber).isEqualTo(70_000)
        assertThat(out.isTerminator).isTrue()
        assertThat(out.data.copyOfRange(out.opusOffset, out.opusOffset + out.opusLength))
            .isEqualTo(bytes(0x11, 0x22, 0x33))
        assertThat(out.volumeAdjustment).isEqualTo(1f)
    }

    @Test
    fun `a legacy packet that is truncated, empty or a ping is refused, and other codecs pass without audio`() {
        val decoder = UdpPacketDecoder()
        val out = VoicePacket()
        val full = legacyServerPacket(HumlaUDPMessageType.UDPVoiceOpus, 0, 1, 1, bytes(0x03, 1, 2, 3))

        for (length in 1 until full.size) assertThat(decoder.decodeLegacy(full, length, out)).isFalse()
        val empty = legacyServerPacket(HumlaUDPMessageType.UDPVoiceOpus, 0, 1, 1, bytes(0x00))
        assertThat(decoder.decodeLegacy(empty, empty.size, out)).isFalse()
        val ping = legacyServerPacket(HumlaUDPMessageType.UDPPing, 0, 1, 1, bytes())
        assertThat(decoder.decodeLegacy(ping, ping.size, out)).isFalse()
        assertThat(decoder.decodeLegacy(bytes(0xE0, 1, 1), 3, out)).isFalse() // type 7

        val celt = legacyServerPacket(HumlaUDPMessageType.UDPVoiceCELTAlpha, 0, 5, 1, bytes(0x02, 1, 2))
        assertThat(decoder.decodeLegacy(celt, celt.size, out)).isTrue()
        assertThat(out.codec).isEqualTo(HumlaUDPMessageType.UDPVoiceCELTAlpha)
        assertThat(out.session).isEqualTo(5)
        assertThat(out.opusLength).isEqualTo(0)
    }

    @Test
    fun `encoding and decoding allocate nothing`() {
        val opus = random.nextBytes(120)
        val buffer = ByteArray(1024)
        val packet = PacketBuffer(buffer, buffer.size)
        val server = bytes(0) + serverAudio {
            context = 0
            senderSession = 4
            frameNumber = 12
            opusData = ByteString.copyFrom(opus)
            volumeAdjustment = 0.7f
            addAllPositionalData(listOf(1f, 2f, 3f))
        }.toByteArray()
        val legacy = legacyServerPacket(HumlaUDPMessageType.UDPVoiceOpus, 0, 4, 12, bytes(0x03, 1, 2, 3))
        val decoder = UdpPacketDecoder()
        val out = VoicePacket()
        val protocols = UdpProtocol.entries.toTypedArray()
        val parts = listOf(
            "encode" to { i: Int ->
                val protocol = protocols[i and 1]
                packet.reset(buffer.size)
                UdpAudioEncoder.writeHeader(protocol, packet, 0, i.toLong(), opus.size, terminator = false)
                packet.append(opus, opus.size)
                UdpAudioEncoder.writeTrailer(protocol, packet, terminator = false)
            },
            "protobuf decode" to { _: Int -> check(decoder.decodeProtobuf(server, 1, server.size - 1, out)) },
            "legacy decode" to { _: Int -> check(decoder.decodeLegacy(legacy, legacy.size, out)) },
        )

        val perCall = parts.associate { (name, part) ->
            var i = 0
            // Indices below 128 keep the boxed lambda argument in the Integer cache.
            name to AllocationMeter.bytesPerCall(50_000, 100_000) { part(i++ and 0x7F) }
        }

        println("ALLOC $perCall")
        assertWithMessage("bytes allocated per call: $perCall").that(perCall.values.max()).isLessThan(1.0)
    }
}
