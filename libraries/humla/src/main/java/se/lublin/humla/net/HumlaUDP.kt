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

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.GeneralSecurityException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Receives and sends OCB-AES encrypted voice datagrams over the UDP connection to a Mumble server.
 *
 * Receives on "humla-udp-recv", sends on "humla-udp-send" (both existed before this rewrite; they
 * are the socket loops, not new bare threads). Every listener callback is posted to
 * [callbackHandler], which defaults to the main looper so that today's consumers keep seeing
 * callbacks exactly where they saw them before.
 *
 * Single-use: [connect] may be called once. UDP recovery restarts by creating a new transport, so
 * nothing in production reconnects an instance, and the tested path is the production path.
 * [socketFactory] is the seam a test uses to get hold of the receive socket.
 *
 * The public interface is not thread safe.
 *
 * @param cryptState Cryptographic state provider.
 * @param listener Callback target. Messages will be posted on the callback handler given.
 * @param callbackHandler Handler to post listener invocations on.
 * @param socketFactory Creates the datagram socket the loops run on.
 */
class HumlaUDP @JvmOverloads constructor(
    private val cryptState: CryptState,
    private val listener: UDPConnectionListener,
    private val callbackHandler: Handler = Handler(Looper.getMainLooper()),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : UdpTransport {
    private var host = ""
    private var port = 0
    @Volatile private var resolvedHost: InetAddress? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var connected = false
    @Volatile private var stopRequested = false
    private var receiveThread: Thread? = null

    /** Unbounded queue of outgoing packets to be sent. */
    private val sendQueue: BlockingQueue<DatagramPacket> = LinkedBlockingQueue()

    override val isRunning: Boolean get() = connected

    override fun connect(host: String, port: Int) {
        check(receiveThread == null) { "HumlaUDP is single-use; create a new transport to reconnect" }
        this.host = host
        this.port = port
        receiveThread = Thread(::receiveLoop, "humla-udp-recv").also { it.start() }
    }

    private fun receiveLoop() {
        var sendThread: Thread? = null
        var udpSocket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            resolvedHost = address
            // Publish the socket before connecting it, so a racing disconnect() can always close it.
            udpSocket = socketFactory()
            socket = udpSocket
            udpSocket.connect(address, port)
            Log.d(TAG, "Created socket")

            // Start the outgoing consumer once the UDP socket is open, as a child thread.
            sendThread = Thread(OutgoingConsumer(udpSocket, sendQueue), "humla-udp-send").also { it.start() }
            // A disconnect() that arrived while the socket was being built must not be overwritten.
            connected = !stopRequested

            val packet = DatagramPacket(ByteArray(BUFFER_SIZE), BUFFER_SIZE)
            while (connected) {
                udpSocket.receive(packet)
                val data = packet.data
                val length = packet.length

                if (!cryptState.isValid) {
                    Log.d(TAG, "CryptState invalid, discarding packet")
                    continue
                }
                if (length < 5) {
                    Log.d(TAG, "Packet too short, discarding")
                    continue
                }

                try {
                    val buffer = cryptState.decrypt(data, length)
                    if (buffer != null) {
                        post { listener.onUDPDataReceived(buffer) }
                    } else if (cryptState.lastGoodElapsed > 5000000 && cryptState.lastRequestElapsed > 5000000) {
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
            // Interrupt the outgoing queue consumer thread to avoid sends after socket cleanup.
            sendThread?.interrupt()
            sendQueue.clear()
            udpSocket?.close()
        }
    }

    /**
     * Posts a listener callback. There is no second route to the listener, so a post the handler
     * refuses - its looper has quit - is a lost callback; say so instead of dropping it silently.
     */
    private fun post(block: () -> Unit) {
        if (!callbackHandler.post(block)) Log.w(TAG, "Callback dropped, the callback handler is gone")
    }

    override fun sendMessage(data: ByteArray, length: Int) {
        if (!cryptState.isValid) {
            Log.w(TAG, "Invalid cryptstate prior to sendMessage call.")
            return
        }
        if (!connected) {
            // A deliberate change from the Java original, which set mConnected at the head of run():
            // packets produced while the host was resolved and the socket built were encrypted and
            // queued there, and went out once the socket opened, because a connected DatagramSocket
            // fills in the address a queued packet left null. They are dropped here instead. The
            // window is one cached DNS lookup plus a socket creation wide, a voice frame held back
            // over it would be played late anyway, and dropping before encrypt() keeps a connection
            // that never opens from accumulating packets nobody will ever send.
            Log.w(TAG, "Tried to send UDP message without an active connection.")
            return
        }
        val address = resolvedHost ?: return
        try {
            val encrypted = cryptState.encrypt(data, length)
            sendQueue.add(DatagramPacket(encrypted, encrypted.size, address, port))
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

    /** Note that all calls are made on the callback handler this transport was given. */
    interface UDPConnectionListener {
        fun onUDPDataReceived(data: ByteArray)
        fun onUDPConnectionError(e: Exception)
        fun resyncCryptState()
    }

    /** Runnable that reads from a shared blocking queue, dispatching datagrams when available. */
    private class OutgoingConsumer(
        private val socket: DatagramSocket,
        private val queue: BlockingQueue<DatagramPacket>,
    ) : Runnable {
        override fun run() {
            Log.d(TAG, "Datagram outbox consumer active")
            while (true) {
                val packet = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    // Our datagram thread interrupted us. We should stop reading.
                    break
                }
                try {
                    socket.send(packet)
                } catch (e: IOException) {
                    Log.w(TAG, "UDP send failed", e)
                }
            }
            Log.d(TAG, "Datagram outbox consumer shutdown")
        }
    }

    companion object {
        private val TAG = HumlaUDP::class.java.name
        private const val BUFFER_SIZE = 2048
    }
}
