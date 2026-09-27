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
import org.junit.Test
import se.lublin.humla.protobuf.MumbleUDP

class UdpPingTest {
    @Test
    fun `a protobuf ping is header byte 1 and a Ping message with the timestamp`() {
        val ping = UdpPing.encode(UdpProtocol.PROTOBUF, 1_000_000L)

        assertThat(ping.map { it.toInt() and 0xFF }).containsExactly(0x01, 0x08, 0xC0, 0x84, 0x3D).inOrder()
    }

    @Test
    fun `a legacy ping is the legacy header and the timestamp varint`() {
        val ping = UdpPing.encode(UdpProtocol.LEGACY, 300L)

        assertThat(ping.map { it.toInt() and 0xFF }).containsExactly(0x20, 0x81, 0x2C).inOrder()
    }

    @Test
    fun `the timestamp of a protobuf reply is read, also with the server's extended fields`() {
        val reply = byteArrayOf(1) + MumbleUDP.Ping.newBuilder()
            .setTimestamp(123_456_789L)
            .setServerVersionV2(0x0001_0005_0000_0000L)
            .setUserCount(3)
            .build()
            .toByteArray()

        assertThat(UdpPing.decodeTimestamp(UdpProtocol.PROTOBUF, reply)).isEqualTo(123_456_789L)
    }

    @Test
    fun `an empty or malformed protobuf reply carries no timestamp`() {
        assertThat(UdpPing.decodeTimestamp(UdpProtocol.PROTOBUF, byteArrayOf(1))).isNull()
        assertThat(UdpPing.decodeTimestamp(UdpProtocol.PROTOBUF, byteArrayOf(1, 0x08))).isNull()
        assertThat(UdpPing.decodeTimestamp(UdpProtocol.PROTOBUF, byteArrayOf(1, 0x0B))).isNull()
    }

    @Test
    fun `a legacy reply round-trips and a bare header carries no timestamp`() {
        assertThat(UdpPing.decodeTimestamp(UdpProtocol.LEGACY, UdpPing.encode(UdpProtocol.LEGACY, 5_000_000L)))
            .isEqualTo(5_000_000L)
        assertThat(UdpPing.decodeTimestamp(UdpProtocol.LEGACY, byteArrayOf(0x20))).isNull()
    }
}
