package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.protobuf.MessageLite
import se.lublin.humla.util.HumlaException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TCP transport that never opens a socket; tests push frames through it as the read thread would.
 *
 * Like HumlaTCP, [disconnect] posts the terminal callback through [callbackHandler] rather than
 * calling the listener inline, so a test can see whether the connection's looper still accepts work.
 */
class FakeTcpTransport(private val callbackHandler: Handler) : TcpTransport {
    @Volatile var listener: HumlaTCP.TCPConnectionListener? = null
    @Volatile var connectThread: String? = null
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
     * Called from [sendMessage], on whichever thread sends. Lets a test park the protocol thread in
     * the middle of a message handler.
     */
    @Volatile var onSend: ((HumlaTCPMessageType) -> Unit)? = null

    /**
     * Whether the terminal callback [disconnect] posts was accepted by the callback handler. False
     * means the looper had already quit.
     */
    val terminalPostAccepted = AtomicBoolean(false)

    override val isRunning: Boolean get() = connectThread != null && disconnectCalls == 0
    override fun setTCPConnectionListener(listener: HumlaTCP.TCPConnectionListener?) { this.listener = listener }
    override fun connect(host: String, port: Int, useTor: Boolean) {
        connectHost = host
        connectPort = port
        connectUseTor = useTor
        // Published last: tests wait on this field, so everything else must be in place first.
        connectThread = Thread.currentThread().name
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
        if (l != null && callbackHandler.post { l.onTCPConnectionDisconnect() }) {
            terminalPostAccepted.set(true)
        }
        // Published last: tests wait on this counter, so it must imply everything this call did.
        disconnectCalls = 1
    }

    fun simulateConnected() = post { it.onTCPConnectionEstablished() }
    fun simulateMessage(type: HumlaTCPMessageType, data: ByteArray) = post { it.onTCPMessageReceived(type, data.size, data) }
    fun simulateFailure(e: HumlaException) = post { it.onTCPConnectionFailed(e) }
    fun simulateHandshakeFailure(chain: Array<X509Certificate>) = post { it.onTLSHandshakeFailed(chain) }
    fun simulateCertificateChanged(chain: Array<X509Certificate>) = post { it.onTLSCertificateChanged(chain) }

    /**
     * The read loop's finally block reporting the closed socket. Called **directly**, not through
     * [callbackHandler]: in production the protocol looper may already have quit by then.
     */
    fun simulateSocketClosed() {
        checkNotNull(listener) { "connect() was not called" }.onTCPConnectionDisconnect()
    }

    private fun post(block: (HumlaTCP.TCPConnectionListener) -> Unit) {
        val l = checkNotNull(listener) { "connect() was not called" }
        check(callbackHandler.post { block(l) }) { "callback looper is no longer accepting work" }
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
    private val callbackHandler: Handler,
    private val listener: HumlaUDP.UDPConnectionListener,
    private val cryptState: CryptState = CryptState(),
) : UdpTransport {
    val connectCalls = AtomicInteger()
    val disconnectCalls = AtomicInteger()
    @Volatile var connectThread: String? = null
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
        connectThread = Thread.currentThread().name
        // Published last: tests wait on this counter.
        connectCalls.incrementAndGet()
    }
    override fun sendMessage(data: ByteArray, length: Int) { sent += data.copyOf(length) }
    override fun disconnect() { disconnectCalls.incrementAndGet() }

    fun simulateError(e: Exception) = callbackHandler.post { listener.onUDPConnectionError(e) }

    /** A datagram that decrypted; raises the crypt state's good counter like a real decrypt. */
    fun simulateDatagram(data: ByteArray) = callbackHandler.post {
        cryptState.good++
        listener.onUDPDataReceived(data)
    }
}

class FakeTransports : HumlaConnection.TransportFactory {
    val tcps = CopyOnWriteArrayList<FakeTcpTransport>()
    val udps = CopyOnWriteArrayList<FakeUdpTransport>()

    override fun createTcp(socketFactory: HumlaSSLSocketFactory, callbackHandler: Handler): TcpTransport =
        FakeTcpTransport(callbackHandler).also { tcps += it }

    override fun createUdp(cryptState: CryptState, listener: HumlaUDP.UDPConnectionListener, callbackHandler: Handler): UdpTransport =
        FakeUdpTransport(callbackHandler, listener, cryptState).also { udps += it }
}

class RecordingConnectionListener : HumlaConnection.HumlaConnectionListener {
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

    /** One entry per callback: the looper it ran on. */
    val callbackLoopers = CopyOnWriteArrayList<Looper?>()

    val allOnMainLooper: Boolean get() = callbackLoopers.isNotEmpty() && callbackLoopers.all { it == Looper.getMainLooper() }

    override fun onConnectionEstablished() { record("established"); established.incrementAndGet() }
    override fun onConnectionSynchronized() { record("synchronized"); synchronizedCount.incrementAndGet() }
    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) { record("handshakeFailed"); handshakeFailures += chain }
    override fun onConnectionCertificateChanged(chain: Array<X509Certificate>) { record("certificateChanged"); certificateChanges += chain }
    override fun onConnectionDisconnected(e: HumlaException?) { record("disconnected"); disconnects += e }
    override fun onConnectionWarning(warning: ConnectionWarning) { record("warning:$warning"); warnings += warning }

    private fun record(event: String) {
        callbackLoopers += Looper.myLooper()
        events += event
    }
}
