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
 * The TLS/TCP connection to a Mumble server, framing protobuf messages. Reads on "humla-tcp-read",
 * writes on "humla-tcp-send"; every listener callback is posted to [callbackHandler].
 *
 * Reusable, one connection at a time: [connect] is refused until the previous read loop has fully
 * unwound. onTCPConnectionDisconnect is delivered exactly once per [connect] and is terminal: no
 * later callback of that connection is delivered (checked at delivery time against its [epoch]).
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
     * Set once this connection's disconnect callback has run, so [post] drops callbacks delivered
     * after it. Per connection: a previous connection's disconnect can run after the next [connect].
     */
    private class Epoch {
        @Volatile var terminated = false
    }

    @Volatile private var epoch = Epoch()

    /** Held from [connect] until the read loop has fully unwound (later than [running] clears). */
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
            // Publish before starting: the loop's finally shuts it down.
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
            // A send queued for the next connection must not write into this one. [socket] stays
            // set: disconnect() may still close it from the send thread.
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

    /** Thread-safe; writes on the send thread. */
    override fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend logNoStream(messageType)
            out.writeShort(messageType.ordinal)
            out.writeInt(message.serializedSize)
            message.writeTo(out)
        }
    }

    /** Thread-safe; writes a copy of [data] on the send thread, so the caller may reuse it. */
    override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        val bytes = data.copyOf(length)
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) Log.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend logNoStream(messageType)
            out.writeShort(messageType.ordinal)
            out.writeInt(length)
            out.write(bytes, 0, length)
        }
    }

    private fun logNoStream(messageType: HumlaTCPMessageType) {
        Log.w(TAG, "Dropping $messageType, the TCP connection has no stream")
    }

    /**
     * Closes the socket from the send thread, so queued messages are written first. Suppresses all
     * later errors of this connection; no-op if not running.
     */
    override fun disconnect() {
        if (!running) return
        running = false
        enqueueSend { socket?.close() }
        // Report now: createSocket() has no connect timeout, the read thread can block for minutes.
        postDisconnectOnce()
    }

    /** Posts onTCPConnectionDisconnect if no one has posted it yet for this connect(). */
    private fun postDisconnectOnce() {
        if (!disconnectReported.compareAndSet(false, true)) return
        // Only a callback actually queued consumes the token (post() fails once the looper quit).
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
     * Posts a listener callback unless this connection's disconnect has already been delivered. The
     * check runs inside the runnable, so the handler's FIFO order decides.
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

    /** All calls are made on the transport's callback handler. */
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

        /** Largest frame Mumble allows (Connection.cpp rejects anything above at both ends). */
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
            // Peer-controlled: validate before allocating.
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
