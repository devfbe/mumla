package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.testutil.awaitUntil
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.coroutines.CoroutineContext

/**
 * Covers the TCP transport's lifecycle: which thread callbacks arrive on, that a failed or aborted
 * connect reports onTCPConnectionDisconnect exactly once, and that no coroutine of the transport
 * outlives the connection.
 *
 * The real read loop is reachable here too: a mocked SSLSocket carrying a piped stream drives
 * readFrame and the frame callbacks for real.
 */
class HumlaTCPTest {
    private val callbackExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-tcp-callbacks") }
    private val callbackDispatcher = callbackExecutor.asCoroutineDispatcher()
    private val listener = RecordingListener()
    private val socketFactory = mockk<HumlaSSLSocketFactory>()
    private var tcp: HumlaTCP? = null

    private class RecordingListener : HumlaTCP.TCPConnectionListener {
        val events = LinkedBlockingQueue<Pair<String, String>>()
        val disconnects = AtomicInteger()
        override fun onTCPConnectionEstablished() { record("established") }
        override fun onTLSHandshakeFailed(chain: Array<X509Certificate>) { record("handshakeFailed") }
        override fun onTLSCertificateChanged(chain: Array<X509Certificate>) { record("certificateChanged") }
        override fun onTCPConnectionFailed(e: HumlaException) { record("failed") }
        override fun onTCPConnectionDisconnect() { disconnects.incrementAndGet(); record("disconnect") }
        override fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray) { record("message") }
        private fun record(name: String) { events.add(name to Thread.currentThread().name) }
        fun next(): Pair<String, String> =
            events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no callback within 5s")
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        tcp?.disconnect()
        try {
            awaitUntil(description = "no coroutine of the transport left by this test") { tcp?.isFinished != false }
        } finally {
            scopes.forEach { it.cancel() }
            callbackDispatcher.close()
            unmockkAll()
        }
    }

    /** A scope like the connection's, dispatching the callbacks on [dispatcher]. */
    private fun scopeOn(dispatcher: CoroutineDispatcher) =
        CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }

    private fun newTransport(
        dispatcher: CoroutineDispatcher = callbackDispatcher,
        scope: CoroutineScope = scopeOn(dispatcher),
    ) = HumlaTCP(socketFactory, scope).also { it.setTCPConnectionListener(listener); tcp = it }

    private fun awaitFinished(transport: HumlaTCP) =
        awaitUntil(description = "every coroutine of the transport finished") { transport.isFinished }

    /** Waits until everything already queued on the callback thread has been delivered. */
    private fun drainCallbacks() {
        callbackExecutor.submit {}.get(5, TimeUnit.SECONDS)
    }

    /** Counts down as soon as the read thread is inside a blocking read on the socket. */
    private class Reading(source: InputStream, private val entered: CountDownLatch) : FilterInputStream(source) {
        override fun read(): Int { entered.countDown(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { entered.countDown(); return super.read(b, off, len) }
    }

    /**
     * Queues the callbacks for the test to run, but runs [beforeQueueing] with the running dispatch
     * count first - between post() deciding to deliver and the callback being queued. [posts] is the
     * same count afterwards.
     */
    private class HookedDispatcher(private val beforeQueueing: (Int) -> Unit) : CoroutineDispatcher() {
        val posts = AtomicInteger()
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            beforeQueueing(posts.incrementAndGet())
            queue += block
        }

        fun runQueued() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    @Test
    fun aFailedConnectReportsFailureThenExactlyOneDisconnectOnTheCallbackThread() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)

        assertThat(listener.next()).isEqualTo("failed" to "test-tcp-callbacks")
        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        awaitUntil(description = "the transport stopped") { !transport.isRunning }
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /** Makes the TLS handshake fail with [failure] as the trust verdict. */
    private fun failHandshakeWith(failure: TrustFailure) {
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.startHandshake() } throws SSLHandshakeException("rejected")
        every { socketFactory.createSocket(any(), any()) } returns socket
        every { socketFactory.serverChain } returns arrayOf(mockk<X509Certificate>())
        every { socketFactory.trustFailure } returns failure
    }

    @Test
    fun anUntrustedCertificateAsksForTrust() {
        failHandshakeWith(TrustFailure.UNTRUSTED)
        newTransport().connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("handshakeFailed")
    }

    @Test
    fun aChangedPinnedCertificateIsReportedAsSuch() {
        failHandshakeWith(TrustFailure.CHANGED)
        newTransport().connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("certificateChanged")
    }

    @Test
    fun aSecondConnectWhileRunningIsRefused() {
        val gate = CountDownLatch(1)
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); throw IOException("no route") }
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)
        try {
            assertThrows(ConnectException::class.java) { transport.connect("example.invalid", 64738, false) }
        } finally {
            gate.countDown()
        }
        assertThat(listener.next()).isEqualTo("failed" to "test-tcp-callbacks")
        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
    }

    /**
     * disconnect() while the socket is still being built: the socket must be closed and the single
     * disconnect callback must arrive, with no error reported for the user's own request.
     */
    @Test
    fun aDisconnectDuringConnectClosesTheSocketAndReportsOneDisconnect() {
        val gate = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.close() } answers { closed.countDown() }
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); socket }
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)
        transport.disconnect()
        gate.countDown()

        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
        awaitUntil(description = "the transport stopped") { !transport.isRunning }
        awaitFinished(transport)
        drainCallbacks() // a barrier, so "nothing else arrived" cannot pass by being early
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /**
     * HumlaSSLSocketFactory.createSocket() connects with no timeout, so a blackholed server keeps
     * the read thread blocked with no socket for disconnect() to close. The caller must still be
     * told it is disconnected (the session releases its wake lock on that callback), and exactly
     * once when the read loop finally unwinds.
     */
    @Test
    fun aDisconnectIsReportedEvenWhileTheConnectAttemptIsStillBlocked() {
        val gate = CountDownLatch(1)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); socket }
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)
        try {
            transport.disconnect()
            assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        } finally {
            gate.countDown() // always let the read thread unwind, however the assertion went
        }

        awaitFinished(transport)
        drainCallbacks() // a barrier, so "nothing else arrived" cannot pass by being early
        assertThat(listener.events).isEmpty() // the read loop did not report a second disconnect
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /** A disconnect before connect() is a no-op. */
    @Test
    fun aDisconnectBeforeConnectIsSilent() {
        val transport = newTransport()

        transport.disconnect()

        assertThat(transport.isRunning).isFalse()
        assertThat(listener.events).isEmpty()
    }

    @Test
    fun noTcpCoroutineOutlivesTheConnection() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("failed")
        awaitFinished(transport)
    }

    /**
     * The read loop parks inside readFrame unaware of a disconnect; disconnect() reports at once and
     * closes the socket only later from the writer. A frame completing in that window must not
     * be delivered after onTCPConnectionDisconnect, which is terminal.
     */
    @Test
    fun aFrameCompletingAfterTheDisconnectIsNotDelivered() {
        val reading = CountDownLatch(1)
        val toClient = PipedOutputStream()
        val fromServer = Reading(PipedInputStream(toClient, 4096), reading)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.inputStream } returns fromServer
        every { socket.outputStream } returns ByteArrayOutputStream()
        every { socketFactory.createSocket(any(), any()) } returns socket
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue() // the read thread is inside readFrame

        transport.disconnect()
        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        toClient.write(tcpFrame(HumlaTCPMessageType.Ping.ordinal)) // the server's answer only lands now
        toClient.flush()

        awaitFinished(transport)
        drainCallbacks()
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /**
     * "Terminal" is decided at delivery, not when queued: post() reads disconnectReported and
     * queues the callback as two steps, and a disconnect() in between would queue the terminal
     * callback ahead of the frame. Reproduced by disconnecting from inside the frame's own post.
     *
     * The callbacks wait in the hooked dispatcher's queue, so the delivery order below is exactly
     * the order the transport handed them over in.
     */
    @Test
    fun aFrameQueuedWhileTheDisconnectRunsIsStillNotDelivered() {
        val reading = CountDownLatch(1)
        val queuedEstablished = CountDownLatch(1)
        val toClient = PipedOutputStream()
        val fromServer = Reading(PipedInputStream(toClient, 4096), reading)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.inputStream } returns fromServer
        every { socket.outputStream } returns ByteArrayOutputStream()
        every { socketFactory.createSocket(any(), any()) } returns socket
        lateinit var transport: HumlaTCP
        // Post 1 is onTCPConnectionEstablished, post 2 the frame, post 3 the disconnect the hook
        // itself triggers - after the frame passed the check in post(), before it is queued.
        val dispatcher = HookedDispatcher { post ->
            when (post) {
                1 -> queuedEstablished.countDown()
                2 -> transport.disconnect()
            }
        }
        transport = newTransport(dispatcher)

        transport.connect("example.invalid", 64738, false)
        assertThat(queuedEstablished.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue() // the read thread is inside readFrame
        toClient.write(tcpFrame(HumlaTCPMessageType.Ping.ordinal))
        toClient.flush()

        awaitFinished(transport)
        dispatcher.runQueued()
        val test = Thread.currentThread().name
        assertThat(listener.next()).isEqualTo("established" to test)
        assertThat(listener.next()).isEqualTo("disconnect" to test)
        assertThat(listener.events).isEmpty() // the frame was queued behind the disconnect, not delivered
        assertThat(listener.disconnects.get()).isEqualTo(1)
        // Pins the numbering the hook keys on.
        assertThat(dispatcher.posts.get()).isEqualTo(3)
    }

    @Test
    fun aFinishedTransportRefusesASecondConnect() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next().first).isEqualTo("failed")
        assertThat(listener.next().first).isEqualTo("disconnect")
        awaitFinished(transport)

        assertThrows(ConnectException::class.java) { transport.connect("example.invalid", 64738, false) }
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    @Test
    fun messagesQueuedBeforeADisconnectAreWrittenBeforeTheSocketCloses() {
        val written = ByteArrayOutputStream()
        val writtenWhenClosed = AtomicInteger(-1)
        val toClient = PipedOutputStream()
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.inputStream } returns PipedInputStream(toClient, 64)
        every { socket.outputStream } returns written
        every { socket.close() } answers { writtenWhenClosed.compareAndSet(-1, written.size()); toClient.close() }
        every { socketFactory.createSocket(any(), any()) } returns socket
        val transport = newTransport()
        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")

        transport.sendMessage(byteArrayOf(1, 2, 3), 3, HumlaTCPMessageType.UDPTunnel)
        transport.disconnect()

        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        awaitFinished(transport)
        assertThat(writtenWhenClosed.get()).isEqualTo(2 + 4 + 3)
    }

    /**
     * Cancelling the scope - what the connection's disconnect does - closes the socket without a
     * disconnect() on the transport, and nothing more is delivered.
     */
    @Test
    fun cancellingTheScopeClosesTheSocketAndDeliversNothingMore() {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val toClient = PipedOutputStream()
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.inputStream } returns Reading(PipedInputStream(toClient, 64), reading)
        every { socket.outputStream } returns ByteArrayOutputStream()
        every { socket.close() } answers { closed.countDown(); toClient.close() }
        every { socketFactory.createSocket(any(), any()) } returns socket
        val scope = scopeOn(callbackDispatcher)
        val transport = newTransport(scope = scope)
        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue()

        scope.cancel()

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
        awaitFinished(transport)
        drainCallbacks()
        assertThat(listener.events).isEmpty()
    }

    @Test
    fun aConnectIntoACancelledScopeOpensNoSocket() {
        val scope = scopeOn(callbackDispatcher).also { it.cancel() }
        val transport = newTransport(scope = scope)

        transport.connect("example.invalid", 64738, false)

        awaitFinished(transport)
        verify(exactly = 0) { socketFactory.createSocket(any(), any()) }
        drainCallbacks()
        assertThat(listener.events).isEmpty()
    }

    /**
     * The voice path hands over one reused packet buffer; the send thread writes later, so the
     * transport must copy it before returning.
     */
    @Test
    fun aTunnelledPacketIsCopiedSoTheCallerMayReuseItsBuffer() {
        val written = ByteArrayOutputStream()
        val writing = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val output = object : java.io.OutputStream() {
            override fun write(b: Int) {
                writing.countDown()
                gate.await(5, TimeUnit.SECONDS)
                written.write(b)
            }
        }
        val toClient = PipedOutputStream()
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.inputStream } returns PipedInputStream(toClient, 64)
        every { socket.outputStream } returns output
        every { socketFactory.createSocket(any(), any()) } returns socket
        val transport = newTransport()
        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")

        val packet = byteArrayOf(1, 2, 3, 4)
        transport.sendMessage(packet, 3, HumlaTCPMessageType.UDPTunnel)
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue()
        packet.fill(9) // the caller's next packet, while the first is still being written
        gate.countDown()

        awaitUntil(description = "the whole frame is written") { written.size() == 2 + 4 + 3 }
        assertThat(written.toByteArray().takeLast(3)).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        toClient.close()
    }
}
