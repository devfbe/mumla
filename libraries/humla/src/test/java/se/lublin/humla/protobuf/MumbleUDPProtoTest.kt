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
package se.lublin.humla.protobuf

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.Test

/** Pins the proto3 wire format of the generated MumbleUDP classes against hand-verified bytes. */
class MumbleUDPProtoTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a target of zero is on the wire because it is a oneof member`() {
        val audio = MumbleUDP.Audio.newBuilder()
            .setTarget(0)
            .setFrameNumber(300)
            .setOpusData(ByteString.copyFrom(bytes(0xAA, 0xBB)))
            .setIsTerminator(true)
            .build()

        // target (1, varint) 0; frame_number (4, varint) 300; opus_data (5, bytes); is_terminator (16) true
        assertThat(audio.toByteArray()).isEqualTo(
            bytes(0x08, 0x00, 0x20, 0xAC, 0x02, 0x2A, 0x02, 0xAA, 0xBB, 0x80, 0x01, 0x01),
        )
    }

    @Test
    fun `the server's fields parse, with positional data packed and the volume as a fixed32 float`() {
        val wire = bytes(
            0x10, 0x03, // context 3
            0x18, 0x07, // sender_session 7
            0x2A, 0x01, 0x55, // opus_data
            0x32, 0x0C, 0, 0, 0x80, 0x3F, 0, 0, 0, 0x40, 0, 0, 0x40, 0x40, // positional_data 1, 2, 3
            0x3D, 0, 0, 0, 0x3F, // volume_adjustment 0.5
        )

        val audio = MumbleUDP.Audio.parseFrom(wire)

        assertThat(audio.headerCase).isEqualTo(MumbleUDP.Audio.HeaderCase.CONTEXT)
        assertThat(audio.context).isEqualTo(3)
        assertThat(audio.senderSession).isEqualTo(7)
        assertThat(audio.frameNumber).isEqualTo(0)
        assertThat(audio.opusData.toByteArray()).isEqualTo(bytes(0x55))
        assertThat(audio.positionalDataList).containsExactly(1f, 2f, 3f).inOrder()
        assertThat(audio.volumeAdjustment).isEqualTo(0.5f)
        assertThat(audio.isTerminator).isFalse()
    }

    @Test
    fun `a ping carries its timestamp and the extended-information request`() {
        val ping = MumbleUDP.Ping.newBuilder().setTimestamp(1_000_000).setRequestExtendedInformation(true).build()

        assertThat(ping.toByteArray()).isEqualTo(bytes(0x08, 0xC0, 0x84, 0x3D, 0x10, 0x01))
    }
}
