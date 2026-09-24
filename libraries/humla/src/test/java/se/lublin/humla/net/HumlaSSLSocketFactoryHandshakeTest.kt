package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Real TLS handshakes over loopback, directly and through a SOCKS5 proxy as the Tor path does. */
@RunWith(RobolectricTestRunner::class)
class HumlaSSLSocketFactoryHandshakeTest {
    private val pool = Executors.newCachedThreadPool()
    private val requestedSni = CopyOnWriteArrayList<String>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** A TLS server presenting [server] that completes every handshake it can. */
    private fun tlsServer(server: TestCertificates.Issued): Int {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", server.keyPair.private, CharArray(0), server.chain)
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, CharArray(0)) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val socket = context.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        closeables += socket
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() as SSLSocket } catch (e: Exception) { return@execute }
                closeables += client
                pool.execute {
                    runCatching {
                        client.startHandshake()
                        (client.session as? ExtendedSSLSession)?.requestedServerNames
                            ?.filterIsInstance<SNIHostName>()?.forEach { requestedSni += it.asciiName }
                        client.outputStream.write(1)
                        client.outputStream.flush()
                    }
                }
            }
        }
        return socket.localPort
    }

    /** A SOCKS5 proxy that only knows "localhost-tls" and forwards it to [targetPort]. */
    private fun socksProxy(targetPort: Int): Int {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        closeables += server
        pool.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { return@execute }
                closeables += client
                pool.execute {
                    runCatching {
                        val input = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        input.readByte() // version
                        val methods = input.readUnsignedByte()
                        input.readFully(ByteArray(methods))
                        out.write(byteArrayOf(5, 0))
                        input.readFully(ByteArray(3)) // ver, cmd, rsv
                        check(input.readUnsignedByte() == 3) { "the proxy must resolve the host" }
                        val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                        input.readUnsignedShort()
                        check(String(name) == "localhost-tls")
                        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        val upstream = Socket(InetAddress.getLoopbackAddress(), targetPort)
                        closeables += upstream
                        pool.execute { runCatching { upstream.getInputStream().copyTo(out) } }
                        client.getInputStream().copyTo(upstream.getOutputStream())
                    }
                }
            }
        }
        return server.localPort
    }

    private fun factory(peerHost: String, pins: KeyStore? = null) =
        HumlaSSLSocketFactory(null, null, pins, TestCertificates.systemTrust(), peerHost)

    private fun SSLSocket.handshakeAndRead(): Int = use {
        soTimeout = 10_000
        startHandshake()
        inputStream.read()
    }

    @Test
    fun aSystemTrustedCertificateForTheEnteredHostConnectsAndSendsItAsSni() {
        val port = tlsServer(TestCertificates.leaf(dnsNames = listOf("mumble.example.org")))
        val factory = factory("mumble.example.org")

        assertThat(factory.createSocket("127.0.0.1", port).handshakeAndRead()).isEqualTo(1)
        assertThat(factory.trustFailure).isEqualTo(TrustFailure.NONE)
        assertThat(requestedSni).containsExactly("mumble.example.org")
    }

    @Test
    fun aSystemTrustedCertificateForAnotherHostIsRejected() {
        val port = tlsServer(TestCertificates.leaf(dnsNames = listOf("other.example.org")))
        val factory = factory("mumble.example.org")

        assertThrows(SSLHandshakeException::class.java) { factory.createSocket("127.0.0.1", port).handshakeAndRead() }
        assertThat(factory.trustFailure).isEqualTo(TrustFailure.UNTRUSTED)
        assertThat(factory.serverChain).isNotNull()
    }

    @Test
    fun aPinnedSelfSignedCertificateConnectsAndADifferentOneIsReportedAsChanged() {
        val pinned = TestCertificates.leaf(selfSigned = true)
        val pins = TestCertificates.pinStore("mumble.example.org" to pinned.certificate)
        val goodPort = tlsServer(pinned)
        val badPort = tlsServer(TestCertificates.leaf(selfSigned = true))

        assertThat(factory("mumble.example.org", pins).createSocket("127.0.0.1", goodPort).handshakeAndRead()).isEqualTo(1)

        val factory = factory("mumble.example.org", pins)
        assertThrows(SSLHandshakeException::class.java) { factory.createSocket("127.0.0.1", badPort).handshakeAndRead() }
        assertThat(factory.trustFailure).isEqualTo(TrustFailure.CHANGED)
    }

    @Test
    fun theTorPathAppliesTheSameChecks() {
        val good = tlsServer(TestCertificates.leaf(dnsNames = listOf("mumble.example.org")))
        val wrongHost = tlsServer(TestCertificates.leaf(dnsNames = listOf("other.example.org")))

        val ok = factory("mumble.example.org")
        assertThat(ok.createTorSocket("localhost-tls", 64738, "127.0.0.1", socksProxy(good)).handshakeAndRead()).isEqualTo(1)
        assertThat(requestedSni).containsExactly("mumble.example.org")

        val rejected = factory("mumble.example.org")
        assertThrows(SSLHandshakeException::class.java) {
            rejected.createTorSocket("localhost-tls", 64738, "127.0.0.1", socksProxy(wrongHost)).handshakeAndRead()
        }
        assertThat(rejected.trustFailure).isEqualTo(TrustFailure.UNTRUSTED)
    }
}
