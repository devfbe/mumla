package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class ChatImageHttpTest {
    private val server = MockWebServer()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private var allowed = true
    private val lookups = mutableListOf<String>()

    /** Only the exact address MockWebServer listens on counts as public. */
    private val onlyTheTestServer = AddressPolicy { it == loopback }

    /** Names resolve from this table; nothing reaches a real resolver. */
    private val names = mutableMapOf("images.test" to listOf(loopback))
    private val tableDns = Dns { host ->
        lookups += host
        names[host] ?: throw UnknownHostException(host)
    }

    @Before
    fun startServer() {
        server.start(loopback, 0)
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun client(
        policy: AddressPolicy = onlyTheTestServer,
        maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
    ): OkHttpClient = chatImageHttpClient({ allowed }, "Mumla/test", policy, tableDns, maxBytes)

    private fun url(path: String, host: String = "images.test"): HttpUrl =
        server.url(path).newBuilder().host(host).build()

    private fun OkHttpClient.get(url: HttpUrl): ByteArray =
        newCall(Request.Builder().url(url).build()).execute().use { it.body.bytes() }

    private fun OkHttpClient.refusal(url: HttpUrl): ImageError =
        assertThrows(ImageRefusedException::class.java) { get(url) }.error

    private fun image(bytes: Int = 16) = MockResponse.Builder().body(Buffer().write(ByteArray(bytes))).build()

    private fun redirect(location: String) = MockResponse(302, headersOf("Location", location))

    @Test
    fun aPublicHostIsFetched() {
        server.enqueue(image(16))
        assertThat(client().get(url("/a.png"))).hasLength(16)
    }

    @Test
    fun aNameThatResolvesIntoTheLanIsRefusedWithoutAConnection() {
        server.enqueue(image())
        val error = assertThrows(IOException::class.java) { client(AddressPolicy.PUBLIC_ONLY).get(url("/a.png")) }

        assertThat(error).isInstanceOf(UnknownHostException::class.java)
        assertThat(ChatImageLoader.errorOf(error)).isEqualTo(ImageError.NETWORK)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun oneRefusedAddressAmongGoodOnesRefusesTheName() {
        names["mixed.test"] = listOf(loopback, InetAddress.getByName("10.0.0.1"))
        server.enqueue(image())
        assertThrows(UnknownHostException::class.java) { client().get(url("/a.png", "mixed.test")) }
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** OkHttp connects to an IP literal without asking Dns, so the literal is judged before any socket exists. */
    @Test
    fun anIpLiteralIsJudgedBeforeAnySocketIsOpened() {
        val connects = AtomicInteger()
        val client = client(AddressPolicy.PUBLIC_ONLY).newBuilder()
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    connects.incrementAndGet()
                }
            })
            .build()
        server.enqueue(image())

        for (host in listOf("127.0.0.1", "127.1", "2130706433", "0.0.0.0", "::1", "::ffff:127.0.0.1")) {
            assertThat(client.refusal(url("/a.png", host))).isEqualTo(ImageError.NETWORK)
        }
        assertThat(connects.get()).isEqualTo(0)
        assertThat(lookups).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** A resolver answer that changes between the lookup and the connection (DNS rebinding). */
    @Test
    fun theAddressActuallyConnectedToIsCheckedBeforeTheRequestIsSent() {
        val checks = AtomicInteger()
        // Allows the lookup, refuses everything after it.
        val flipping = AddressPolicy { checks.incrementAndGet() == 1 && it == loopback }
        server.enqueue(image())

        assertThat(client(flipping).refusal(url("/a.png"))).isEqualTo(ImageError.NETWORK)
        assertThat(checks.get()).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun aRedirectToAnAllowedHostOfTheSameSchemeIsFollowed() {
        server.enqueue(redirect(url("/b.png").toString()))
        server.enqueue(image(8))
        assertThat(client().get(url("/a.png"))).hasLength(8)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun aRedirectIntoTheLanIsRefusedWithoutFollowingIt() {
        names["lan.test"] = listOf(InetAddress.getByName("192.168.1.1"))
        server.enqueue(redirect(url("/b.png", "lan.test").toString()))
        assertThrows(UnknownHostException::class.java) { client().get(url("/a.png")) }

        server.enqueue(redirect("http://169.254.169.254/latest/meta-data"))
        assertThat(client().refusal(url("/a.png"))).isEqualTo(ImageError.NETWORK)
        assertThat(server.requestCount).isEqualTo(2)
    }

    /** No https -> http downgrade; the check is "same scheme", so the other direction pins it too. */
    @Test
    fun aRedirectThatChangesTheSchemeIsRefused() {
        server.enqueue(redirect(url("/b.png").newBuilder().scheme("https").build().toString()))
        assertThat(client().refusal(url("/a.png"))).isEqualTo(ImageError.NETWORK)

        for (location in listOf("file:///etc/passwd", "ftp://images.test/a.png", "javascript:alert(1)")) {
            server.enqueue(redirect(location))
            assertThat(client().refusal(url("/a.png"))).isEqualTo(ImageError.NETWORK)
        }
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun aRedirectWithoutALocationIsANetworkError() {
        server.enqueue(MockResponse(302))
        assertThat(client().refusal(url("/a.png"))).isEqualTo(ImageError.NETWORK)
    }

    @Test
    fun atMostFiveRedirectsAreFollowed() {
        repeat(6) { server.enqueue(redirect("/next")) }
        assertThat(client().refusal(url("/a.png"))).isEqualTo(ImageError.NETWORK)
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun aDeclaredLengthOverTheCapIsRefusedBeforeTheBodyIsRead() {
        server.enqueue(image(101))
        assertThat(client(maxBytes = 100).refusal(url("/a.png"))).isEqualTo(ImageError.TOO_LARGE)
    }

    @Test
    fun anUndeclaredBodyOverTheCapFailsWhileStreaming() {
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(101)), 10).build())
        assertThat(client(maxBytes = 100).refusal(url("/a.png"))).isEqualTo(ImageError.TOO_LARGE)
    }

    @Test
    fun aBodyExactlyAtTheCapIsRead() {
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(100)), 10).build())
        assertThat(client(maxBytes = 100).get(url("/a.png"))).hasLength(100)
    }

    /** OkHttp would inflate gzip past the cap, so compression is never asked for. */
    @Test
    fun compressionIsNotRequested() {
        server.enqueue(image())
        client().get(url("/a.png"))
        assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("identity")
    }

    @Test
    fun theDefaultCapIsFiveMegabytes() {
        assertThat(DEFAULT_MAX_IMAGE_BYTES).isEqualTo(5L * 1024 * 1024)
    }

    /** Under Tor (or with external images off) nothing is resolved and nothing is sent. */
    @Test
    fun whileTheGateIsClosedNothingIsResolvedOrSent() {
        allowed = false
        server.enqueue(image())
        assertThat(client().refusal(url("/a.png"))).isEqualTo(ImageError.EXTERNAL_DISABLED)
        assertThat(lookups).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** A gate that closes mid-chain stops the next hop. */
    @Test
    fun theGateIsAskedAgainForEveryRedirect() {
        server.enqueue(redirect("/b.png"))
        server.enqueue(image())
        var asked = 0
        val client = chatImageHttpClient({ ++asked == 1 }, "Mumla/test", onlyTheTestServer, tableDns)
        assertThat(client.refusal(url("/a.png"))).isEqualTo(ImageError.EXTERNAL_DISABLED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun theUserAgentIsMumlaAndNoCookiesAreKept() {
        server.enqueue(MockResponse(200, headersOf("Set-Cookie", "id=tracker; Path=/")))
        server.enqueue(image())
        val client = client()
        client.get(url("/a.png"))
        client.get(url("/b.png"))

        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("Mumla/test")
        assertThat(server.takeRequest().headers["Cookie"]).isNull()
    }

    @Test
    fun timeoutsProxyRedirectsAndCacheAreAsIntended() {
        val client = chatImageHttpClient({ true }, "Mumla/test")
        assertThat(client.connectTimeoutMillis).isEqualTo(5_000)
        assertThat(client.readTimeoutMillis).isEqualTo(10_000)
        assertThat(client.callTimeoutMillis).isEqualTo(20_000)
        assertThat(client.proxy).isEqualTo(Proxy.NO_PROXY)
        assertThat(client.followRedirects).isFalse()
        assertThat(client.followSslRedirects).isFalse()
        assertThat(client.cache).isNull()
    }
}
