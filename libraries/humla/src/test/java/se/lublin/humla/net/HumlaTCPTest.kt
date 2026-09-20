package se.lublin.humla.net

import android.net.SSLCertificateSocketFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
import javax.net.ssl.SSLSocket

/**
 * Covers the TCP transport's lifecycle: which thread callbacks arrive on, that a failed or aborted
 * connect reports onTCPConnectionDisconnect exactly once, and that no socket thread outlives the
 * connection.
 *
 * The real read loop is reachable here too: HumlaTCP hands its socket to
 * SSLCertificateSocketFactory.setHostname for SNI, which rejects anything that is not a Conscrypt
 * socket, but mockkStatic on that factory replaces the SNI call, and a mocked SSLSocket carrying a
 * piped stream then drives readFrame and the frame callbacks for real.
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
        override fun onTCPConnectionFailed(e: HumlaException) { record("failed") }
        override fun onTCPConnectionDisconnect() { disconnects.incrementAndGet(); record("disconnect") }
        override fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray) { record("message") }
        private fun record(name: String) { events.add(name to Thread.currentThread().name) }
        fun next(): Pair<String, String> =
            events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no callback within 5s")
    }

    @After
    fun tearDown() {
        tcp?.disconnect()
        callbackThread.quitSafely()
        unmockkAll()
    }

    private fun newTransport(handler: Handler? = null) =
        (if (handler == null) HumlaTCP(socketFactory) else HumlaTCP(socketFactory, handler))
            .also { it.setTCPConnectionListener(listener); tcp = it }

    private fun liveThreadNames(prefix: String) =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(prefix) }.map { it.name }

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
     * Delivers for real, but runs [beforeQueueing] with the running post count first - at the one
     * instruction post() has between reading disconnectReported and handing the callback to the
     * handler. Handler.post is final, so the hook sits on the funnel every post goes through.
     */
    private class HookedHandler(looper: Looper, private val beforeQueueing: (Int) -> Unit) : Handler(looper) {
        private val posts = AtomicInteger()
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

    /**
     * The default handler must stay the main looper: HumlaConnection is still a main-thread
     * consumer until Task 4 moves it, and a different delivery thread would reorder its callbacks.
     */
    @Test
    fun theDefaultHandlerDeliversOnTheMainLooper() {
        every { socketFactory.createSocket(any(), any()) } throws IOException("no route")
        val transport = newTransport()

        transport.connect("example.invalid", 64738, false)

        awaitUntil(description = "the callbacks reached the main looper queue") {
            !shadowOf(Looper.getMainLooper()).isIdle
        }
        assertThat(listener.events).isEmpty() // nothing was delivered on the read thread
        awaitUntil(description = "the transport stopped") { !transport.isRunning }
        shadowOf(Looper.getMainLooper()).idle()
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
        assertThat(listener.events).isEmpty()
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /**
     * HumlaSSLSocketFactory.createSocket() connects with no timeout, so a blackholed server keeps
     * the read thread blocked with no socket for disconnect() to close. The caller must still be
     * told it is disconnected - HumlaService only releases its wake lock and shuts audio down when
     * that callback arrives - and it must still be told exactly once when the read loop finally
     * unwinds.
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
        assertThat(listener.events).isEmpty() // the read loop did not report a second disconnect
        assertThat(listener.disconnects.get()).isEqualTo(1)
    }

    /** A disconnect before connect() must stay a no-op, exactly as the Java original was. */
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
     * The read loop parks inside readFrame with no idea that a disconnect has happened: disconnect()
     * reports at once and closes the socket only from the send thread, behind everything queued
     * there. A frame that completes in that window used to be delivered after
     * onTCPConnectionDisconnect - by which time HumlaService has released its wake lock, shut the
     * audio handler down and nulled its handlers, so the late packet walks into a torn-down
     * consumer. The disconnect callback is terminal: nothing follows it.
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
        mockkStatic(SSLCertificateSocketFactory::class)
        runCatching { SSLCertificateSocketFactory.getDefault(0) } // run the static initializer outside every {}
        every { SSLCertificateSocketFactory.getDefault(0) } returns mockk<SSLCertificateSocketFactory>(relaxed = true)
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
     * disconnect() clears "running" while the read thread is still unwinding: it may still be stuck
     * in a connect with no timeout, and its finally still has to report the disconnect and shut the
     * executors down. A connect() slipping into that window would hand the old finally the new
     * connection's disconnect token and let it shut down the new connection's executors, after
     * which sendMessage is a silent no-op and nobody ever reports a disconnect. The Java original
     * refused this with "Threads already initialized."; the transport is busy until its read loop
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
     * Where inUse is released inside the finally is the whole point of holding it: everything from
     * postDisconnectOnce() down still belongs to the connection that is ending. A connect() let
     * through any earlier would have its disconnect token consumed and its brand-new executors
     * shut down by the outgoing finally, leaving sendMessage a silent no-op with nobody ever
     * reporting a disconnect.
     *
     * aConnectIsRefusedUntilTheReadLoopHasFinishedUnwinding cannot see this: it takes the token
     * while the read thread still hangs in createSocket, long before the finally is entered, so it
     * holds for a release anywhere inside the finally. Here the read thread is parked *inside* the
     * finally - in the disconnect post, its first interceptable statement - which makes the
     * position of the release observable rather than just its existence.
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
     * Handler.post returns false once its looper has quit - which Task 4's protocol thread can do.
     * The token that makes the disconnect exactly-once must only be consumed by a callback that was
     * actually queued, otherwise the one report is dropped on the floor and the read loop, which
     * would have reported it a moment later, stays suppressed: nobody ever reports.
     */
    @Test
    fun aDisconnectThePostRejectsIsReportedAgainByTheReadLoop() {
        val attempts = AtomicInteger()
        val handler = mockk<Handler>()
        every { handler.post(any()) } answers {
            attempts.incrementAndGet()
            firstArg<Runnable>().run() // run inline, so the listener still sees what was attempted
            false // ... but tell the caller the looper is gone and the message was not queued
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
        assertThat(listener.disconnects.get()).isEqualTo(2)
    }

    /**
     * "Terminal" has to be decided when the callback is delivered, not when it is queued: post()
     * reads disconnectReported and hands the callback to the handler as two separate steps, and a
     * consumer calling disconnect() in between gets its terminal callback queued first, with the
     * frame landing behind it. That is the same walk into a torn-down consumer as
     * aFrameCompletingAfterTheDisconnectIsNotDelivered, only through a window two instructions
     * wide - reproduced here exactly, by disconnecting from inside the frame's own post.
     *
     * Robolectric's main looper is paused, so nothing runs until the test idles it: the delivery
     * order below is exactly the order the transport handed the callbacks over in.
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
        mockkStatic(SSLCertificateSocketFactory::class)
        runCatching { SSLCertificateSocketFactory.getDefault(0) } // run the static initializer outside every {}
        every { SSLCertificateSocketFactory.getDefault(0) } returns mockk<SSLCertificateSocketFactory>(relaxed = true)
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
    }

    /**
     * A reconnect on the same transport must not write into the previous connection's streams. The
     * read loop closes them but used to leave the fields pointing at them, so between connect() and
     * the new handshake - the new send executor is already running - sendMessage still found the old
     * output. On a real socket that is an IOException swallowed by the send thread, so the message
     * would simply vanish; the fake here keeps the bytes instead, which is what makes it visible.
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
        mockkStatic(SSLCertificateSocketFactory::class)
        runCatching { SSLCertificateSocketFactory.getDefault(0) } // run the static initializer outside every {}
        every { SSLCertificateSocketFactory.getDefault(0) } returns mockk<SSLCertificateSocketFactory>(relaxed = true)
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
}
