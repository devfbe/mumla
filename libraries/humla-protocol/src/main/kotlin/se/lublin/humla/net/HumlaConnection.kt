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

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Latency
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerInfo
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaLog
import java.io.IOException
import java.net.ConnectException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

private const val BYTE_MASK = 0xFF
private const val LEGACY_TYPE_SHIFT = 5

/** A key store file of CA certificates to trust besides the system's. */
internal class TrustStore(val path: String, val password: String?, val format: String?)

/** What a connection is opened with; fixed for its lifetime. */
internal class ConnectionParams(
    val server: Server,
    val forceTcp: Boolean = false,
    /** Proxy through a local Orbot; implies tunnelled voice. */
    val useTor: Boolean = false,
    /** A PKCS#12 client certificate. */
    val certificate: ByteArray? = null,
    val certificatePassword: String? = null,
    val trustStore: TrustStore? = null,
) {
    /** Voice goes through the TCP tunnel for the whole connection. */
    val tunnelVoice: Boolean get() = forceTcp || useTor
}

/**
 * The lifecycle of a [HumlaConnection]. Only ever moves forward - Idle, Connecting, Established,
 * Synchronized - and from any of them into the terminal [Closed].
 */
internal sealed interface ConnectionState {
    data object Idle : ConnectionState

    /** Resolving, opening the socket, TLS. */
    data object Connecting : ConnectionState

    /** TLS is up and the handshake under way. */
    data object Established : ConnectionState

    /** ServerSync arrived. */
    data object Synchronized : ConnectionState

    /** [error] is null for a clean end; [started] tells whether a listener is owed the report. */
    class Closed(val error: HumlaException?, val started: Boolean) : ConnectionState
}

/**
 * One connection to a Mumble server, single-use: the TCP and UDP transports, the crypt state, and
 * the framing between them and the [ProtocolSession] and message handlers above.
 *
 * Everything of the connection - resolution, socket creation, parsing, dispatch, voice routing,
 * pings, transport callbacks - runs in one scope on [dispatcher], one task at a time in submission
 * order; the close cancels it. [Listener] callbacks run on [callbacks], in order, and none follows
 * the disconnect report. [sendTCPMessage], [sendUDPMessage], [post] and [disconnect] may be called
 * from any thread.
 *
 * @param dispatcher Runs one task at a time, in order. By default a view of the IO pool rather than
 *   a thread of its own: resolution, key store loading and the TLS setup block, which is what IO
 *   is for, and a connection that has ended leaves no thread behind.
 */
