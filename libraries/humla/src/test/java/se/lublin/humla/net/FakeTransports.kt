package se.lublin.humla.net

import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.lublin.humla.exception.HumlaException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TCP transport that never opens a socket; tests push frames through it as the read thread would.
 *
 * Like HumlaTCP, every callback, including the terminal one [disconnect] reports, is dispatched on
 * [scope] rather than called inline. [inProtocolContext] tells whether the caller runs there.
 */
class FakeTcpTransport(
    private val scope: CoroutineScope,
    private val inProtocolContext: () -> Boolean,
) : TcpTransport {
    @Volatile var listener: HumlaTCP.TCPConnectionListener? = null

    /** Whether [connect] ran on the protocol context; null before it ran. */
    @Volatile var connectedInProtocolContext: Boolean? = null
    val isConnectCalled: Boolean get() = connectedInProtocolContext != null
    @Volatile var connectHost: String? = null
    @Volatile var connectPort: Int = 0
    @Volatile var connectUseTor: Boolean = false
    @Volatile var disconnectCalls = 0
    val sent = CopyOnWriteArrayList<HumlaTCPMessageType>()

    /** The protobuf messages sent, in order; raw tunnelled frames are not included. */
    val sentMessages = CopyOnWriteArrayList<MessageLite>()

    /** The raw frames sent (the tunnelled voice packets), in order. */
    val sentFrames = CopyOnWriteArrayList<ByteArray>()

    /**
     * Called from [sendMessage], on whichever thread sends. Lets a test act while the protocol
     * context is in the middle of a message handler.
     */
    @Volatile var onSend: ((HumlaTCPMessageType) -> Unit)? = null

    /** Whether the terminal callback [disconnect] dispatched actually ran. */
    val terminalDelivered = AtomicBoolean(false)

    override val isRunning: Boolean get() = isConnectCalled && disconnectCalls == 0
    override fun setTCPConnectionListener(listener: HumlaTCP.TCPConnectionListener?) { this.listener = listener }
    override fun connect(host: String, port: Int, useTor: Boolean) {
        connectHost = host
        connectPort = port
        connectUseTor = useTor
        // Published last: tests wait on this field, so everything else must be in place first.
        connectedInProtocolContext = inProtocolContext()
    }
    override fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        sentMessages += message
        record(messageType)
    }
    override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) {
        sentFrames += data.copyOf(length)
        record(messageType)
    }

    private fun record(messageType: HumlaTCPMessageType) {
        sent += messageType
        onSend?.invoke(messageType)
    }

    override fun disconnect() {
        if (disconnectCalls > 0) {
            disconnectCalls++ // the real transport returns early once it is not running
            return
        }
        val l = listener
        if (l != null) scope.launch { terminalDelivered.set(true); l.onTCPConnectionDisconnect() }
        // Published last: tests wait on this counter, so it must imply everything this call did.
        disconnectCalls = 1
    }

    fun simulateConnected() = post { it.onTCPConnectionEstablished() }
    fun simulateMessage(type: HumlaTCPMessageType, data: ByteArray) =
        post { it.onTCPMessageReceived(type, data.size, data) }
    fun simulateFailure(e: HumlaException) = post { it.onTCPConnectionFailed(e) }
    fun simulateHandshakeFailure(chain: Array<X509Certificate>) = post { it.onTLSHandshakeFailed(chain) }

    /**
     * The read loop's finally block reporting the closed socket. Called **directly**, not through
     * [scope]: in production the scope may already have been cancelled by then.
     */
    fun simulateSocketClosed() {
        checkNotNull(listener) { "connect() was not called" }.onTCPConnectionDisconnect()
    }

    private fun post(block: (HumlaTCP.TCPConnectionListener) -> Unit) {
        val l = checkNotNull(listener) { "connect() was not called" }
        scope.launch { block(l) }
    }
}

/**
 * A UDP transport that never opens a socket.
 *
 * It keeps the [CryptState] the factory handed it: `good` only grows in `CryptState.decrypt()`,
 * so [simulateDatagram] counts the packet the way a successful decrypt would, which makes the
 * [UdpHealthMonitor] decisions that depend on `localGood` reachable.
 */
