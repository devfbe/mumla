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

package se.lublin.humla.net

import com.google.protobuf.Message
import java.net.ConnectException

/**
 * The TCP control channel to a Mumble server as seen by [HumlaConnection].
 *
 * Implementations post every [HumlaTCP.TCPConnectionListener] call to the handler they were given,
 * so the connection never sees a callback on a socket thread.
 */
interface TcpTransport {
    val isRunning: Boolean
    fun setTCPConnectionListener(listener: HumlaTCP.TCPConnectionListener?)

    @Throws(ConnectException::class)
    fun connect(host: String, port: Int, useTor: Boolean)
    fun sendMessage(message: Message, messageType: HumlaTCPMessageType)
    fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType)

    /** Closes the socket. The listener's onTCPConnectionDisconnect follows exactly once. */
    fun disconnect()
}
