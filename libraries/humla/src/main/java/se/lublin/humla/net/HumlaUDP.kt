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
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.GeneralSecurityException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Class to maintain and receive packets from the UDP connection to a Mumble server.
 * Public interface is not thread safe.
 *
 * @param cryptState Cryptographic state provider.
 * @param listener Callback target. Messages will be posted on the callback handler given.
 * @param callbackHandler Handler to post listener invocations on.
 */
class HumlaUDP(
    private val cryptState: CryptState,
    private val listener: UDPConnectionListener,
    private val callbackHandler: Handler,
) : Runnable {
    private var udpSocket: DatagramSocket? = null
    private var host = ""
    private var port = 0
    private var resolvedHost: InetAddress? = null
    @Volatile private var connected = false

    /** Main datagram thread hosting this runnable. */
    private val datagramThread = Thread(this)

    /** Unbounded queue of outgoing packets to be sent. */
    private val sendQueue: BlockingQueue<DatagramPacket> = LinkedBlockingQueue()

    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port
        datagramThread.start()
    }

    val isRunning: Boolean get() = connected

    override fun run() {
        var outgoingConsumerThread: Thread? = null
        connected = true
        try {
            val address = InetAddress.getByName(host)
            resolvedHost = address
            val socket = DatagramSocket()
            udpSocket = socket
            socket.connect(address, port)
            Log.d(TAG, "Created socket")

            // Start outgoing consumer once the UDP socket is open, as a child thread.
            outgoingConsumerThread = Thread(OutgoingConsumer(socket, sendQueue)).also { it.start() }

            val packet = DatagramPacket(ByteArray(BUFFER_SIZE), BUFFER_SIZE)
            while (connected) {
                socket.receive(packet)
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
                        callbackHandler.post { listener.onUDPDataReceived(buffer) }
                    } else if (cryptState.lastGoodElapsed > 5000000 && cryptState.lastRequestElapsed > 5000000) {
                        cryptState.resetLastRequestTime()
                        callbackHandler.post { listener.resyncCryptState() }
                        Log.d(TAG, "Packet failed to decrypt, discarding and requesting crypt state resync")
                    } else {
                        Log.d(TAG, "Packet failed to decrypt, discarding")
                    }
                } catch (e: GeneralSecurityException) {
                    Log.d(TAG, "Discarding packet", e)
                }
            }
        } catch (e: IOException) {
            // If connected is false, then this is a user-triggered disconnection. Report no error.
            if (connected) {
                Log.d(TAG, "UDP socket closed unexpectedly")
                callbackHandler.post { listener.onUDPConnectionError(e) }
            } else {
                Log.d(TAG, "UDP socket closed in response to user disconnect")
            }
        } finally {
            connected = false
            // Interrupt the outgoing queue consumer thread to avoid sends after socket cleanup.
            outgoingConsumerThread?.interrupt()
            // Clear the outgoing queue, in case the caller decides to reconnect with the same socket.
            sendQueue.clear()
            udpSocket?.close()
        }
    }

    fun sendMessage(data: ByteArray, length: Int) {
        if (!cryptState.isValid) {
            Log.w(TAG, "Invalid cryptstate prior to sendMessage call.")
            return
        }
        if (!connected) {
            Log.w(TAG, "Tried to send UDP message without an active connection.")
            return
        }
        try {
            val encryptedData = cryptState.encrypt(data, length)
            val packet = DatagramPacket(encryptedData, encryptedData.size)
            packet.address = resolvedHost
            packet.port = port
            sendQueue.add(packet)
        } catch (e: GeneralSecurityException) {
            e.printStackTrace()
        }
    }

    /** Lazy, non-blocking idempotent disconnect. */
    fun disconnect() {
        connected = false
        // Closing a socket will trigger an IOException on the consumer thread.
        udpSocket?.close()
    }

    /**
     * Note that all connection state related calls are made on the callback handler.
     */
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
            var interrupted = false
            while (!interrupted) {
                try {
                    val packet = queue.take()
                    socket.send(packet)
                } catch (e: IOException) {
                    e.printStackTrace()
                } catch (e: InterruptedException) {
                    // Our datagram thread interrupted us. We should stop reading.
                    interrupted = true
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
