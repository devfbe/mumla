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

/**
 * The UDP voice channel as seen by [HumlaConnection].
 *
 * Single-use: [HumlaConnection] builds a fresh transport for every UDP start and for every restart
 * after a failure, so an implementation may refuse a second [connect].
 */
interface UdpTransport {
    /** True once the socket is open and [sendMessage] can be used. */
    val isRunning: Boolean
    fun connect(host: String, port: Int)
    fun sendMessage(data: ByteArray, length: Int)
    fun disconnect()
}
