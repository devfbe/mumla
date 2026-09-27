package se.lublin.humla.net

import android.os.Handler
import android.os.HandlerThread
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.awaitUntil
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HumlaUDPTest {
    private val key = ByteArray(16) { it.toByte() }
    private val clientNonce = ByteArray(16) { (0x10 + it).toByte() }
    private val serverNonce = ByteArray(16) { (0x20 + it).toByte() }
    private val callbackThread = HandlerThread("test-udp-callbacks").apply { start() }

    /** Like the connection's scope, dispatching the callbacks on [callbackThread]. */
    private val scope = CoroutineScope(SupervisorJob() + Handler(callbackThread.looper).asCoroutineDispatcher())
    private val server = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 200 }
    private val serverCrypt = CryptState().apply { setKeys(key, serverNonce, clientNonce) }
    private val listener = RecordingListener()
    private lateinit var udp: HumlaUDP

    private class RecordingListener : HumlaUDP.UDPConnectionListener {
        val received = LinkedBlockingQueue<Pair<String, ByteArray>>()
        val errors = LinkedBlockingQueue<Exception>()
        override fun onUDPDataReceived(data: ByteArray) { received.add(Thread.currentThread().name to data) }
        override fun onUDPConnectionError(e: Exception) { errors.add(e) }
        override fun resyncCryptState() {}
    }

    private fun startClient(): HumlaUDP {
        udp = HumlaUDP(CryptState().apply { setKeys(key, clientNonce, serverNonce) }, listener, scope)
        udp.connect("127.0.0.1", server.localPort)
        return udp
    }

    /**
     * Sends [payload] until the server socket really receives a datagram, and decrypts it with the
     * single long-lived [serverCrypt] so retried packets stay nonce-consistent. Retrying is what
     * makes this deterministic: a datagram handed to the transport before its socket exists can be
     * dropped, and the OS may drop a loopback datagram too.
     */
    private fun sendUntilReceived(client: HumlaUDP, payload: ByteArray): Pair<DatagramPacket, ByteArray?> {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        var plaintext: ByteArray? = null
        awaitUntil(description = "a datagram from the client reached the server") {
            client.sendMessage(payload, payload.size)
            try {
                server.receive(packet)
            } catch (e: SocketTimeoutException) {
                return@awaitUntil false
            }
            plaintext = serverCrypt.decrypt(packet.data, packet.length)
            true
        }
        return packet to plaintext
    }

    @After
    fun tearDown() {
        if (::udp.isInitialized) {
            udp.disconnect()
            awaitFinished(udp)
        }
        server.close()
        scope.cancel()
        callbackThread.quitSafely()
    }

    @Test
    fun sentPacketArrivesOcbEncryptedAndTheServerCanDecryptIt() {
        val client = startClient()
        val payload = byteArrayOf(0x20, 1, 2, 3, 4)

        val (packet, plaintext) = sendUntilReceived(client, payload)

        assertThat(packet.length).isEqualTo(payload.size + 4) // 1 IV byte + 3 tag bytes
        assertThat(plaintext).isEqualTo(payload)
    }

    @Test
    fun receivedDatagramIsDecryptedAndDeliveredOnTheCallbackHandler() {
        val client = startClient()
        val (hello, _) = sendUntilReceived(client, byteArrayOf(0x20)) // lets the server learn the address
        val payload = byteArrayOf(0x20, 9, 8, 7)
        val encrypted = serverCrypt.encrypt(payload, payload.size)

        server.send(DatagramPacket(encrypted, encrypted.size, hello.socketAddress))

        val (thread, data) = listener.received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("nothing received")
        assertThat(data).isEqualTo(payload)
        assertThat(thread).isEqualTo("test-udp-callbacks")
    }

    @Test
    fun aClosedSocketIsReportedAsAConnectionError() {
        val sockets = LinkedBlockingQueue<DatagramSocket>()
        udp = HumlaUDP(
            CryptState().apply { setKeys(key, clientNonce, serverNonce) },
            listener,
            scope,
        ) { DatagramSocket().also { sockets.add(it) } }

        udp.connect("127.0.0.1", server.localPort)
        awaitUntil(description = "udp running") { udp.isRunning }
        sockets.poll(5, TimeUnit.SECONDS)!!.close() // the receive loop's socket disappears

        val error = listener.errors.poll(5, TimeUnit.SECONDS)
        assertThat(error).isInstanceOf(IOException::class.java)
        awaitUntil(description = "udp stopped") { !udp.isRunning }
    }

    /**
     * A send after the transport's thread has died is dropped before encrypt(), so no OCB2 sequence
     * number is burned. encrypt() increments the IV first, so an unchanged IV means it was not
     * reached.
     */
    @Test
    fun aSendAfterTheThreadDiedDoesNotBurnASequenceNumber() {
        val crypt = CryptState().apply { setKeys(key, clientNonce, serverNonce) }
        val sockets = LinkedBlockingQueue<DatagramSocket>()
        udp = HumlaUDP(crypt, listener, scope) {
            DatagramSocket().also { sockets.add(it) }
        }
        udp.connect("127.0.0.1", server.localPort)
        awaitUntil(description = "udp running") { udp.isRunning }
        sockets.poll(5, TimeUnit.SECONDS)!!.close()
        assertThat(listener.errors.poll(5, TimeUnit.SECONDS)).isInstanceOf(IOException::class.java)
        awaitUntil(description = "udp stopped") { !udp.isRunning }

        val ivBefore = crypt.encryptIV.copyOf()
        udp.sendMessage(ByteArray(64), 64)

        assertThat(crypt.encryptIV).isEqualTo(ivBefore)
    }

    @Test
    fun aUserDisconnectIsNotReportedAsAnError() {
        val client = startClient()
        awaitUntil(description = "udp running") { client.isRunning }

        client.disconnect()

        awaitUntil(description = "udp stopped") { !client.isRunning }
        assertThat(listener.errors).isEmpty()
    }

    @Test
    fun aTransportIsSingleUseAndAFreshOneConnectsAgain() {
        val first = startClient()
        awaitUntil(description = "udp running") { first.isRunning }
        first.disconnect()

        assertThrows(IllegalStateException::class.java) { first.connect("127.0.0.1", server.localPort) }

        // What HumlaConnection.startUdp() actually does after a failure: build a new transport.
        val second = startClient()
        val (packet, _) = sendUntilReceived(second, byteArrayOf(0x20, 5))
        assertThat(packet.length).isEqualTo(6)
    }

    /**
     * disconnect() before the receive loop has a socket must still stop it, not be overwritten by
     * the loop's start-up.
     */
    @Test
    fun aDisconnectBeforeTheSocketExistsStillStopsTheReceiveLoop() {
        val gate = CountDownLatch(1)
        val sockets = LinkedBlockingQueue<DatagramSocket>()
        udp = HumlaUDP(
            CryptState().apply { setKeys(key, clientNonce, serverNonce) },
            listener,
            scope,
        ) { gate.await(); DatagramSocket().also { sockets.add(it) } }

        udp.connect("127.0.0.1", server.localPort)
        udp.disconnect()
        gate.countDown()

        val socket = sockets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no socket was created")
        awaitUntil(description = "the receive loop closed its socket and exited") { socket.isClosed }
        assertThat(udp.isRunning).isFalse()
        assertThat(listener.errors).isEmpty()
        awaitFinished(udp)
    }

    @Test
    fun bothUdpLoopsEndOnDisconnect() {
        val client = startClient()
        awaitUntil(description = "udp running") { client.isRunning }
        sendUntilReceived(client, byteArrayOf(0x20, 1)) // makes sure the sender is up

        client.disconnect()

        awaitFinished(client)
    }

    /** Cancelling the scope - what the connection's disconnect does - closes the socket as well. */
    @Test
    fun cancellingTheScopeClosesTheSocketAndReportsNothing() {
        val sockets = LinkedBlockingQueue<DatagramSocket>()
        udp = HumlaUDP(CryptState().apply { setKeys(key, clientNonce, serverNonce) }, listener, scope) {
            DatagramSocket().also { sockets.add(it) }
        }
        udp.connect("127.0.0.1", server.localPort)
        awaitUntil(description = "udp running") { udp.isRunning }

        scope.cancel()

        val socket = sockets.poll(5, TimeUnit.SECONDS)!!
        awaitUntil(description = "the socket closed") { socket.isClosed }
        awaitFinished(udp)
        assertThat(listener.errors.poll(200, TimeUnit.MILLISECONDS)).isNull()
    }

    private fun awaitFinished(transport: HumlaUDP) =
        awaitUntil(description = "every coroutine of the transport finished") { transport.isFinished }
}
