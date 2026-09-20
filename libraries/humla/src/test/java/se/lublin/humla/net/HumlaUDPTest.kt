package se.lublin.humla.net

import android.os.Handler
import android.os.HandlerThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.awaitUntil
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HumlaUDPTest {
    private val key = ByteArray(16) { it.toByte() }
    private val clientNonce = ByteArray(16) { (0x10 + it).toByte() }
    private val serverNonce = ByteArray(16) { (0x20 + it).toByte() }
    private val callbackThread = HandlerThread("test-udp-callbacks").apply { start() }
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
        udp = HumlaUDP(CryptState().apply { setKeys(key, clientNonce, serverNonce) }, listener, Handler(callbackThread.looper))
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
        if (::udp.isInitialized) udp.disconnect()
        server.close()
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
}
