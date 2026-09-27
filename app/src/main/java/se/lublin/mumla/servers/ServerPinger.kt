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

import android.util.Log
import se.lublin.humla.model.Server
import se.lublin.humla.net.ServerResolver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

private const val TAG = "ServerPinger"
private const val REQUEST_SIZE = 12
private const val REPLY_SIZE = 24
private const val TIMEOUT_MS = 1000
private const val RECEIVE_BUFFER_BYTES = 1024
private const val NANOS_PER_MILLI = 1_000_000

/** Pings Mumble servers over UDP. [ping] blocks for up to a second, so call it off the main thread. */
class ServerPinger(
    private val resolver: ServerResolver = ServerResolver(),
    private val createSocket: () -> DatagramSocket = { DatagramSocket() },
) {

    /** Returns [server]'s ping reply, or a dummy response when there is none. */
    suspend fun ping(server: Server): ServerInfoResponse = try {
        val endpoint = resolver.resolve(server)
        val request = ByteBuffer.allocate(REQUEST_SIZE).putInt(0).putLong(server.id).array()
        val requestPacket = DatagramPacket(request, request.size, InetAddress.getByName(endpoint.host), endpoint.port)
        createSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.receiveBufferSize = RECEIVE_BUFFER_BYTES
            val startTime = System.nanoTime()
            socket.send(requestPacket)
            val reply = ByteArray(REPLY_SIZE)
            socket.receive(DatagramPacket(reply, reply.size))
            val latencyMs = ((System.nanoTime() - startTime) / NANOS_PER_MILLI).toInt()
            ServerInfoResponse(server, reply, latencyMs).also {
                Log.d(TAG, "Server version: ${it.versionString} Users: ${it.currentUsers}/${it.maximumUsers}")
            }
        }
    } catch (e: Exception) {
        ServerInfoResponse()
    }
}
