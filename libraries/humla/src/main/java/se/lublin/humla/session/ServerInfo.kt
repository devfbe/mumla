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
package se.lublin.humla.session

import se.lublin.humla.net.HumlaUDPMessageType

/** What the synchronized connection knows about its server, as of the moment it was read. */
data class ServerInfo(
    /** Where the connection went: the entered host, or the target of its SRV record. */
    val host: String,
    val port: Int,
    /** The server's Mumble release, user-readable. */
    val release: String?,
    val osName: String?,
    val osVersion: String?,
    /** The protocol version as 0xAABBCC: major, minor and patch version. */
    val version: Int,
    /** The server's maximum audio bandwidth in bps, or -1 if not set. */
    val maxBandwidth: Int,
    /** The voice codec; null if the server offers none this client can use. */
    val codec: HumlaUDPMessageType?,
    /** The TCP round trip in microseconds. */
    val tcpLatency: Long,
    /** The UDP round trip in microseconds. */
    val udpLatency: Long,
)
