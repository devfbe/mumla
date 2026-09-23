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

package se.lublin.mumla.servers

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * The ping behind every row of the server list. Its UDP socket was never closed, on any path, so
 * each refresh of the list left one socket per server for the finalizer ("A resource failed to
 * call close").
 */
@RunWith(RobolectricTestRunner::class)
class ServerInfoTaskTest {
    /** A real socket, so close() has something to close, that records the call. */
    private class RecordingSocket(private val reply: ByteArray?) : DatagramSocket() {
        @Volatile var closeCalls = 0

        override fun send(p: DatagramPacket) {
            if (reply == null) throw IOException("unreachable")
        }

        override fun receive(p: DatagramPacket) {
            System.arraycopy(reply!!, 0, p.data, 0, reply.size)
        }

        override fun close() {
            closeCalls++
            super.close()
        }
    }

    private fun taskWith(socket: DatagramSocket) = object : ServerInfoTask() {
        override fun createSocket(): DatagramSocket = socket
    }

    private val server = Server(1, "s", "127.0.0.1", 64738, "me", "")

    @Test
    fun aSuccessfulPingClosesItsSocket() {
        val socket = RecordingSocket(ByteArray(24))

        val response = taskWith(socket).doInBackground(server)

        assertThat(response.isDummy).isFalse()
        assertThat(socket.closeCalls).isEqualTo(1)
    }

    @Test
    fun aFailedPingClosesItsSocket() {
        val socket = RecordingSocket(null)

        val response = taskWith(socket).doInBackground(server)

        assertThat(response.isDummy).isTrue()
        assertThat(socket.closeCalls).isEqualTo(1)
    }
}
