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

import android.net.SSLCertificateSocketFactory
import android.util.Log
import com.google.protobuf.Message
import se.lublin.humla.util.HumlaException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** One framed Mumble TCP message. */
class TcpFrame(val type: HumlaTCPMessageType, val data: ByteArray)

/**
 * Class to maintain and interface with the TCP connection to a Mumble server.
 * Parses Mumble protobuf packets according to the Mumble protocol specification.
 */
class HumlaTCP(private val socketFactory: HumlaSSLSocketFactory) : HumlaNetworkThread() {
    private var host = ""
    private var port = 0
    private var useTor = false
    private var tcpSocket: SSLSocket? = null
    private var dataInput: DataInputStream? = null
    private var dataOutput: DataOutputStream? = null
    private var running = false
    private var connected = false
    private var listener: TCPConnectionListener? = null

    fun setTCPConnectionListener(listener: TCPConnectionListener?) {
        this.listener = listener
    }

    @Throws(ConnectException::class)
    fun connect(host: String, port: Int, useTor: Boolean) {
        if (running) throw ConnectException("TCP connection already established!")
        this.host = host
        this.port = port
        this.useTor = useTor
        startThreads()
    }

    val isRunning: Boolean get() = running

    override fun run() {
        running = true
        try {
            Log.i(TAG, "Connecting")
            val socket = if (useTor) {
                socketFactory.createTorSocket(host, port, HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT)
            } else {
                socketFactory.createSocket(host, port)
            }
            tcpSocket = socket
            (SSLCertificateSocketFactory.getDefault(0) as SSLCertificateSocketFactory).setHostname(socket, host)
            socket.keepAlive = true
            socket.startHandshake()
            Log.v(TAG, "Started handshake")

            val input = DataInputStream(socket.inputStream)
            dataInput = input
            dataOutput = DataOutputStream(socket.outputStream)

            Log.v(TAG, "Now listening")
            connected = true
            listener?.let { l -> executeOnMainThread { l.onTCPConnectionEstablished() } }

            while (connected) {
                val frame = readFrame(input) ?: continue
                listener?.let { l -> executeOnMainThread { l.onTCPMessageReceived(frame.type, frame.data.size, frame.data) } }
            }
        } catch (e: SocketException) {
            error("Could not open a connection to the host", e)
        } catch (e: SSLHandshakeException) {
            // Try and verify certificate manually.
            val chain = socketFactory.serverChain
            val l = listener
            if (chain != null && l != null) {
                if (!running) return
                executeOnMainThread { l.onTLSHandshakeFailed(chain) }
            } else {
                error("Could not verify host certificate", e)
            }
        } catch (e: IOException) {
            error("An error occurred when communicating with the host", e)
        } finally {
            connected = false
            try {
                dataInput?.close()
                dataOutput?.close()
                tcpSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            running = false
            executeOnMainThread { listener?.onTCPConnectionDisconnect() }
            stopThreads()
        }
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The message to send.
     * @param messageType The type of the message to send.
     */
    fun sendMessage(message: Message, messageType: HumlaTCPMessageType) {
        executeOnSendThread {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            try {
                val out = dataOutput ?: return@executeOnSendThread
                out.writeShort(messageType.ordinal)
                out.writeInt(message.serializedSize)
                message.writeTo(out)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Attempts to send raw data over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The data to send.
     * @param length The length of the byte array.
     * @param messageType The type of the message to send.
     */
    fun sendMessage(message: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        executeOnSendThread {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            try {
                val out = dataOutput ?: return@executeOnSendThread
                out.writeShort(messageType.ordinal)
                out.writeInt(length)
                out.write(message, 0, length)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Attempts to disconnect gracefully on the Tx thread.
     * Disconnects interrupt the socket listening on the Tx thread, suppressing any exceptions
     * caused by this request. Any remaining protobuf messages will be dispatched first.
     *
     * Suppresses all future errors on this connection.
     */
    fun disconnect() {
        if (!running) return
        running = false
        executeOnSendThread {
            try {
                tcpSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        listener?.let { l -> executeOnMainThread { l.onTCPConnectionDisconnect() } }
    }

    private fun error(desc: String, e: Exception) {
        if (!running) return // Don't handle errors post-disconnection.
        val ce = HumlaException(desc, e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        listener?.let { l -> executeOnMainThread { l.onTCPConnectionFailed(ce) } }
    }

    interface TCPConnectionListener {
        fun onTCPConnectionEstablished()
        fun onTLSHandshakeFailed(chain: Array<X509Certificate>)
        fun onTCPConnectionFailed(e: HumlaException)
        fun onTCPConnectionDisconnect()
        fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray)
    }

    companion object {
        private val TAG = HumlaTCP::class.java.name

        /**
         * Reads one frame: int16 type, int32 length, payload. Returns null (payload already
         * consumed) for a type this client does not know, so the stream stays in sync. Lifted
         * verbatim out of the Java read loop.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun readFrame(input: DataInputStream): TcpFrame? {
            val messageType = input.readShort().toInt()
            val length = input.readInt()
            val data = ByteArray(length)
            input.readFully(data)
            val types = HumlaTCPMessageType.values()
            if (messageType < 0 || messageType >= types.size) {
                Log.w(TAG, "Got unsupported messageType: $messageType")
                return null
            }
            return TcpFrame(types[messageType], data)
        }
    }
}
