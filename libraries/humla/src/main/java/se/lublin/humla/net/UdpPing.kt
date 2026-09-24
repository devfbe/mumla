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

import se.lublin.humla.protobuf.MumbleUDP
import java.nio.BufferUnderflowException

/**
 * The UDP connectivity ping, in the connection's [UdpProtocol].
 *
 * Legacy: one header byte and the timestamp as a Mumble varint, exactly as long as that varint is
 * (mumble `UDPPingEncoder::encodePingPacket_legacy`). The exact length matters: a 1.5 server
 * decodes a legacy client's ping with `decodePing_legacy`, which accepts at most nine varint bytes
 * behind the header (or the 12-byte extended-information request) and silently drops anything
 * else. Older servers echo the datagram unchanged, so one reader serves both.
 *
 * Protobuf: the header byte 1 and a `MumbleUDP.Ping` with the timestamp. A 1.5 server drops a
 * legacy ping from a client that announced 1.5, so the format must match the voice format.
 */
internal object UdpPing {
    private val HEADER = ((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte()

    /** Header plus the longest varint (0xF4 and eight bytes). */
    const val MAX_SIZE = 1 + 9

    fun encode(protocol: UdpProtocol, timestampMicros: Long): ByteArray = when (protocol) {
        UdpProtocol.LEGACY -> encodeLegacy(timestampMicros)
        UdpProtocol.PROTOBUF ->
            byteArrayOf(UdpAudioEncoder.PROTOBUF_PING.toByte()) +
                MumbleUDP.Ping.newBuilder().setTimestamp(timestampMicros).build().toByteArray()
    }

    private fun encodeLegacy(timestampMicros: Long): ByteArray {
        val pb = PacketBuffer.allocate(MAX_SIZE)
        pb.append(HEADER.toLong())
        pb.writeLong(timestampMicros)
        val length = pb.size()
        pb.rewind()
        return pb.dataBlock(length)
    }

    /**
     * The timestamp a ping reply carries, or null for a datagram too short or malformed to carry
     * one. [data] starts with the header byte the caller has already read its type from, so it is
     * never empty; everything after it comes from the network and is not trusted.
     */
    fun decodeTimestamp(protocol: UdpProtocol, data: ByteArray): Long? {
        val timestamp = when (protocol) {
            UdpProtocol.LEGACY -> decodeLegacy(data)
            UdpProtocol.PROTOBUF -> UdpPacketDecoder().decodePingTimestamp(data, 1, data.size - 1)
        }
        return timestamp?.takeIf { it >= 0 }
    }

    /** A header with nothing behind it runs into the same underflow as a truncated varint. */
    private fun decodeLegacy(data: ByteArray): Long? {
        val pb = PacketBuffer(data, data.size)
        pb.skip(1)
        return try {
            pb.readLong()
        } catch (e: BufferUnderflowException) {
            null
        }
    }
}
