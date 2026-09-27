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
import se.lublin.humla.util.MumbleVersion.v2

class UdpProtocolTest {
    @Test
    fun `protobuf only when client and server are both at least 1_5_0`() {
        val cases = mapOf(
            (v2(1, 5, 0) to v2(1, 5, 0)) to UdpProtocol.PROTOBUF,
            (v2(1, 5, 0) to v2(1, 5, 857)) to UdpProtocol.PROTOBUF,
            (v2(1, 6, 0) to v2(2, 0, 0)) to UdpProtocol.PROTOBUF,
            (v2(1, 5, 0) to v2(1, 4, 287)) to UdpProtocol.LEGACY,
            (v2(1, 4, 0) to v2(1, 5, 0)) to UdpProtocol.LEGACY,
            (v2(1, 5, 0) to 0L) to UdpProtocol.LEGACY,
        )
        for ((versions, expected) in cases) {
            assertThat(UdpProtocol.negotiate(versions.first, versions.second)).isEqualTo(expected)
        }
    }
}
