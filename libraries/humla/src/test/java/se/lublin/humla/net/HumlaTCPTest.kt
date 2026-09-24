package se.lublin.humla.net

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.HumlaException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Covers the TCP transport's lifecycle: which thread callbacks arrive on, that a failed or aborted
 * connect reports onTCPConnectionDisconnect exactly once, and that no socket thread outlives the
 * connection.
 *
 * The real read loop is reachable here too: a mocked SSLSocket carrying a piped stream drives
 * readFrame and the frame callbacks for real.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaTCPTest {
    private val callbackThread = HandlerThread("test-tcp-callbacks").apply { start() }
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

    /**
     * Threads already running when this test started. Thread.getAllStackTraces() is JVM-global, so
     * this keeps a thread leaked by an earlier test from failing this one.
     */
    private val preexistingThreads = Thread.getAllStackTraces().keys.toSet()

    @After
    fun tearDown() {
        tcp?.disconnect()
        try {
            awaitUntil(description = "no live thread named humla-tcp-* left by this test") {
                liveThreadNames("humla-tcp-").isEmpty()
            }
        } finally {
            callbackThread.quitSafely()
            unmockkAll()
        }
    }

    private fun newTransport(handler: Handler? = null) =
        (if (handler == null) HumlaTCP(socketFactory) else HumlaTCP(socketFactory, handler))
            .also { it.setTCPConnectionListener(listener); tcp = it }

    /** Only threads this test started; see [preexistingThreads]. */
    private fun liveThreadNames(prefix: String) = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.startsWith(prefix) && it !in preexistingThreads }
        .map { it.name }

    /** Waits until everything already queued on the callback handler has been delivered. */
    private fun drainCallbacks() {
        val drained = CountDownLatch(1)
        Handler(callbackThread.looper).post { drained.countDown() }
        assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /** Counts down as soon as the read thread is inside a blocking read on the socket. */
    private class Reading(source: InputStream, private val entered: CountDownLatch) : FilterInputStream(source) {
        override fun read(): Int { entered.countDown(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { entered.countDown(); return super.read(b, off, len) }
    }

    /**
     * Delivers for real, but runs [beforeQueueing] with the running post count first - between
     * post() capturing the epoch and handing the callback to the handler (Handler.post is final, so
     * the hook sits on the funnel every post goes through). [posts] is the same count afterwards.
     */
    private class HookedHandler(looper: Looper, private val beforeQueueing: (Int) -> Unit) : Handler(looper) {
        val posts = AtomicInteger()
        override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
            beforeQueueing(posts.incrementAndGet())
            return super.sendMessageAtTime(msg, uptimeMillis)
        }
    }

    private fun frame(type: HumlaTCPMessageType, payload: ByteArray = ByteArray(0)): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeShort(type.ordinal); writeInt(payload.size); write(payload) }
        return bytes.toByteArray()
    }

    @Test
    fun aFailedConnectReportsFailureThenExactlyOneDisconnectOnTheCallbackHandler() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport(Handler(callbackThread.looper))

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
        newTransport(Handler(callbackThread.looper)).connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("handshakeFailed")
    }

    @Test
    fun aChangedPinnedCertificateIsReportedAsSuch() {
        failHandshakeWith(TrustFailure.CHANGED)
        newTransport(Handler(callbackThread.looper)).connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("certificateChanged")
    }

    @Test
    fun theDefaultHandlerDeliversOnTheMainLooper() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)

        awaitUntil(description = "the callbacks reached the main looper queue") {
            !shadowOf(Looper.getMainLooper()).isIdle
        }
        assertThat(listener.events).isEmpty() // nothing was delivered on the read thread
        // Wait for the event, not for isRunning: the read loop clears that flag just before it
        // posts the disconnect.
        awaitUntil(description = "the disconnect reached the main looper") {
            shadowOf(Looper.getMainLooper()).idle()
            listener.disconnects.get() == 1
        }
        assertThat(transport.isRunning).isFalse()
        val main = Looper.getMainLooper().thread.name
        assertThat(listener.next()).isEqualTo("failed" to main)
        assertThat(listener.next()).isEqualTo("disconnect" to main)
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    @Test
    fun aSecondConnectWhileRunningIsRefused() {
        val gate = CountDownLatch(1)
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); throw IOException("no route") }
        val transport = newTransport(Handler(callbackThread.looper))

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
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)
        transport.disconnect()
        gate.countDown()

        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
        awaitUntil(description = "the transport stopped") { !transport.isRunning }
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        drainCallbacks() // a barrier, so "nothing else arrived" cannot pass by being early
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /**
     * HumlaSSLSocketFactory.createSocket() connects with no timeout, so a blackholed server keeps
     * the read thread blocked with no socket for disconnect() to close. The caller must still be
     * told it is disconnected (HumlaService releases its wake lock on that callback), and exactly
     * once when the read loop finally unwinds.
     */
    @Test
    fun aDisconnectIsReportedEvenWhileTheConnectAttemptIsStillBlocked() {
        val gate = CountDownLatch(1)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); socket }
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)
        try {
            transport.disconnect()
            assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        } finally {
            gate.countDown() // always let the read thread unwind, however the assertion went
        }

        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        drainCallbacks() // a barrier, so "nothing else arrived" cannot pass by being early
        assertThat(listener.events).isEmpty() // the read loop did not report a second disconnect
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /** A disconnect before connect() is a no-op. */
    @Test
    fun aDisconnectBeforeConnectIsSilent() {
        val transport = newTransport(Handler(callbackThread.looper))

        transport.disconnect()

        assertThat(transport.isRunning).isFalse()
        assertThat(listener.events).isEmpty()
    }

    @Test
    fun noTcpThreadOutlivesTheConnection() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("failed")
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
    }

    /**
     * The read loop parks inside readFrame unaware of a disconnect; disconnect() reports at once and
     * closes the socket only later from the send thread. A frame completing in that window must not
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
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue() // the read thread is inside readFrame

        transport.disconnect()
        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        toClient.write(frame(HumlaTCPMessageType.Ping)) // the server's answer only lands now
        toClient.flush()

        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        drainCallbacks()
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /**
     * disconnect() clears "running" while the read thread may still be unwinding (e.g. stuck in a
     * connect with no timeout). A connect() in that window would hand the old finally the new
     * connection's disconnect token and executors, so the transport stays busy until its read loop
     * is done.
     */
    @Test
    fun aConnectIsRefusedUntilTheReadLoopHasFinishedUnwinding() {
        val gate = CountDownLatch(1)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); socket }
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)
        transport.disconnect()
        assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
        assertThat(transport.isRunning).isFalse() // reported and stopped, but not yet torn down

        try {
            assertThrows(ConnectException::class.java) { transport.connect("example.invalid", 64738, false) }
        } finally {
            gate.countDown()
        }
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        drainCallbacks()
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /**
     * inUse is released only after everything from postDisconnectOnce() down, which still belongs
     * to the ending connection. The read thread is parked *inside* the finally here, which makes the
     * position of the release observable, not just its existence.
     */
    @Test
    fun aConnectIsRefusedWhileTheReadLoopIsStillInsideItsFinally() {
        val inFinally = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Post 1 is the failure report from the catch, post 2 the disconnect from the finally.
        val handler = HookedHandler(callbackThread.looper) { post ->
            if (post == 2) {
                inFinally.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport(handler)

        transport.connect("example.invalid", 64738, false)
        assertThat(inFinally.await(5, TimeUnit.SECONDS)).isTrue()

        try {
            assertThrows(ConnectException::class.java) { transport.connect("example.invalid", 64738, false) }
        } finally {
            release.countDown() // always let the read thread unwind, however the assertion went
        }
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        drainCallbacks()
        assertThat(listener.next().first).isEqualTo("failed")
        assertThat(listener.next().first).isEqualTo("disconnect")
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /** Refusing during teardown must not turn the transport into a one-shot. */
    @Test
    fun theTransportConnectsAgainOnceTheReadLoopHasFinished() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next().first).isEqualTo("failed")
        assertThat(listener.next().first).isEqualTo("disconnect")
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }

        transport.connect("example.invalid", 64738, false)

        assertThat(listener.next().first).isEqualTo("failed")
        assertThat(listener.next().first).isEqualTo("disconnect")
        assertThat(listener.disconnects.get()).isEqualTo(2)
    }

    /**
     * Handler.post returns false once its looper has quit. The exactly-once disconnect token must
     * only be consumed by a callback that was actually queued, or nobody ever reports.
     *
     * The fake leaves the runnable unrun, as a quit looper does; running it inline would assert a
     * delivery the device never makes.
     */
    @Test
    fun aDisconnectThePostRejectsIsReportedAgainByTheReadLoop() {
        val attempts = AtomicInteger()
        val handler = mockk<Handler>()
        every { handler.post(any()) } answers {
            attempts.incrementAndGet()
            false // the looper is gone: the message was not queued, so the runnable never runs
        }
        val gate = CountDownLatch(1)
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socketFactory.createSocket(any(), any()) } answers { gate.await(); socket }
        val transport = newTransport(handler)

        transport.connect("example.invalid", 64738, false)
        transport.disconnect()
        assertThat(attempts.get()).isEqualTo(1)

        gate.countDown() // the read thread unwinds and finds the report still unclaimed
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(listener.events).isEmpty() // both attempts were refused, so nothing was delivered
        assertThat(listener.disconnects.get()).isEqualTo(0)
    }

    /**
     * "Terminal" is decided at delivery, not when queued: post() reads disconnectReported and
     * queues the callback as two steps, and a disconnect() in between would queue the terminal
     * callback ahead of the frame. Reproduced by disconnecting from inside the frame's own post.
     *
     * Robolectric's main looper is paused, so the delivery order below is exactly the order the
     * transport handed the callbacks over in.
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
        val handler = HookedHandler(Looper.getMainLooper()) { post ->
            when (post) {
                1 -> queuedEstablished.countDown()
                2 -> transport.disconnect()
            }
        }
        transport = newTransport(handler)

        transport.connect("example.invalid", 64738, false)
        assertThat(queuedEstablished.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue() // the read thread is inside readFrame
        toClient.write(frame(HumlaTCPMessageType.Ping))
        toClient.flush()

        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        shadowOf(Looper.getMainLooper()).idle()
        val main = Looper.getMainLooper().thread.name
        assertThat(listener.next()).isEqualTo("established" to main)
        assertThat(listener.next()).isEqualTo("disconnect" to main)
        assertThat(listener.events).isEmpty() // the frame was queued behind the disconnect, not delivered
        assertThat(listener.disconnects.get()).isEqualTo(1)
        // Pins the numbering the hook keys on.
        assertThat(handler.posts.get()).isEqualTo(3)
    }

    /**
     * A reconnect on the same transport must not write into the previous connection's streams:
     * between connect() and the new handshake, sendMessage must not find the old output. (On a real
     * socket such a write would just vanish; the fake keeps the bytes, which makes it visible.)
     */
    @Test
    fun aSendBetweenTwoConnectionsDoesNotReachThePreviousConnectionsStream() {
        val firstOutput = ByteArrayOutputStream()
        val toClient = PipedOutputStream()
        val first = mockk<SSLSocket>(relaxed = true)
        every { first.inputStream } returns PipedInputStream(toClient, 64)
        every { first.outputStream } returns firstOutput
        val handshaking = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val secondClosed = CountDownLatch(1)
        val second = mockk<SSLSocket>(relaxed = true)
        every { second.startHandshake() } answers { handshaking.countDown(); gate.await() }
        every { second.close() } answers { secondClosed.countDown() }
        every { socketFactory.createSocket(any(), any()) } returnsMany listOf(first, second)
        val transport = newTransport(Handler(callbackThread.looper))

        transport.connect("example.invalid", 64738, false)
        assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")
        toClient.close() // the server hangs up; the read loop unwinds and tears everything down
        assertThat(listener.next().first).isEqualTo("failed")
        assertThat(listener.next().first).isEqualTo("disconnect")
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
        assertThat(firstOutput.size()).isEqualTo(0)

        transport.connect("example.invalid", 64738, false) // the second connection parks in the handshake
        assertThat(handshaking.await(5, TimeUnit.SECONDS)).isTrue()
        transport.sendMessage(byteArrayOf(1, 2, 3), 3, HumlaTCPMessageType.Ping)
        transport.disconnect() // queued behind the send on the single send thread, so it is a barrier
        try {
            assertThat(secondClosed.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(firstOutput.size()).isEqualTo(0)
        } finally {
            gate.countDown()
        }
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
    }

    /**
     * The terminal flag belongs to the connection, not to the transport. A disconnect queued for
     * connection A can still be delivered after A released the transport and B is up; it must not
     * silence B (a later failure would arrive as a bare disconnect, which is not retried).
     */
    @Test
    fun aDisconnectDeliveredAfterTheNextConnectDoesNotSilenceIt() {
        val callbacksBlocked = CountDownLatch(1)
        val connecting = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val establishedQueued = CountDownLatch(1)
        val calls = AtomicInteger()
        val toClient = PipedOutputStream()
        val second = mockk<SSLSocket>(relaxed = true)
        every { second.inputStream } returns PipedInputStream(toClient, 4096)
        every { second.outputStream } returns ByteArrayOutputStream()
        every { socketFactory.createSocket(any(), any()) } answers {
            if (calls.incrementAndGet() == 1) {
                connecting.countDown()
                gate.await()
                throw IOException("no route")
            }
            second
        }
        // Post 1 is A's disconnect, post 2 is B's onTCPConnectionEstablished.
        val handler = HookedHandler(callbackThread.looper) { post ->
            if (post == 2) establishedQueued.countDown()
        }
        val transport = newTransport(handler)
        Handler(callbackThread.looper).post { callbacksBlocked.await(10, TimeUnit.SECONDS) }

        transport.connect("example.invalid", 64738, false) // A
        assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue()
        transport.disconnect() // A's disconnect is queued behind the busy callback thread
        gate.countDown() // A's read loop unwinds and releases the transport
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }

        transport.connect("example.invalid", 64738, false) // B
        assertThat(establishedQueued.await(5, TimeUnit.SECONDS)).isTrue()
        callbacksBlocked.countDown() // now A's disconnect runs, then B's established

        try {
            assertThat(listener.next()).isEqualTo("disconnect" to "test-tcp-callbacks")
            assertThat(listener.next()).isEqualTo("established" to "test-tcp-callbacks")
        } finally {
            toClient.close() // always let B's read loop unwind, however the assertion went
        }
        awaitUntil(description = "no live thread named humla-tcp-*") { liveThreadNames("humla-tcp-").isEmpty() }
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
        val transport = newTransport(Handler(callbackThread.looper))
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
