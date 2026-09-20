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
    @Volatile var disconnectCalls = 0
    val sent = CopyOnWriteArrayList<HumlaTCPMessageType>()

    /**
     * Whether the terminal callback [disconnect] posts was accepted by the callback handler. False
     * means the looper had already quit and the real transport would have handed its token back,
     * with no second route to report the disconnect from.
     */
    val terminalPostAccepted = AtomicBoolean(false)

    override val isRunning: Boolean get() = connectThread != null && disconnectCalls == 0
    override fun setTCPConnectionListener(listener: HumlaTCP.TCPConnectionListener?) { this.listener = listener }
    override fun connect(host: String, port: Int, useTor: Boolean) {
        connectThread = Thread.currentThread().name
        connectHost = host
        connectPort = port
    }
    override fun sendMessage(message: Message, messageType: HumlaTCPMessageType) { sent += messageType }
    override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) { sent += messageType }

    override fun disconnect() {
        val first = disconnectCalls == 0
        disconnectCalls++
        if (!first) return // the real transport's disconnect() returns early once it is not running
        val l = listener ?: return
        if (callbackHandler.post { l.onTCPConnectionDisconnect() }) terminalPostAccepted.set(true)
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

class FakeUdpTransport(
    private val callbackHandler: Handler,
    private val listener: HumlaUDP.UDPConnectionListener,
) : UdpTransport {
    val connectCalls = AtomicInteger()
    val disconnectCalls = AtomicInteger()
    @Volatile var connectThread: String? = null
    val sent = CopyOnWriteArrayList<ByteArray>()

    override val isRunning: Boolean get() = connectCalls.get() > disconnectCalls.get()
    override fun connect(host: String, port: Int) {
        connectThread = Thread.currentThread().name
        connectCalls.incrementAndGet()
    }
    override fun sendMessage(data: ByteArray, length: Int) { sent += data.copyOf(length) }
    override fun disconnect() { disconnectCalls.incrementAndGet() }

    fun simulateError(e: Exception) = callbackHandler.post { listener.onUDPConnectionError(e) }
    fun simulateDatagram(data: ByteArray) = callbackHandler.post { listener.onUDPDataReceived(data) }
}

class FakeTransports : HumlaConnection.TransportFactory {
    val tcps = CopyOnWriteArrayList<FakeTcpTransport>()
    val udps = CopyOnWriteArrayList<FakeUdpTransport>()

    override fun createTcp(socketFactory: HumlaSSLSocketFactory, callbackHandler: Handler): TcpTransport =
        FakeTcpTransport(callbackHandler).also { tcps += it }

    override fun createUdp(cryptState: CryptState, listener: HumlaUDP.UDPConnectionListener, callbackHandler: Handler): UdpTransport =
        FakeUdpTransport(callbackHandler, listener).also { udps += it }
}

class RecordingConnectionListener : HumlaConnection.HumlaConnectionListener {
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

    override fun onConnectionEstablished() { record(); established.incrementAndGet() }
    override fun onConnectionSynchronized() { record(); synchronizedCount.incrementAndGet() }
    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) { record(); handshakeFailures += chain }
    override fun onConnectionDisconnected(e: HumlaException?) { record(); disconnects += e }
    override fun onConnectionWarning(warning: ConnectionWarning) { record(); warnings += warning }

    private fun record() { callbackLoopers += Looper.myLooper() }
}
