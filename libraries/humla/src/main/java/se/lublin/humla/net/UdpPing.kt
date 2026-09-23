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

import java.nio.BufferUnderflowException

/**
 * The legacy UDP connectivity ping: one header byte and the timestamp as a Mumble varint, exactly
 * as long as that varint is (mumble `UDPPingEncoder::encodePingPacket_legacy`).
 *
 * Exact length is the whole point. Humla announces protocol 1.2.5, so a 1.5 server decodes the
 * ping with `decodePing_legacy`, which accepts at most nine bytes behind the header as a varint and
 * otherwise only the 12-byte extended-information request. The sixteen padded bytes this client
 * sent before were neither and were dropped without an answer; servers before 1.5 echoed them.
 *
 * A 1.5 server answers with the same shape, and an older one echoes the datagram unchanged, which
 * is also the same shape - so one reader serves both.
 */
internal object UdpPing {
    private val HEADER = ((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte()

    /** Header plus the longest varint (0xF4 and eight bytes). */
    const val MAX_SIZE = 1 + 9

    fun encode(timestampMicros: Long): ByteArray {
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
     * never empty; everything after it comes from the network and is not trusted - a header with
     * nothing behind it runs into the same underflow as a truncated varint.
     */
    fun decodeTimestamp(data: ByteArray): Long? {
        val pb = PacketBuffer(data, data.size)
        pb.skip(1)
        val timestamp = try {
            pb.readLong()
        } catch (e: BufferUnderflowException) {
            return null
        }
        return timestamp.takeIf { it >= 0 }
    }
}
