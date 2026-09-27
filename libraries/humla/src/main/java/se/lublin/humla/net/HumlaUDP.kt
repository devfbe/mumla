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

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.GeneralSecurityException

/** The encryption header alone is four bytes; anything this short carries no voice. */
private const val MIN_DATAGRAM_BYTES = 5

/** How long decryption may fail before a crypt resync is requested, and how often. */
private const val RESYNC_AFTER_MICROS = 5_000_000

/**
 * Receives and sends OCB-AES encrypted voice datagrams over the UDP connection to a Mumble server.
 *
 * A blocking receive loop and a sender draining the send queue run in [scope] on [Dispatchers.IO];
 * every listener callback is dispatched on [scope]'s dispatcher.
 *
 * Single-use: [connect] may be called once; UDP recovery creates a new transport.
 *
 * @param scope The connection's scope; its dispatcher delivers the callbacks.
 * @param socketFactory Creates the datagram socket the loops run on.
 */
class HumlaUDP(
    private val cryptState: CryptState,
    private val listener: UDPConnectionListener,
    private val scope: CoroutineScope,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : UdpTransport {
    private var port = 0
    @Volatile private var resolvedHost: InetAddress? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var connected = false
    @Volatile private var stopRequested = false
    private var job: Job? = null

    private val sendQueue = Channel<DatagramPacket>(Channel.UNLIMITED)

    override val isRunning: Boolean get() = connected

    /** True once every coroutine of this transport has finished. */
    @VisibleForTesting
    internal val isFinished: Boolean get() = job?.isCompleted ?: true

    override fun connect(host: String, port: Int) {
        check(job == null) { "HumlaUDP is single-use; create a new transport to reconnect" }
        this.port = port
        job = scope.launch(Dispatchers.IO) { receiveLoop(host, port) }
    }

    private suspend fun receiveLoop(host: String, port: Int): Unit = coroutineScope {
        var sender: Job? = null
        var udpSocket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            resolvedHost = address
            // Publish the socket before connecting it, so a racing disconnect() can always close it.
            udpSocket = socketFactory()
            socket = udpSocket
            udpSocket.connect(address, port)
            Log.d(TAG, "Created socket")

            // Undispatched, so it is started, and closes the socket, even if the scope is cancelled now.
            sender = launch(start = CoroutineStart.UNDISPATCHED) { sendLoop(udpSocket) }
            // A disconnect() that arrived while the socket was being built must not be overwritten.
            connected = !stopRequested && isActive

            val packet = DatagramPacket(ByteArray(BUFFER_SIZE), BUFFER_SIZE)
            while (connected) {
                udpSocket.receive(packet)
                onDatagram(packet.data, packet.length)
            }
        } catch (e: IOException) {
            // If a stop was requested, then this is a user-triggered disconnection. Report no error.
            if (!stopRequested) {
                Log.d(TAG, "UDP socket closed unexpectedly")
                post { listener.onUDPConnectionError(e) }
            } else {
                Log.d(TAG, "UDP socket closed in response to user disconnect")
            }
        } finally {
            connected = false
            // No sends after socket cleanup.
            sender?.cancel()
            sendQueue.close()
            udpSocket?.close()
        }
    }

    private fun onDatagram(data: ByteArray, length: Int) {
        if (!cryptState.isValid) {
            Log.d(TAG, "CryptState invalid, discarding packet")
            return
        }
        if (length < MIN_DATAGRAM_BYTES) {
            Log.d(TAG, "Packet too short, discarding")
            return
        }
        try {
            val buffer = cryptState.decrypt(data, length)
            if (buffer != null) {
                post { listener.onUDPDataReceived(buffer) }
            } else if (
                cryptState.lastGoodElapsed > RESYNC_AFTER_MICROS &&
                cryptState.lastRequestElapsed > RESYNC_AFTER_MICROS
            ) {
                cryptState.resetLastRequestTime()
                post { listener.resyncCryptState() }
                Log.d(TAG, "Packet failed to decrypt, discarding and requesting crypt state resync")
            } else {
                Log.d(TAG, "Packet failed to decrypt, discarding")
            }
        } catch (e: GeneralSecurityException) {
            Log.d(TAG, "Discarding packet", e)
        }
    }

    /** Sends until cancelled, then closes the socket, which ends the receive loop too. */
    private suspend fun sendLoop(udpSocket: DatagramSocket) {
        try {
            for (packet in sendQueue) {
                try {
                    udpSocket.send(packet)
                } catch (e: IOException) {
                    Log.w(TAG, "UDP send failed", e)
                }
            }
        } finally {
            udpSocket.close()
        }
    }

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    override fun sendMessage(data: ByteArray, length: Int) {
        if (!cryptState.isValid) {
            Log.w(TAG, "Invalid cryptstate prior to sendMessage call.")
            return
        }
        if (!connected) {
            // Drop before encrypt(): encrypt() consumes an OCB2 sequence number, so a packet that is
            // never sent would punch a hole in the server's replay window.
            Log.w(TAG, "Tried to send UDP message without an active connection.")
            return
        }
        val address = resolvedHost ?: return
        try {
            val encrypted = cryptState.encrypt(data, length)
            sendQueue.trySend(DatagramPacket(encrypted, encrypted.size, address, port))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Could not encrypt UDP packet", e)
        }
    }

    /** Non-blocking, idempotent. Closing the socket makes the receive loop exit without reporting an error. */
    override fun disconnect() {
        stopRequested = true
        connected = false
        socket?.close()
    }

    /** All calls are made on the transport scope's dispatcher. */
    interface UDPConnectionListener {
        fun onUDPDataReceived(data: ByteArray)
        fun onUDPConnectionError(e: Exception)
        fun resyncCryptState()
    }

    companion object {
        private val TAG = HumlaUDP::class.java.name
        private const val BUFFER_SIZE = 2048
    }
}
