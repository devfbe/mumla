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

import com.google.protobuf.MessageLite

/** Receives every parsed TCP message from the server; handlers pick types with `is` checks. */
internal fun interface TcpMessageHandler {
    fun onMessage(msg: MessageLite)
}

/** Receives voice packets, whether they arrived over UDP or tunnelled through TCP. */
internal fun interface VoicePacketHandler {
    /** [packet] is reused for the next packet: valid only until this call returns. */
    fun onVoicePacket(packet: VoicePacket)
}
