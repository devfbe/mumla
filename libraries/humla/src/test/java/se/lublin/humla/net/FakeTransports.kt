package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.protobuf.Message
import se.lublin.humla.util.HumlaException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TCP transport that never opens a socket; tests push frames through it as the read thread would.
 *
 * It promises exactly what [TcpTransport] promises and nothing more. In particular [disconnect]
 * posts the terminal callback through [callbackHandler], because that is what HumlaTCP.disconnect
 * does - it reports the disconnect itself rather than waiting for the read loop, which may be stuck
 * in a connect that has no timeout. A fake that called the listener inline instead would hide
 * whether the connection's own looper is still accepting work at that moment.
 */
class FakeTcpTransport(private val callbackHandler: Handler) : TcpTransport {
    @Volatile var listener: HumlaTCP.TCPConnectionListener? = null
    @Volatile var connectThread: String? = null
    @Volatile var connectHost: String? = null
    @Volatile var connectPort: Int = 0
    /** Recorded because nothing read it back: the connection passes it and no test looked. */
    @Volatile var connectUseTor: Boolean = false
    @Volatile var disconnectCalls = 0
    val sent = CopyOnWriteArrayList<HumlaTCPMessageType>()

    /**
     * Called from [sendMessage], on whichever thread sends. A test uses it to park the protocol
     * thread in the middle of a message handler, which is the only way to make the interleaving
     * "the user disconnected while a handler was still running" a fact rather than a race.
     */
    @Volatile var onSend: ((HumlaTCPMessageType) -> Unit)? = null

    /**
     * Whether the terminal callback [disconnect] posts was accepted by the callback handler. False
     * means the looper had already quit and the real transport would have handed its token back,
     * with no second route to report the disconnect from.
     */
    val terminalPostAccepted = AtomicBoolean(false)

    override val isRunning: Boolean get() = connectThread != null && disconnectCalls == 0
    override fun setTCPConnectionListener(listener: HumlaTCP.TCPConnectionListener?) { this.listener = listener }
    override fun connect(host: String, port: Int, useTor: Boolean) {
        connectHost = host
        connectPort = port
        connectUseTor = useTor
        // Published last, like disconnectCalls below: tests wait on this field, so everything the
        // call recorded has to be in place before a waiter can see it.
        connectThread = Thread.currentThread().name
    }
    override fun sendMessage(message: Message, messageType: HumlaTCPMessageType) { record(messageType) }
    override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) { record(messageType) }

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
        // Published last, on purpose: tests wait on this counter, so a counter they can see has to
        // imply everything this call already did. Written the other way round, the test that pins
        // the terminal post passes whenever the waiter happens to be slower than one field write --
        // measured, 200 ms between the two lines is enough to fail it.
        disconnectCalls = 1
    }

    fun simulateConnected() = post { it.onTCPConnectionEstablished() }
    fun simulateMessage(type: HumlaTCPMessageType, data: ByteArray) = post { it.onTCPMessageReceived(type, data.size, data) }
    fun simulateFailure(e: HumlaException) = post { it.onTCPConnectionFailed(e) }
    fun simulateHandshakeFailure(chain: Array<X509Certificate>) = post { it.onTLSHandshakeFailed(chain) }

    /**
     * The read loop's finally block reporting the closed socket. Called **directly**, not through
     * [callbackHandler]: in production this runs on the socket thread after [disconnect], by which
     * time the protocol looper may already have been quit, so posting it would silently drop the
     * call and the de-duplication it is meant to exercise would never be reached.
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
 * It holds the [CryptState] the factory handed it, which is not decoration: `mUiGood` grows in
 * exactly one place, `CryptState.decrypt()`, reachable only from HumlaUDP's receive loop. A fake
 * that dropped the crypt state left `localGood` constant zero in every connection test, and with
 * it three of the six decisions in [UdpHealthMonitor] unreachable end-to-end - SWITCH_TO_TCP_SEND
 * outright, and RESTORE_UDP at any threshold a caller would actually use, which is why the one
 * restore test had to construct restoreThreshold = -1. [simulateDatagram] counts the packet the
 * way a successful decrypt would, so the production threshold is drivable.
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
     * Recorded for the same reason [FakeTcpTransport.connectUseTor] is: this fake counted the call
     * and dropped both arguments, so `transport.connect("", 0)` in startUdp() survived the whole
     * suite. InetAddress.getByName("") resolves to loopback instead of failing, so the production
     * consequence of that line going wrong is a call that is silently sent to 127.0.0.1.
     */
    @Volatile var connectHost: String? = null
    @Volatile var connectPort: Int = 0
    val sent = CopyOnWriteArrayList<ByteArray>()

    override val isRunning: Boolean get() = connectCalls.get() > disconnectCalls.get()
    override fun connect(host: String, port: Int) {
        connectHost = host
        connectPort = port
        connectThread = Thread.currentThread().name
        // Published last: tests wait on this counter, so everything the call recorded has to be in
        // place before a waiter can see it.
        connectCalls.incrementAndGet()
    }
    override fun sendMessage(data: ByteArray, length: Int) { sent += data.copyOf(length) }
    override fun disconnect() { disconnectCalls.incrementAndGet() }

    fun simulateError(e: Exception) = callbackHandler.post { listener.onUDPConnectionError(e) }

    /**
     * A datagram that decrypted. The counter is raised here rather than in the test, because in
     * production it is raised by the same call that produces the buffer - a test that had to
     * remember to bump it separately would be free to forget, which is the state this fake was in.
     */
    fun simulateDatagram(data: ByteArray) = callbackHandler.post {
        cryptState.mUiGood++
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
     * One entry per callback, in delivery order. The listener contract calls
     * onConnectionDisconnected terminal, and "terminal" is a statement about order that a set of
     * per-callback counters cannot express - which is how a synchronized and a warning delivered
     * behind the disconnect report both went unnoticed.
     */
    val events = CopyOnWriteArrayList<String>()

    val established = AtomicInteger()
    val synchronizedCount = AtomicInteger()
    val disconnects = CopyOnWriteArrayList<HumlaException?>()
    val warnings = CopyOnWriteArrayList<ConnectionWarning>()
    val handshakeFailures = CopyOnWriteArrayList<Array<X509Certificate>>()

    /**
     * One entry per callback. The looper is recorded, not the thread name: "the callback ran on the
     * main looper" is the property the listener contract states, and it holds no matter what the
     * test runner happens to have named its thread.
     */
    val callbackLoopers = CopyOnWriteArrayList<Looper?>()

    val allOnMainLooper: Boolean get() = callbackLoopers.isNotEmpty() && callbackLoopers.all { it == Looper.getMainLooper() }

    override fun onConnectionEstablished() { record("established"); established.incrementAndGet() }
    override fun onConnectionSynchronized() { record("synchronized"); synchronizedCount.incrementAndGet() }
    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) { record("handshakeFailed"); handshakeFailures += chain }
    override fun onConnectionDisconnected(e: HumlaException?) { record("disconnected"); disconnects += e }
    override fun onConnectionWarning(warning: ConnectionWarning) { record("warning:$warning"); warnings += warning }

    private fun record(event: String) {
        callbackLoopers += Looper.myLooper()
        events += event
    }
}
