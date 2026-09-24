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
import com.google.protobuf.MessageLite
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
 * [callbackHandler].
 *
 * Reusable, one connection at a time: [connect] is refused until the previous connection's read
 * loop has finished unwinding, which is later than [disconnect] returns.
 *
 * onTCPConnectionDisconnect is delivered exactly once per [connect]: by [disconnect] (so the caller
 * hears about it even while the read thread is stuck in a connect without timeout) or by the read
 * loop when it ends on its own. It is terminal: no callback of that connection is delivered after
 * it, decided at delivery time against that connection's own [epoch].
 *
 * State flags opened and closed on the connect/read side are fenced by [inUse]. State whose closing
 * edge runs on the callback handler outlives its connection and must be per connection, like
 * [epoch].
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
     * Marks the connection whose disconnect callback has been delivered, so [post] can drop
     * callbacks queued before but delivered after it. [disconnectReported] cannot answer that: it
     * flips when the disconnect is queued. Per connection because the disconnect of a previous
     * connection can be delivered after the next [connect]; a reset flag would silence the new one.
     */
    private class Epoch {
        @Volatile var terminated = false
    }

    @Volatile private var epoch = Epoch()

    /**
     * Held from [connect] until the read loop has fully unwound, which is later than [running]
     * clears. Otherwise a connect() during teardown would have its executors shut down and its
     * disconnect token consumed by the outgoing connection's finally block.
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
            epoch = Epoch()
            running = true
            sendExecutor = Executors.newSingleThreadExecutor { Thread(it, "humla-tcp-send") }
            // Publish the executor before starting the loop: its finally shuts it down and must not
            // see null.
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
                if (running) {
                    if (socketFactory.trustFailure == TrustFailure.CHANGED) {
                        post { it.onTLSCertificateChanged(chain) }
                    } else {
                        post { it.onTLSHandshakeFailed(chain) }
                    }
                }
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
            // Drop the streams so a send queued for the next connection before its handshake never
            // writes into this one. [socket] stays set on purpose: disconnect() closes it from the
            // send thread and may get there after this block, so socket != null does not mean
            // "connected".
            input = null
            output = null
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
    override fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend logNoStream(messageType)
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
            val out = output ?: return@enqueueSend logNoStream(messageType)
            out.writeShort(messageType.ordinal)
            out.writeInt(length)
            out.write(data, 0, length)
        }
    }

    /**
     * A send that finds no stream (before the handshake, or after the read loop ended) is dropped
     * with a log line; there is nowhere to write and nobody to throw at.
     */
    private fun logNoStream(messageType: HumlaTCPMessageType) {
        Log.w(TAG, "Dropping $messageType, the TCP connection has no stream")
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
        // Report now rather than from the read loop: createSocket() has no connect timeout, so the
        // read thread can stay blocked for minutes. The read loop's own attempt is then suppressed.
        postDisconnectOnce()
    }

    /** Posts onTCPConnectionDisconnect if no one has posted it yet for this connect(). */
    private fun postDisconnectOnce() {
        if (!disconnectReported.compareAndSet(false, true)) return
        // Only a callback that was actually queued consumes the token: post() fails once the
        // looper has quit, and the read loop's attempt must then still be allowed. (A quit looper
        // never comes back, so the lost race is harmless.)
        val current = epoch // captured here, so a disconnect still in flight cannot mark the next connection
        if (!deliver { current.terminated = true; it.onTCPConnectionDisconnect() }) disconnectReported.set(false)
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
     * Posts a listener callback, unless this connection's disconnect has already been delivered
     * ([epoch], not [disconnectReported]). The read thread cannot see a disconnect while parked in
     * readFrame, so a late frame or onTCPConnectionEstablished is dropped here.
     *
     * The check runs inside the posted runnable: only the handler's FIFO order decides whether
     * this callback ran before the disconnect. The disconnect report itself goes straight to
     * [deliver], or it would suppress itself.
     */
    private fun post(block: (TCPConnectionListener) -> Unit) {
        val current = epoch // captured here: at delivery time [epoch] may already be the next connection's
        deliver { if (!current.terminated) block(it) }
    }

    /** Returns true if the callback was queued on the handler. */
    private fun deliver(block: (TCPConnectionListener) -> Unit): Boolean {
        val l = listener ?: return true // nothing to deliver, so nothing is owed
        return callbackHandler.post { block(l) }
    }

    /** Note that all calls are made on the callback handler this transport was given. */
    interface TCPConnectionListener {
        fun onTCPConnectionEstablished()
        fun onTLSHandshakeFailed(chain: Array<X509Certificate>)
        /** A host with a pinned certificate presented a different one that the system does not trust. */
        fun onTLSCertificateChanged(chain: Array<X509Certificate>)
        fun onTCPConnectionFailed(e: HumlaException)
        fun onTCPConnectionDisconnect()
        fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray)
    }

    companion object {
        private val TAG = HumlaTCP::class.java.name

        /**
         * Largest frame the Mumble protocol allows (Mumble's Connection.cpp rejects packets above
         * 0x7fffff at both ends); anything above it is a broken peer.
         */
        private const val MAX_FRAME_LENGTH = 0x7fffff

        /**
         * Reads one frame: int16 type, int32 length, payload. Returns null (payload consumed) for
         * a type this client does not know, so the stream stays in sync.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun readFrame(input: DataInputStream): TcpFrame? {
            val messageType = input.readShort().toInt()
            val length = input.readInt()
            // Peer-controlled: validate before allocating, or a bad value escapes the read loop as
            // NegativeArraySizeException/OutOfMemoryError instead of a connection error.
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
