/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.servers

import se.lublin.humla.model.Server
import java.nio.ByteBuffer

/* The ping reply: version, the request's identifier, users, maximum users, bandwidth. */
private const val VERSION_OFFSET = 0
private const val IDENTIFIER_OFFSET = 4
private const val CURRENT_USERS_OFFSET = 12
private const val MAXIMUM_USERS_OFFSET = 16
private const val BANDWIDTH_OFFSET = 20

/** The version is `major.minor.patch`, a byte each, in the low three bytes. */
private const val MAJOR_SHIFT = 16
private const val MINOR_SHIFT = 8

/** A server's answer to a UDP ping. A [isDummy] response stands for a ping that got no answer. */
class ServerInfoResponse private constructor(
    val server: Server?,
    val version: Int,
    val identifier: Long,
    val currentUsers: Int,
    val maximumUsers: Int,
    val allowedBandwidth: Int,
    val latency: Int,
    val isDummy: Boolean,
) {
    /** Parses the 24-byte ping reply [response] from [server]. */
    constructor(server: Server, response: ByteArray, latency: Int) : this(
        server = server,
        version = ByteBuffer.wrap(response).getInt(VERSION_OFFSET),
        identifier = ByteBuffer.wrap(response).getLong(IDENTIFIER_OFFSET),
        currentUsers = ByteBuffer.wrap(response).getInt(CURRENT_USERS_OFFSET),
        maximumUsers = ByteBuffer.wrap(response).getInt(MAXIMUM_USERS_OFFSET),
        allowedBandwidth = ByteBuffer.wrap(response).getInt(BANDWIDTH_OFFSET),
        latency = latency,
        isDummy = false,
    )

    /** A dummy response. */
    constructor() : this(null, 0, 0L, 0, 0, 0, 0, true)

    val versionString: String
        get() = "%d.%d.%d".format(
            (version shr MAJOR_SHIFT).toByte().toInt(),
            (version shr MINOR_SHIFT).toByte().toInt(),
            version.toByte().toInt(),
        )
}