class FakeUdpTransport(
    private val scope: CoroutineScope,
    private val listener: HumlaUDP.UDPConnectionListener,
    private val cryptState: CryptState,
    private val inProtocolContext: () -> Boolean,
) : UdpTransport {
    val connectCalls = AtomicInteger()
    val disconnectCalls = AtomicInteger()

    /** Whether [connect] ran on the protocol context; null before it ran. */
    @Volatile var connectedInProtocolContext: Boolean? = null
    /**
     * Recorded so tests can check where UDP is pointed; InetAddress.getByName("") would silently
     * resolve to loopback.
     */
    @Volatile var connectHost: String? = null
    @Volatile var connectPort: Int = 0
    val sent = CopyOnWriteArrayList<ByteArray>()

    override val isRunning: Boolean get() = connectCalls.get() > disconnectCalls.get()
    override fun connect(host: String, port: Int) {
        connectHost = host
        connectPort = port
        connectedInProtocolContext = inProtocolContext()
        // Published last: tests wait on this counter.
        connectCalls.incrementAndGet()
    }
    override fun sendMessage(data: ByteArray, length: Int) { sent += data.copyOf(length) }
    override fun disconnect() { disconnectCalls.incrementAndGet() }

    fun simulateError(e: Exception) = scope.launch { listener.onUDPConnectionError(e) }

    /** A datagram that decrypted; raises the crypt state's good counter like a real decrypt. */
    fun simulateDatagram(data: ByteArray) = scope.launch {
        cryptState.good++
        listener.onUDPDataReceived(data)
    }
}

/** [inProtocolContext] tells the fakes whether their caller runs on the protocol context. */
class FakeTransports(private val inProtocolContext: () -> Boolean = { true }) : HumlaConnection.TransportFactory {
    val tcps = CopyOnWriteArrayList<FakeTcpTransport>()
    val udps = CopyOnWriteArrayList<FakeUdpTransport>()

    override fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
        FakeTcpTransport(scope, inProtocolContext).also { tcps += it }

    override fun createUdp(
        cryptState: CryptState,
        listener: HumlaUDP.UDPConnectionListener,
        scope: CoroutineScope,
    ): UdpTransport =
        FakeUdpTransport(scope, listener, cryptState, inProtocolContext).also { udps += it }
}

/** [onCallbackThread] tells whether a callback runs where the connection's callbacks belong. */
class RecordingConnectionListener(private val onCallbackThread: () -> Boolean = { true }) : HumlaConnection.Listener {
    /**
     * One entry per callback, in delivery order, so tests can check that onConnectionDisconnected
     * is terminal.
     */
    val events = CopyOnWriteArrayList<String>()

    val established = AtomicInteger()
    val synchronizedCount = AtomicInteger()
    val disconnects = CopyOnWriteArrayList<HumlaException?>()
    val warnings = CopyOnWriteArrayList<ConnectionWarning>()
    val handshakeFailures = CopyOnWriteArrayList<Array<X509Certificate>>()
    val certificateChanges = CopyOnWriteArrayList<Array<X509Certificate>>()

    /** One entry per callback: whether it ran on the callback thread. */
    private val onCallbackThreads = CopyOnWriteArrayList<Boolean>()

    val allOnCallbackThread: Boolean get() = onCallbackThreads.isNotEmpty() && onCallbackThreads.all { it }

    override fun onConnectionEstablished() { record("established"); established.incrementAndGet() }
    override fun onConnectionSynchronized() { record("synchronized"); synchronizedCount.incrementAndGet() }
    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) {
        record("handshakeFailed")
        handshakeFailures += chain
    }
    override fun onConnectionCertificateChanged(chain: Array<X509Certificate>) {
        record("certificateChanged")
        certificateChanges += chain
    }
    override fun onConnectionDisconnected(e: HumlaException?) { record("disconnected"); disconnects += e }
    override fun onConnectionWarning(warning: ConnectionWarning) { record("warning:$warning"); warnings += warning }

    private fun record(event: String) {
        onCallbackThreads += onCallbackThread()
        events += event
    }
}
