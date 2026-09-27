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

import androidx.annotation.VisibleForTesting
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.util.HumlaLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class TcpFrame(val type: HumlaTCPMessageType, val data: ByteArray)

/**
 * The TLS/TCP connection to a Mumble server, framing protobuf messages. Single-use.
 *
 * Reads and writes run in [scope] on [Dispatchers.IO]: a blocking read loop and a writer draining
 * the send queue in order. Every listener callback is dispatched on [scope]'s dispatcher.
 * onTCPConnectionDisconnect is delivered exactly once and is terminal: no later callback is
 * delivered (checked at delivery time). Cancelling [scope] closes the socket.
 */
class HumlaTCP(
    private val socketFactory: HumlaSSLSocketFactory,
    private val scope: CoroutineScope,
) : TcpTransport {
    private var listener: TCPConnectionListener? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var running = false
    private val connectCalled = AtomicBoolean(false)
    private val disconnectReported = AtomicBoolean(false)

    /** Set when onTCPConnectionDisconnect is delivered; callbacks behind it are dropped. */
    @Volatile private var terminated = false

    /** Writes in submission order; closed once the connection ends, which stops the writer. */
    private val sendQueue = Channel<() -> Unit>(Channel.UNLIMITED)
    private var job: Job? = null

    override val isRunning: Boolean get() = running

    /** True once every coroutine of this transport has finished. */
    @VisibleForTesting
    internal val isFinished: Boolean get() = job?.isCompleted ?: true

    override fun setTCPConnectionListener(listener: TCPConnectionListener?) {
        this.listener = listener
    }

    override fun connect(host: String, port: Int, useTor: Boolean) {
        if (!connectCalled.compareAndSet(false, true)) throw ConnectException("HumlaTCP is single-use")
        running = true
        job = scope.launch(Dispatchers.IO) {
            coroutineScope {
                // Undispatched, so it is started, and its finally will close the socket, even if the
                // scope is cancelled right now.
                launch(start = CoroutineStart.UNDISPATCHED) { writeLoop() }
                readLoop(host, port, useTor)
            }
        }
    }

    /** Writes until [sendQueue] is closed, or drains it when cancelled; then closes the socket. */
    private suspend fun writeLoop() {
        try {
            for (write in sendQueue) write()
        } finally {
            sendQueue.close()
            while (true) (sendQueue.tryReceive().getOrNull() ?: break).invoke()
            closeSocket()
        }
    }

    private suspend fun readLoop(host: String, port: Int, useTor: Boolean) {
        val context = currentCoroutineContext()
        var input: DataInputStream? = null
        try {
            HumlaLog.i(TAG, "Connecting")
            val tcpSocket = openSocket(host, port, useTor)
            socket = tcpSocket
            // disconnect() or cancellation raced the connect; finally closes the socket.
            if (!running || !context.isActive) return

            tcpSocket.keepAlive = true
            tcpSocket.startHandshake()
            HumlaLog.v(TAG, "Started handshake")

            val dataInput = DataInputStream(tcpSocket.inputStream)
            input = dataInput
            output = DataOutputStream(tcpSocket.outputStream)
            if (!running || !context.isActive) return

            HumlaLog.v(TAG, "Now listening")
            post { it.onTCPConnectionEstablished() }

            while (running) {
                val frame = readFrame(dataInput) ?: continue
                post { it.onTCPMessageReceived(frame.type, frame.data.size, frame.data) }
            }
        } catch (e: SocketException) {
            error("Could not open a connection to the host", e)
        } catch (e: SSLHandshakeException) {
            onHandshakeFailed(e)
        } catch (e: IOException) {
            error("An error occurred when communicating with the host", e)
        } finally {
            try {
                input?.close()
                output?.close()
            } catch (e: IOException) {
                HumlaLog.w(TAG, "Error closing TCP streams", e)
            }
            closeSocket()
            output = null
            running = false
            postDisconnectOnce()
            sendQueue.close() // the writer drains what is queued and ends
        }
    }

    private fun openSocket(host: String, port: Int, useTor: Boolean): SSLSocket = if (useTor) {
        socketFactory.createTorSocket(host, port, HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT)
    } else {
        socketFactory.createSocket(host, port)
    }

    /** Lets the user verify the certificate manually, if there is one to show. */
    private fun onHandshakeFailed(e: SSLHandshakeException) {
        val chain = socketFactory.serverChain
        if (chain == null || listener == null) {
            error("Could not verify host certificate", e)
        } else if (running) {
            if (socketFactory.trustFailure == TrustFailure.CHANGED) {
                post { it.onTLSCertificateChanged(chain) }
            } else {
                post { it.onTLSHandshakeFailed(chain) }
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: IOException) {
            HumlaLog.w(TAG, "Error closing TCP socket", e)
        }
    }

    /** Thread-safe; writes in order after everything queued before. */
    override fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) HumlaLog.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend logNoStream(messageType)
            out.writeShort(messageType.ordinal)
            out.writeInt(message.serializedSize)
            message.writeTo(out)
        }
    }

    /** Thread-safe; writes a copy of [data], so the caller may reuse it. */
    override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        val bytes = data.copyOf(length)
        enqueueSend {
            if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType)) HumlaLog.v(TAG, "OUT: $messageType")
            val out = output ?: return@enqueueSend logNoStream(messageType)
            out.writeShort(messageType.ordinal)
            out.writeInt(length)
            out.write(bytes, 0, length)
        }
    }

    private fun logNoStream(messageType: HumlaTCPMessageType) {
        HumlaLog.w(TAG, "Dropping $messageType, the TCP connection has no stream")
    }

    /**
     * Closes the socket once the messages queued so far are written. Suppresses all later errors of
     * this connection; no-op if not running.
     */
    override fun disconnect() {
        if (!running) return
        running = false
        sendQueue.close()
        // Report now: createSocket() has no connect timeout, the read loop can block for minutes.
        postDisconnectOnce()
    }

    /** Posts onTCPConnectionDisconnect if no one has posted it yet. */
    private fun postDisconnectOnce() {
        if (!disconnectReported.compareAndSet(false, true)) return
        deliver { terminated = true; it.onTCPConnectionDisconnect() }
    }

    private fun enqueueSend(block: () -> Unit) {
        val write: () -> Unit = {
            try {
                block()
            } catch (e: IOException) {
                HumlaLog.w(TAG, "TCP send failed", e)
            }
        }
        if (sendQueue.trySend(write).isFailure) HumlaLog.w(TAG, "TCP send rejected after shutdown")
    }

    private fun error(description: String, cause: Exception) {
        if (!running) return // Don't handle errors post-disconnection.
        val e = HumlaException(description, cause, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        post { it.onTCPConnectionFailed(e) }
    }

    /** Posts a listener callback that is dropped if the disconnect was delivered before it. */
    private fun post(block: (TCPConnectionListener) -> Unit) {
        deliver { if (!terminated) block(it) }
    }

    private fun deliver(block: (TCPConnectionListener) -> Unit) {
        val l = listener ?: return
        scope.launch { block(l) }
    }

    /** All calls are made on the transport scope's dispatcher. */
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
                HumlaLog.w(TAG, "Got unsupported messageType: $messageType")
                return null
            }
            return TcpFrame(types[messageType], data)
        }
    }
}
