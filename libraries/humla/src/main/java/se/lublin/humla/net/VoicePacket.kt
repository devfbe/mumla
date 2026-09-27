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

/**
 * One voice packet the server sent, decoded from either [UdpProtocol]. Owned and reused by the
 * connection: valid only during the handler call it is passed to.
 */
internal class VoicePacket {
    /** The codec; only Opus packets carry the fields below. */
    var codec: HumlaUDPMessageType = HumlaUDPMessageType.UDPVoiceOpus
        internal set

    /** Why this client receives the audio: one of the `CONTEXT_*` values, or another target id. */
    var context: Int = CONTEXT_NORMAL
        internal set

    /** The session of the user talking. */
    var session: Int = 0
        internal set

    /** The position of the first frame in the talker's stream, in 10 ms frames. */
    var frameNumber: Long = 0
        internal set

    /** Holds the Opus packet at [opusOffset], [opusLength] bytes long. */
    var data: ByteArray = EMPTY
        internal set
    var opusOffset: Int = 0
        internal set
    var opusLength: Int = 0
        internal set

    /** The last packet of a transmission. */
    var isTerminator: Boolean = false
        internal set

    /** Gain the server asks the client to apply, 1 if none. */
    var volumeAdjustment: Float = 1f
        internal set

    internal fun clear() {
        codec = HumlaUDPMessageType.UDPVoiceOpus
        context = CONTEXT_NORMAL
        session = 0
        frameNumber = 0
        data = EMPTY
        opusOffset = 0
        opusLength = 0
        isTerminator = false
        volumeAdjustment = 1f
    }

    companion object {
        const val CONTEXT_NORMAL = 0
        const val CONTEXT_SHOUT = 1
        const val CONTEXT_WHISPER = 2
        const val CONTEXT_LISTEN = 3

        private val EMPTY = ByteArray(0)
    }
}