// The transport listener interfaces and the handler registry; the seams tests replace.
@Suppress("TooManyFunctions", "LongParameterList")
internal class HumlaConnection(
    val params: ConnectionParams,
    private val listener: Listener,
    private val callbacks: Executor,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val transports: TransportFactory = DefaultTransportFactory,
    private val resolver: ServerResolver = ServerResolver(),
    protocolFactory: (ProtocolSession.Link, Boolean) -> ProtocolSession = ::ProtocolSession,
) : HumlaTCP.TCPConnectionListener, HumlaUDP.UDPConnectionListener, MessageHandlerRegistry {

    /**
     * Builds the transports, so tests can supply fakes that never open a socket. Each gets the
     * connection's scope: cancelled on disconnect, dispatching on the protocol context.
     */
    interface TransportFactory {
        fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport

        fun createUdp(cryptState: CryptState, listener: HumlaUDP.UDPConnectionListener, scope: CoroutineScope):
            UdpTransport
    }

    object DefaultTransportFactory : TransportFactory {
        override fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
            HumlaTCP(socketFactory, scope)

        override fun createUdp(
            cryptState: CryptState,
            listener: HumlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport = HumlaUDP(cryptState, listener, scope)
    }

    /** Every call is made on the connection's callback executor. */
    interface Listener {
        /** The socket to the server has opened. */
        fun onConnectionEstablished()

        /** The handshake completed. */
        fun onConnectionSynchronized()

        /** Certificate verification failed; [onConnectionDisconnected] still follows. */
        fun onConnectionHandshakeFailed(chain: Array<X509Certificate>)
        fun onConnectionCertificateChanged(chain: Array<X509Certificate>)

        /** [e] is null for a clean disconnect. Exactly once per started connection, and last. */
        fun onConnectionDisconnected(e: HumlaException?)

        fun onConnectionWarning(warning: ConnectionWarning)
    }

    private val state = AtomicReference<ConnectionState>(ConnectionState.Idle)

    /** Parent of all of the connection's coroutines; cancelled when it closes. */
    private val job = SupervisorJob()

    /** An exception nothing caught ends the connection the way a transport failure does. */
    private val scope = CoroutineScope(
        job + dispatcher + CoroutineExceptionHandler { _, e ->
            fail(HumlaException("Connection failed", e, HumlaException.HumlaDisconnectReason.OTHER_ERROR))
        }
    )

    /** Closes the transports, behind everything queued on the protocol context at the close. */
    @Volatile private var teardown: Job? = null

    /** True once no coroutine of this connection is left. */
    val isTerminated: Boolean get() = job.isCompleted && teardown?.isCompleted != false

    private val closed: Boolean get() = state.get() is ConnectionState.Closed

    @Volatile private var tcp: TcpTransport? = null
    @Volatile private var udp: UdpTransport? = null
    private val cryptState = CryptState()

    /** Where the connection goes, once resolved; set before any transport exists. */
    @Volatile var endpoint: Endpoint? = null
        private set

    private val link = object : ProtocolSession.Link {
        override val scope: CoroutineScope get() = this@HumlaConnection.scope
        override val cryptState: CryptState get() = this@HumlaConnection.cryptState
        override val endpoint: Endpoint get() = checkNotNull(this@HumlaConnection.endpoint)

        override fun sendTcp(message: MessageLite, type: HumlaTCPMessageType) = sendTCPMessage(message, type)

        override fun sendUdp(data: ByteArray, length: Int) {
            if (isConnected) udp?.sendMessage(data, length)
        }

        override fun startUdp() {
            if (closed) return
            val transport = transports.createUdp(cryptState, this@HumlaConnection, scope)
            udp = transport
            transport.connect(endpoint.host, endpoint.port)
        }

        override fun onSynchronized() {
            if (state.compareAndSet(ConnectionState.Established, ConnectionState.Synchronized)) {
                notify { onConnectionSynchronized() }
            }
        }

        override fun onWarning(warning: ConnectionWarning) = notify { onConnectionWarning(warning) }

        override fun fail(error: HumlaException) = this@HumlaConnection.fail(error)
    }

    private val protocol = protocolFactory(link, params.tunnelVoice)

    private val tcpHandlers = ConcurrentLinkedQueue<TcpMessageHandler>()
    private val voiceHandlers = ConcurrentLinkedQueue<VoicePacketHandler>()

    /** Decodes received voice into [voicePacket]; protocol context only. */
    private val udpDecoder = UdpPacketDecoder()
    private val voicePacket = VoicePacket()

    /** Read and written only by tasks on [callbacks]. */
    private var disconnectReported = false

    val isConnected: Boolean
        get() = state.get().let { it == ConnectionState.Established || it == ConnectionState.Synchronized }

    /** True once ServerSync arrived; don't log user actions before that. */
    val isSynchronized: Boolean get() = state.get() == ConnectionState.Synchronized

    /** What ServerSync told about the server; null outside of a synchronized connection. */
    val serverInfo: ServerInfo? get() = protocol.serverInfo.takeIf { isSynchronized }

    /** The latest round trips; null outside of a synchronized connection. */
    val latency: Latency? get() = protocol.latency.takeIf { isSynchronized }

    /** The negotiated voice format; see [ProtocolSession.udpProtocol]. */
    val udpProtocol: UdpProtocol get() = protocol.udpProtocol

    /** Whether [sendUDPMessage] would use UDP for an unforced packet. */
    val isUsingUdp: Boolean get() = protocol.usingUdp

    /** If the connection ended with an error, that error. */
    val error: HumlaException? get() = (state.get() as? ConnectionState.Closed)?.error

    /**
     * Starts connecting. Resolution (the SRV lookup included, skipped over Tor), key store loading
     * and socket creation run on the protocol context; every outcome goes to the listener.
     */
    fun connect() {
        check(state.compareAndSet(ConnectionState.Idle, ConnectionState.Connecting)) {
            "HumlaConnection is single-use; create a new one for another connection"
        }
        protocol.start()
        scope.launch {
            val socketFactory = try {
                createSocketFactory()
            } catch (e: HumlaException) {
                fail(e)
                return@launch
            }
            val endpoint = resolver.resolve(params.server, params.useTor)
            this@HumlaConnection.endpoint = endpoint
            val transport = transports.createTcp(socketFactory, scope)
            transport.setTCPConnectionListener(this@HumlaConnection)
            tcp = transport
            try {
                transport.connect(endpoint.host, endpoint.port, params.useTor)
            } catch (e: ConnectException) {
                fail(HumlaException(e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR))
            }
        }
    }

    /** Runs [block] on the protocol context, after what is queued there already; dropped once closed. */
    fun post(block: () -> Unit) {
        if (!closed) scope.launch { block() }
    }

    override fun addTcpHandler(handler: TcpMessageHandler) {
        tcpHandlers.add(handler)
    }

    override fun removeTcpHandler(handler: TcpMessageHandler) {
        tcpHandlers.remove(handler)
    }

    override fun addVoiceHandler(handler: VoicePacketHandler) {
        voiceHandlers.add(handler)
    }

    override fun removeVoiceHandler(handler: VoicePacketHandler) {
        voiceHandlers.remove(handler)
    }

    /**
     * Shuts down networking. Safe from any thread, idempotent, non-blocking. onConnectionDisconnected
     * is delivered exactly once per started connection, and last.
     */
    fun disconnect() {
        close(null)
    }

    /** Ends the connection with [error], unless it has ended already. */
    private fun fail(error: HumlaException) {
        if (close(error)) HumlaLog.e(TAG, "Fatal connection error: ${error.message}", error)
    }

    /** Moves to [ConnectionState.Closed]; false if the connection was closed already. */
    private fun close(error: HumlaException?): Boolean {
        val previous = state.getAndUpdate {
            it as? ConnectionState.Closed ?: ConnectionState.Closed(error, started = it != ConnectionState.Idle)
        }
        if (previous is ConnectionState.Closed) return false
        job.cancel()
        // Nothing started means nothing to tear down or report.
        if (previous != ConnectionState.Idle) {
            // Behind whatever the protocol context is running, so it never races a transport being
            // built; not cancellable, as the scope it runs in has just been cancelled.
            teardown = scope.launch(NonCancellable) {
                tcp?.disconnect()
                tcp = null
                udp?.disconnect()
                udp = null
            }
            callbacks.execute {
                disconnectReported = true
                listener.onConnectionDisconnected(error)
            }
        }
        return true
    }

    /** Queues a listener callback, dropped at delivery if the disconnect report ran before it. */
    private fun notify(callback: Listener.() -> Unit) {
        callbacks.execute { if (!disconnectReported) listener.callback() }
    }

    /** The server certificate must match the host the user entered, not an SRV target. */
    private fun createSocketFactory(): HumlaSSLSocketFactory {
        val cause: Exception
        val message: String
        try {
            val keyStore = params.certificate?.let { Pkcs12Certificates.load(it, params.certificatePassword) }
            return HumlaSSLSocketFactory(
                keyStore, params.certificatePassword,
                params.trustStore?.path, params.trustStore?.password, params.trustStore?.format,
                params.server.host,
            )
        } catch (e: IOException) {
            cause = e
            message = "Could not read certificate file"
        } catch (e: CertificateException) {
            cause = e
            message = "Could not read certificate"
        } catch (e: GeneralSecurityException) {
            cause = e
            message = "Could not recover keys from certificate"
        }
        throw HumlaException(message, cause, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
    }

    /** Sends a protobuf message over TCP. Can silently fail. */
    fun sendTCPMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        if (!isConnected) return
        tcp?.sendMessage(message, messageType)
    }

    /**
     * Sends over UDP, or tunnels through TCP unless [force]; the only gate on the voice path.
     * [data] is not kept after this returns, so the caller may reuse it.
     */
    fun sendUDPMessage(data: ByteArray, length: Int, force: Boolean) {
        if (!isConnected) return
        require(length <= data.size) { "Requested length $length is longer than available data length ${data.size}!" }
        if (!force && !protocol.usingUdp) {
            tcp?.sendMessage(data, length, HumlaTCPMessageType.UDPTunnel)
        } else if (!params.tunnelVoice) {
            udp?.sendMessage(data, length)
        }
    }

    override fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray) {
        // Frames that arrive during teardown are dropped; the consumer's audio path is already gone.
        if (closed) return
        if (type.isLogged) HumlaLog.v(TAG, "IN: $type")

        if (type == HumlaTCPMessageType.UDPTunnel) {
            onUDPDataReceived(data)
            return
        }
        try {
            // Parsed once, so every handler receives the same message object.
            val message = type.parse(data)
            if (message is Mumble.UserRemove) {
                for (handler in tcpHandlers) handler.onMessage(message)
                protocol.onMessage(message)
            } else {
                protocol.onMessage(message)
                for (handler in tcpHandlers) handler.onMessage(message)
            }
        } catch (e: InvalidProtocolBufferException) {
            HumlaLog.w(TAG, "Could not parse $type", e)
        } catch (e: RuntimeException) {
            // A single bad message must not end the session.
            HumlaLog.e(TAG, "Handler failed for $type", e)
        }
    }

    override fun onTCPConnectionEstablished() {
        if (!state.compareAndSet(ConnectionState.Connecting, ConnectionState.Established)) return
        protocol.onEstablished()
        notify { onConnectionEstablished() }
    }

    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>) {
        if (closed) return
        // Queued first, so the certificate prompt comes ahead of the disconnect.
        notify { onConnectionHandshakeFailed(chain) }
        disconnect()
    }

    override fun onTLSCertificateChanged(chain: Array<X509Certificate>) {
        if (closed) return
        notify { onConnectionCertificateChanged(chain) }
        disconnect()
    }

    override fun onTCPConnectionFailed(e: HumlaException) = fail(e)

    override fun onTCPConnectionDisconnect() = disconnect()

    override fun onUDPDataReceived(data: ByteArray) {
        if (closed || data.isEmpty()) return
        try {
            if (protocol.udpProtocol == UdpProtocol.PROTOBUF) onProtobufUdp(data) else onLegacyUdp(data)
        } catch (e: RuntimeException) {
            HumlaLog.e(TAG, "UDP handler failed", e)
        }
    }

    private fun onProtobufUdp(data: ByteArray) {
        when (data[0].toInt() and BYTE_MASK) {
            UdpAudioEncoder.PROTOBUF_PING -> protocol.onUdpPing(data)
            UdpAudioEncoder.PROTOBUF_AUDIO ->
                if (udpDecoder.decodeProtobuf(data, 1, data.size - 1, voicePacket)) dispatchVoice()
            // Unknown message types are dropped.
        }
    }

    private fun onLegacyUdp(data: ByteArray) {
        if ((data[0].toInt() and BYTE_MASK) ushr LEGACY_TYPE_SHIFT == HumlaUDPMessageType.UDPPing.ordinal) {
            protocol.onUdpPing(data)
        } else if (udpDecoder.decodeLegacy(data, data.size, voicePacket)) {
            dispatchVoice()
        }
    }

    private fun dispatchVoice() {
        for (handler in voiceHandlers) handler.onVoicePacket(voicePacket)
    }

    override fun onUDPConnectionError(e: Exception) {
        if (!closed) protocol.onUdpFailed(e)
    }

    override fun resyncCryptState() {
        if (!closed) protocol.requestCryptResync()
    }

    private companion object {
        const val TAG = "HumlaConnection"
    }
}
