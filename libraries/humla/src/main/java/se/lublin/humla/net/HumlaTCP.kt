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
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.protobuf.Message
import se.lublin.humla.util.HumlaException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** One framed Mumble TCP message. */
class TcpFrame(val type: HumlaTCPMessageType, val data: ByteArray)

/**
 * Maintains the TLS/TCP connection to a Mumble server and frames Mumble protobuf packets according
 * to the Mumble protocol specification.
 *
 * Reads on "humla-tcp-read", writes on "humla-tcp-send"; every listener callback is posted to
 * [callbackHandler], which defaults to the main looper so that today's consumers keep seeing
 * callbacks exactly where they saw them before.
 *
 * Reusable, one connection at a time: [connect] is refused until the previous connection's read
 * loop has finished unwinding, which is later than [disconnect] returns.
 *
 * onTCPConnectionDisconnect is delivered exactly once per [connect]: either by [disconnect], so the
 * caller hears about its own request immediately even while the read thread is still stuck in a
 * connect that has no timeout, or by the read loop when it ends on its own. It is also terminal -
 * no callback of this connection follows it, however far the read thread still has to unwind.
 */
class HumlaTCP @JvmOverloads constructor(
    private val socketFactory: HumlaSSLSocketFactory,
    private val callbackHandler: Handler = Handler(Looper.getMainLooper()),
) : TcpTransport {
    private var listener: TCPConnectionListener? = null
    @Volatile private var readExecutor: ExecutorService? = null
    @Volatile private var sendExecutor: ExecutorService? = null
    @Volatile private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    private var host = ""
    private var port = 0
    private var useTor = false
    @Volatile private var running = false
    @Volatile private var connected = false
    private val disconnectReported = AtomicBoolean(true)

    /**
     * Held from [connect] until the read loop has fully unwound - which is later than [running]
     * clears, because [disconnect] clears that immediately while the read thread may still be stuck
     * in a connect with no timeout. Replaces HumlaNetworkThread's mInitialized flag, which was
     * cleared at the very end of stopThreads() for exactly this reason: a connect() landing in the
     * teardown window would have had its disconnect token consumed and its fresh executors shut
     * down by the outgoing connection's finally block, leaving sendMessage a silent no-op and
     * nobody reporting a disconnect.
     */
    private val inUse = AtomicBoolean(false)

    override val isRunning: Boolean get() = running

    override fun setTCPConnectionListener(listener: TCPConnectionListener?) {
        this.listener = listener
    }

    @Throws(ConnectException::class)
    override fun connect(host: String, port: Int, useTor: Boolean) {
        if (!inUse.compareAndSet(false, true)) throw ConnectException("TCP connection already established!")
        try {
            this.host = host
            this.port = port
            this.useTor = useTor
            disconnectReported.set(false)
            running = true
            sendExecutor = Executors.newSingleThreadExecutor { Thread(it, "humla-tcp-send") }
            // Publish the executor before handing the read loop to it: the loop's finally shuts it
            // down and would otherwise be able to observe the field still null and leak a live,
            // non-daemon thread for every connection attempt.
            val reader = Executors.newSingleThreadExecutor { Thread(it, "humla-tcp-read") }
            readExecutor = reader
            reader.execute(::readLoop)
        } catch (e: Throwable) {
            // The read loop never started, so its finally will not release the transport.
            running = false
            inUse.set(false)
            throw e
        }
    }

    private fun readLoop() {
        try {
            Log.i(TAG, "Connecting")
            val tcpSocket = if (useTor) {
                socketFactory.createTorSocket(host, port, HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT)
            } else {
                socketFactory.createSocket(host, port)
            }
            socket = tcpSocket
            // disconnect() raced the connect; bail out before the handshake, finally closes the socket.
            if (!running) return

            (SSLCertificateSocketFactory.getDefault(0) as SSLCertificateSocketFactory).setHostname(tcpSocket, host)
            tcpSocket.keepAlive = true
            tcpSocket.startHandshake()
            Log.v(TAG, "Started handshake")

            val dataInput = DataInputStream(tcpSocket.inputStream)
            input = dataInput
            output = DataOutputStream(tcpSocket.outputStream)
            if (!running) return // disconnect() raced with the handshake; finally closes the socket

            Log.v(TAG, "Now listening")
            connected = true
            post { it.onTCPConnectionEstablished() }

            while (connected && running) {
                val frame = readFrame(dataInput) ?: continue
                post { it.onTCPMessageReceived(frame.type, frame.data.size, frame.data) }
            }
        } catch (e: SocketException) {
            error("Could not open a connection to the host", e)
        } catch (e: SSLHandshakeException) {
            // Let the user verify the certificate manually.
            val chain = socketFactory.serverChain
            if (chain != null && listener != null) {
                if (running) post { it.onTLSHandshakeFailed(chain) }
            } else {
                error("Could not verify host certificate", e)
            }
        } catch (e: IOException) {
            error("An error occurred when communicating with the host", e)
        } finally {
            connected = false
            try {
                input?.close()
                output?.close()
                socket?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing TCP socket", e)
            }
            running = false
            postDisconnectOnce()
            sendExecutor?.shutdown()
            sendExecutor = null
            readExecutor?.shutdown()
            readExecutor = null
            inUse.set(false) // last: only now may a connect() build a new connection on this object
        }
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on the send thread.
     * @param message The message to send.
     * @param messageType The type of the message to send.
     */
    override fun sendMessage(message: Message, messageType: HumlaTCPMessageType) {
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend
            out.writeShort(messageType.ordinal)
            out.writeInt(message.serializedSize)
            message.writeTo(out)
        }
    }

    /**
     * Attempts to send raw data over TCP. Thread-safe, executes on the send thread.
     * @param data The data to send.
     * @param length The length of the byte array.
     * @param messageType The type of the message to send.
     */
    override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend
            out.writeShort(messageType.ordinal)
            out.writeInt(length)
            out.write(data, 0, length)
        }
    }

    /**
     * Attempts to disconnect gracefully: the socket is closed from the send thread, so any protobuf
     * messages already queued are written first. The read loop then exits and its finally block
     * reports onTCPConnectionDisconnect exactly once. Suppresses all future errors on this
     * connection, and is a no-op if the transport is not running.
     */
    override fun disconnect() {
        if (!running) return
        running = false
        enqueueSend { socket?.close() }
        // Report now rather than from the read loop: createSocket() has no connect timeout, so a
        // blackholed server can keep the read thread blocked for minutes with no socket to close,
        // and the caller must not be left believing it is still connected. The read loop's own
        // attempt is then suppressed, which is what makes the callback exactly-once.
        postDisconnectOnce()
    }

    /** Posts onTCPConnectionDisconnect if no one has posted it yet for this connect(). */
    private fun postDisconnectOnce() {
        if (disconnectReported.compareAndSet(false, true)) deliver { it.onTCPConnectionDisconnect() }
    }

    private fun enqueueSend(block: () -> Unit) {
        val executor = sendExecutor ?: return
        try {
            executor.execute {
                try {
                    block()
                } catch (e: IOException) {
                    Log.w(TAG, "TCP send failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "TCP send rejected after shutdown")
        }
    }

    private fun error(description: String, cause: Exception) {
        if (!running) return // Don't handle errors post-disconnection.
        val e = HumlaException(description, cause, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        post { it.onTCPConnectionFailed(e) }
    }

    /**
     * Posts a listener callback, unless the disconnect has already been reported. The read thread
     * parks inside readFrame and cannot see a disconnect that happens meanwhile, so a frame - or a
     * late onTCPConnectionEstablished - can still complete afterwards. The consumer has torn its
     * message handlers down by then, so anything arriving behind the disconnect is dropped here.
     */
    private fun post(block: (TCPConnectionListener) -> Unit) {
        if (disconnectReported.get()) return
        deliver(block)
    }

    private fun deliver(block: (TCPConnectionListener) -> Unit) {
        val l = listener ?: return
        callbackHandler.post { block(l) }
    }

    /** Note that all calls are made on the callback handler this transport was given. */
    interface TCPConnectionListener {
        fun onTCPConnectionEstablished()
        fun onTLSHandshakeFailed(chain: Array<X509Certificate>)
        fun onTCPConnectionFailed(e: HumlaException)
        fun onTCPConnectionDisconnect()
        fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray)
    }

    companion object {
        private val TAG = HumlaTCP::class.java.name

        /** Largest frame the Mumble protocol allows; anything above it is a broken peer. */
        private const val MAX_FRAME_LENGTH = 8 * 1024 * 1024

        /**
         * Reads one frame: int16 type, int32 length, payload. Returns null (payload consumed) for
         * a type this client does not know, so the stream stays in sync. Lifted out of the Java
         * read loop, with the length field validated before it is used to allocate.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun readFrame(input: DataInputStream): TcpFrame? {
            val messageType = input.readShort().toInt()
            val length = input.readInt()
            // The peer controls this field. Allocating on it unchecked turns a negative value into
            // a NegativeArraySizeException and a huge one into an OutOfMemoryError - neither is an
            // IOException, so both would escape the read loop and take the process down instead of
            // reporting a connection error the caller can reconnect from.
            if (length < 0 || length > MAX_FRAME_LENGTH) {
                throw IOException("Invalid frame length: $length")
            }
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
