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
        version = ByteBuffer.wrap(response).getInt(0),
        identifier = ByteBuffer.wrap(response).getLong(4),
        currentUsers = ByteBuffer.wrap(response).getInt(12),
        maximumUsers = ByteBuffer.wrap(response).getInt(16),
        allowedBandwidth = ByteBuffer.wrap(response).getInt(20),
        latency = latency,
        isDummy = false,
    )

    /** A dummy response. */
    constructor() : this(null, 0, 0L, 0, 0, 0, 0, true)

    val versionString: String
        get() = "%d.%d.%d".format(
            (version shr 16).toByte().toInt(),
            (version shr 8).toByte().toInt(),
            version.toByte().toInt(),
        )
}
