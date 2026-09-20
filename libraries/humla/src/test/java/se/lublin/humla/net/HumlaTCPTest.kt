package se.lublin.humla.net

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.HumlaException
import java.io.IOException
import java.net.ConnectException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/**
 * The TLS read loop itself cannot be driven on the JVM: HumlaTCP hands its socket to
 * SSLCertificateSocketFactory.setHostname for SNI, which rejects anything that is not a Conscrypt
 * socket, so no fake ever reaches the handshake. What is testable, and what this covers, is the
 * lifecycle around it: which thread callbacks arrive on, that a failed or aborted connect reports
 * onTCPConnectionDisconnect exactly once, and that no socket thread outlives the connection.
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
    }

    private fun newTransport(handler: Handler? = null) =
        (if (handler == null) HumlaTCP(socketFactory) else HumlaTCP(socketFactory, handler))
            .also { it.setTCPConnectionListener(listener); tcp = it }

    private fun liveThreadNames(prefix: String) =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(prefix) }.map { it.name }

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
}
