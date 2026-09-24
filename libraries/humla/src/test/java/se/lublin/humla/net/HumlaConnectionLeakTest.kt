package se.lublin.humla.net

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.awaitUntil
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

/**
 * Nothing of a connection outlives it: with the real transports, a disconnect leaves no coroutine
 * of the connection running and no thread of its own alive.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaConnectionLeakTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val udpServer = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val listener = RecordingConnectionListener()
    private val tcps = CopyOnWriteArrayList<HumlaTCP>()
    private val udps = CopyOnWriteArrayList<HumlaUDP>()

    /** A server that accepts the TLS handshake and then stays silent until the socket is closed. */
    private val silentServer = mockk<HumlaSSLSocketFactory>().also { factory ->
        every { factory.createSocket(any(), any()) } answers {
            val toClient = PipedOutputStream()
            val input = PipedInputStream(toClient, 64)
            mockk<SSLSocket>(relaxed = true).also { socket ->
                every { socket.inputStream } returns input
                every { socket.outputStream } returns ByteArrayOutputStream()
                every { socket.close() } answers { toClient.close() }
            }
        }
    }

    /** The real transports, except that TCP talks to [silentServer]. */
    private val transports = object : HumlaConnection.TransportFactory {
        override fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
            HumlaTCP(silentServer, scope).also { tcps += it }

        override fun createUdp(
            cryptState: CryptState,
            listener: HumlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport = HumlaUDP(cryptState, listener, scope).also { udps += it }
    }

    @After
    fun tearDown() {
        udpServer.close()
        unmockkAll()
    }

    @Test
    fun aDisconnectedConnectionLeavesNoCoroutineAndNoThreadBehind() {
        val before = liveThreads()
        val connection = HumlaConnection(listener, transports)
        connection.connect(Server(-1, "test", "127.0.0.1", udpServer.localPort, "user", ""))
        awaitUntil(description = "connected over TCP and UDP") {
            mainLooper.idle()
            listener.established.get() == 1 && udps.singleOrNull()?.isRunning == true
        }

        connection.disconnect()

        awaitUntil(description = "the connection terminated") { connection.isTerminated }
        awaitUntil(description = "both transports finished") { tcps.single().isFinished && udps.single().isFinished }
        awaitUntil(description = "no thread of the connection left") { (liveThreads() - before).isEmpty() }
        mainLooper.idle()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    /** Threads the connection owns; the shared coroutine pools outlive every connection by design. */
    private fun liveThreads(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && !it.name.startsWith("DefaultDispatcher-worker") }
        .filter { it.name != "kotlinx.coroutines.DefaultExecutor" }
        .toSet()
}
