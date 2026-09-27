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

import se.lublin.humla.util.MumbleVersion

/**
 * The wire format of UDP voice and pings, also inside a UDPTunnel message. Servers from 1.5.0 on
 * speak the protobuf format (MumbleUDP.proto) with clients from 1.5.0 on and the legacy one with
 * everybody else; OCB2 encryption wraps either packet unchanged.
 */
internal enum class UdpProtocol {
    /** A header byte with the type in its top three bits, then Mumble varints. */
    LEGACY,

    /** A header byte with the type (0 audio, 1 ping), then a MumbleUDP protobuf message. */
    PROTOBUF,
    ;

    companion object {
        /** The first version speaking [PROTOBUF]. */
        val PROTOBUF_INTRODUCTION: Long = MumbleVersion.v2(1, 5, 0)

        /** The format for a connection between these two versions, both in the v2 format. */
        fun negotiate(clientV2: Long, serverV2: Long): UdpProtocol =
            if (clientV2 >= PROTOBUF_INTRODUCTION && serverV2 >= PROTOBUF_INTRODUCTION) PROTOBUF else LEGACY
    }
}
