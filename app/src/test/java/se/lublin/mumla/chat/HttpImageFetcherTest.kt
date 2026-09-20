package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HttpImageFetcherTest {
    private lateinit var server: HttpServer
    private val closeables = mutableListOf<AutoCloseable>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Daemon threads: the endless/dribbling handlers below block on purpose, and a stuck
        // handler must never be able to keep the Gradle test JVM alive.
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
        closeables.forEach { runCatching { it.close() } }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    /** [declaredLength] 0 = chunked; a declared length larger than [body] is only a header lie. */
    private fun serve(path: String, body: ByteArray, status: Int = 200, declaredLength: Long = body.size.toLong(), delayMs: Long = 0) {
        server.createContext(path) { exchange ->
            if (delayMs > 0) Thread.sleep(delayMs)
            exchange.sendResponseHeaders(status, declaredLength)
            try {
                exchange.responseBody.use { it.write(body) }
            } catch (ignored: java.io.IOException) {
                // client hung up early (expected for the too-large cases)
            }
        }
    }

    @Test
    fun returnsResponseBody() {
        val body = ByteArray(100) { it.toByte() }
        serve("/a.png", body)
        assertThat(HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(url("/a.png"))).isEqualTo(body)
    }

    @Test
    fun percentEscapesInThePathReachTheServerUndecoded() {
        val body = ByteArray(10) { it.toByte() }
        val requested = AtomicReference<String>()
        server.createContext("/") { exchange ->
            requested.set(exchange.requestURI.rawPath)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertThat(HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(url("/a%20b.png"))).isEqualTo(body)
        assertThat(requested.get()).isEqualTo("/a%20b.png")
    }

    @Test
    fun rejectsDeclaredOversizedBody() {
        serve("/big", ByteArray(10), declaredLength = 2_000)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(maxBytes = 1_000, hostPolicy = HostPolicy.ANY_HOST).fetch(url("/big")) }
        assertThat(e.error).isEqualTo(ImageError.TOO_LARGE)
    }

    @Test
    fun rejectsUndeclaredOversizedBody() {
        serve("/chunked", ByteArray(2_000), declaredLength = 0)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(maxBytes = 1_000, hostPolicy = HostPolicy.ANY_HOST).fetch(url("/chunked")) }
        assertThat(e.error).isEqualTo(ImageError.TOO_LARGE)
    }

    @Test
    fun non2xxIsNetworkError() {
        serve("/missing", "nope".toByteArray(), status = 404)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(url("/missing")) }
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    @Test
    fun readTimeoutIsReported() {
        serve("/slow", ByteArray(10), delayMs = 1_000)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(readTimeoutMs = 200, hostPolicy = HostPolicy.ANY_HOST).fetch(url("/slow")) }
        assertThat(e.error).isEqualTo(ImageError.TIMEOUT)
    }

    @Test
    fun connectionRefusedIsNetworkError() {
        val port = server.address.port
        server.stop(0)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(connectTimeoutMs = 500, hostPolicy = HostPolicy.ANY_HOST).fetch("http://127.0.0.1:$port/x") }
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    @Test
    fun nonHttpUrlIsUnsupported() {
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch("nourl") }
        assertThat(e.error).isEqualTo(ImageError.UNSUPPORTED)
    }

    // --- The fetcher is the second half of the scheme guarantee: it must refuse a source that
    // --- ImageSource classifies as Unsupported outright, without opening anything.

    private fun expectError(url: String, error: ImageError, fetcher: HttpImageFetcher = HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST)) {
        val e = assertThrows("expected $error for <$url>", ImageFetchException::class.java) { fetcher.fetch(url) }
        assertWithMessage("error for <%s>", url).that(e.error).isEqualTo(error)
    }

    @Test
    fun foreignSchemesAreRefusedWithoutAnyAccess() {
        listOf(
            "file:///etc/passwd",
            "FILE:///etc/passwd",
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "ftp://127.0.0.1/a.png",
            "jar:file:///tmp/a.jar!/a.png",
            "content://settings/secure",
            "mailto:a@b.c",
            "data:image/png;base64,YQ==",
            "//127.0.0.1/a.png",
            "/a.png",
            "a.png",
        ).forEach { expectError(it, ImageError.UNSUPPORTED) }
    }

    @Test
    fun aFileUrlNeverReadsTheFile() {
        val secret = File.createTempFile("mumla-secret", ".txt").apply {
            writeText("top secret")
            deleteOnExit()
        }
        try {
            expectError(secret.toURI().toString(), ImageError.UNSUPPORTED)
        } finally {
            secret.delete()
        }
    }

    @Test
    fun syntacticallyInvalidRemoteUrlsAreUnsupported() {
        // These reach the fetcher as ImageSource.Remote; nothing must be sent for them.
        listOf(
            url("/a b.png"),
            url("/a\u0000b.png"),
            url("/a\r\nX-Evil: 1/b.png"),
            "http://",
            "https://",
            "http:///a.png",
        ).forEach { expectError(it, ImageError.UNSUPPORTED) }
    }

    @Test
    fun uppercaseSchemeIsAccepted() {
        val body = ByteArray(10) { it.toByte() }
        serve("/up.png", body)
        assertThat(HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch("HTTP://127.0.0.1:${server.address.port}/up.png")).isEqualTo(body)
    }

    @Test
    fun credentialsInTheUrlConnectToTheHostNotTheUserinfo() {
        val body = ByteArray(10) { it.toByte() }
        serve("/cred.png", body)
        val fetched = HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch("http://user:pass@127.0.0.1:${server.address.port}/cred.png")
        assertThat(fetched).isEqualTo(body)
    }

    private fun serveRedirect(path: String, location: String) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
    }

    @Test
    fun sameSchemeRedirectIsFollowed() {
        val body = ByteArray(10) { it.toByte() }
        serve("/target.png", body)
        serveRedirect("/redirect.png", url("/target.png"))
        assertThat(HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(url("/redirect.png"))).isEqualTo(body)
    }

    @Test
    fun redirectToAFileUrlIsNotFollowed() {
        val secret = File.createTempFile("mumla-secret", ".txt").apply {
            writeText("top secret")
            deleteOnExit()
        }
        try {
            serveRedirect("/to-file.png", secret.toURI().toString())
            expectError(url("/to-file.png"), ImageError.NETWORK)
        } finally {
            secret.delete()
        }
    }

    @Test
    fun crossSchemeRedirectsAreNotFollowed() {
        serveRedirect("/to-js.png", "javascript:alert(1)")
        serveRedirect("/to-https.png", "https://127.0.0.1:1/a.png")
        expectError(url("/to-js.png"), ImageError.NETWORK)
        expectError(url("/to-https.png"), ImageError.NETWORK)
    }

    /** Records that it was reached and answers; used where being reached at all is the failure. */
    private fun serveTripwire(path: String, reached: AtomicBoolean) {
        server.createContext(path) { exchange ->
            reached.set(true)
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
    }

    @Test
    fun aRelativeRedirectIsResolvedAgainstTheUrlThatSentIt() {
        // HttpURLConnection used to resolve these; now the fetcher follows redirects itself, so it
        // has to, and a Location of "/target.png" is what a real server sends.
        val body = ByteArray(10) { it.toByte() }
        serve("/relative-target.png", body)
        serveRedirect("/relative.png", "/relative-target.png")
        assertThat(HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(url("/relative.png"))).isEqualTo(body)
    }

    @Test
    fun anEndlessRedirectLoopIsCutOff() {
        val hops = AtomicInteger()
        server.createContext("/loop.png") { exchange ->
            hops.incrementAndGet()
            exchange.responseHeaders.add("Location", url("/loop.png"))
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val e = assertThrows(ImageFetchException::class.java) {
            HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(url("/loop.png"))
        }
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
        // Six requests: the original plus MAX_REDIRECTS. Following redirects by hand means the
        // bound is ours now, so it is worth stating rather than trusting the platform's.
        assertThat(hops.get()).isEqualTo(6)
    }

    /**
     * The point of following redirects by hand. The policy is asked again for the second hop, and
     * refusing it has to stop the request, not merely rename the error afterwards — so the target
     * records whether it was ever reached, and that is what is asserted first.
     */
    @Test
    fun theHostPolicyIsAskedAgainForEveryRedirectHop() {
        val reached = AtomicBoolean(false)
        serveTripwire("/second-hop.png", reached)
        serveRedirect("/first-hop.png", url("/second-hop.png"))
        val asked = mutableListOf<String>()
        val onceOnly = HostPolicy { host ->
            synchronized(asked) { asked += host; asked.size == 1 }
        }

        val e = assertThrows(ImageFetchException::class.java) {
            HttpImageFetcher(hostPolicy = onceOnly).fetch(url("/first-hop.png"))
        }

        assertWithMessage("the refused hop was requested anyway").that(reached.get()).isFalse()
        assertThat(asked).containsExactly("127.0.0.1", "127.0.0.1")
        // NETWORK, not UNSUPPORTED: the source in the message was fine and it is the server's
        // Location plus a resolver answer that were judged. See aRefusalByTheHostPolicyIsRetryable.
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    /**
     * That the *default* is the refusing policy is a claim of its own: every other test in this
     * class hands in HostPolicy.ANY_HOST, so a default quietly changed to that would leave all of
     * them green.
     */
    @Test
    fun theDefaultPolicyRefusesTheDevicesOwnNetworkWithoutOpeningAnything() {
        val reached = AtomicBoolean(false)
        serveTripwire("/loopback.png", reached)

        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher().fetch(url("/loopback.png")) }

        assertWithMessage("a loopback URL from a chat message was fetched").that(reached.get()).isFalse()
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    @Test
    fun aRedirectWithNothingUsableToFollowIsANetworkError() {
        // Not UNSUPPORTED: the source in the message parsed fine, and UNSUPPORTED is the error the
        // loader caches for good. A server that answers badly must stay retryable.
        serveRedirect("/no-location.png", "")
        serveRedirect("/torn-location.png", "http://[not a url")
        expectError(url("/no-location.png"), ImageError.NETWORK)
        expectError(url("/torn-location.png"), ImageError.NETWORK)
    }

    /** "The check could not be made" must not read as "let it through". */
    @Test
    fun aHostPolicyThatThrowsRefuses() {
        val reached = AtomicBoolean(false)
        serveTripwire("/throwing-policy.png", reached)
        val broken = HostPolicy { throw IllegalStateException("resolver on fire") }

        val e = assertThrows(ImageFetchException::class.java) {
            HttpImageFetcher(hostPolicy = broken).fetch(url("/throwing-policy.png"))
        }

        assertThat(reached.get()).isFalse()
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    /**
     * The two gates look alike from the outside and must not be reported alike. The syntactic one
     * judges the string the message carried: it cannot become right later, so UNSUPPORTED, which
     * the loader remembers for the life of the process. [PublicHostsOnly] judges a **resolver
     * answer**, and that is exactly what a DNS blocker (`0.0.0.0`), a captive portal or a
     * split-horizon company resolver (`192.168.x.x`) hands back for a perfectly good CDN name.
     * Reporting that as terminal means turning the blocker off, signing in to the portal or moving
     * to another network changes nothing until the app is restarted. So: retryable.
     */
    @Test
    fun aRefusalByTheHostPolicyIsRetryableWhileABrokenSourceIsNot() {
        val reached = AtomicBoolean(false)
        serveTripwire("/judged.png", reached)
        val refusing = HostPolicy { false }

        expectError(url("/judged.png"), ImageError.NETWORK, HttpImageFetcher(hostPolicy = refusing))
        assertWithMessage("a refused host was fetched anyway").that(reached.get()).isFalse()

        expectError("ftp://x/a.png", ImageError.UNSUPPORTED, HttpImageFetcher(hostPolicy = refusing))
        expectError("http://@:8080/a.png", ImageError.UNSUPPORTED, HttpImageFetcher(hostPolicy = refusing))
    }

    /**
     * The same split one hop later. A `Location` that no connection can be opened for is the
     * server's mistake, not the message's, so it may not be remembered for good either — which is
     * already what a missing or torn `Location` costs.
     */
    @Test
    fun aRedirectToAnUnopenableUrlIsANetworkErrorNotAVerdictOnTheSource() {
        serveRedirect("/to-hostless.png", "http://@:8080/a.png")
        expectError(url("/to-hostless.png"), ImageError.NETWORK)
    }

    /**
     * At the hop limit the sixth `Location` is not followed, so it must cost nothing and decide
     * nothing: no name lookup, and no chance for the host it names to set this call's error code.
     */
    @Test
    fun theHopLimitIsReachedBeforeTheNextLocationIsJudged() {
        val hops = AtomicInteger()
        server.createContext("/loop6.png") { exchange ->
            hops.incrementAndGet()
            exchange.responseHeaders.add("Location", url("/loop6.png"))
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val asked = AtomicInteger()
        val counting = HostPolicy { asked.incrementAndGet(); true }

        val e = assertThrows(ImageFetchException::class.java) {
            HttpImageFetcher(hostPolicy = counting).fetch(url("/loop6.png"))
        }

        assertThat(e.error).isEqualTo(ImageError.NETWORK)
        assertThat(hops.get()).isEqualTo(6)
        assertWithMessage("name lookups: one per request made, none for the hop that is not made")
            .that(asked.get()).isEqualTo(6)
    }

    /** Streams chunks until the client hangs up (or a hard backstop), i.e. an endless body. */
    private fun serveEndless(path: String, chunk: ByteArray = ByteArray(4_096)) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, 0)
            val stop = System.nanoTime() + 20_000_000_000L
            try {
                exchange.responseBody.use { out ->
                    while (System.nanoTime() < stop) {
                        out.write(chunk)
                        out.flush()
                    }
                }
            } catch (ignored: java.io.IOException) {
                // client hung up, which is the point of the test
            }
        }
    }

    @Test(timeout = 30_000)
    fun endlessBodyIsCutOffByTheSizeCap() {
        serveEndless("/endless")
        expectError(url("/endless"), ImageError.TOO_LARGE, HttpImageFetcher(maxBytes = 64 * 1024, hostPolicy = HostPolicy.ANY_HOST))
    }

    /** Serves one connection by hand, so the response can break the rules the JDK server enforces. */
    private fun rawServer(respond: (java.io.OutputStream) -> Unit): String {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        closeables += socket
        Thread {
            runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    // Drain the request headers.
                    var crlfs = 0
                    while (crlfs < 2) {
                        val b = input.read()
                        if (b < 0) return@use
                        crlfs = if (b == '\n'.code) crlfs + 1 else if (b == '\r'.code) crlfs else 0
                    }
                    respond(client.getOutputStream())
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${socket.localPort}/a.png"
    }

    /** A raw server that declares [declared] bytes and then writes [actual] bytes anyway. */
    private fun rawLyingServer(declared: Int, actual: ByteArray): String = rawServer { out ->
        out.write("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\n\r\n".toByteArray())
        out.write(actual)
        out.flush()
    }

    /** Sends the status line and then one header byte every [gapMs]: every read succeeds, forever. */
    private fun rawHeaderDribbleServer(gapMs: Long = 20): String = rawServer { out ->
        out.write("HTTP/1.1 200 OK\r\nX-Pad: ".toByteArray())
        out.flush()
        val stop = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < stop) {
            out.write('x'.code)
            out.flush()
            Thread.sleep(gapMs)
        }
    }

    /**
     * The other direction of a lying Content-Length, and the one that is silent: a body that stops
     * early reaches the decoder as a truncated image, which is indistinguishable from a broken one
     * — and the loader caches MALFORMED for the life of the process. Half a download must be a
     * NETWORK error, which expires, not a verdict on the image.
     */
    @Test(timeout = 30_000)
    fun aBodyThatStopsShortOfItsContentLengthIsNotAccepted() {
        val target = rawLyingServer(declared = 1_000, actual = ByteArray(400) { 1 })
        val e = assertThrows(ImageFetchException::class.java) {
            HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch(target)
        }
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    @Test(timeout = 30_000)
    fun aContentLengthThatUnderstatesTheBodyCannotDefeatTheCap() {
        val truthful = ByteArray(10) { it.toByte() }
        val target = rawLyingServer(declared = 10, actual = truthful + ByteArray(512 * 1024) { 0x7F })
        // Measured on JDK 21: the stream reports EOF once the declared length has been consumed in
        // small reads, but a single large read overshoots it freely (asking for 16 KiB after a
        // declared 10 returned 16384 bytes). The fetcher never asks for more than what is left of
        // the cap, so the 512 KiB tail cannot reach the caller either way.
        val body = HttpImageFetcher(maxBytes = 1_000, hostPolicy = HostPolicy.ANY_HOST).fetch(target)
        assertWithMessage("body must never exceed the cap").that(body.size).isAtMost(1_000)
        assertThat(body).isEqualTo(truthful)
    }

    /** Sends one byte at a time, slowly, forever: every single read succeeds, the transfer never ends. */
    private fun serveDribble(path: String, gapMs: Long = 20) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, 0)
            val stop = System.nanoTime() + 20_000_000_000L
            try {
                exchange.responseBody.use { out ->
                    while (System.nanoTime() < stop) {
                        out.write(1)
                        out.flush()
                        Thread.sleep(gapMs)
                    }
                }
            } catch (ignored: Exception) {
                // client hung up, which is the point of the test
            }
        }
    }

    @Test(timeout = 30_000)
    fun aDribblingServerHitsTheTotalTimeoutNotJustTheReadTimeout() {
        serveDribble("/dribble")
        val started = System.nanoTime()
        expectError(
            url("/dribble"),
            ImageError.TIMEOUT,
            HttpImageFetcher(readTimeoutMs = 10_000, maxBytes = 5L * 1024 * 1024, totalTimeoutMs = 500, hostPolicy = HostPolicy.ANY_HOST),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertThat(elapsedMs).isLessThan(5_000)
    }

    /**
     * Authorities that name no host in the only sense that counts: the URL that would actually be
     * opened has an empty `getHost()`. Two parsers read these differently — `URI.getHost()` is null
     * for every one of them, and splitting the authority by hand finds a "host" in the ones with
     * more than one `@` — while `URLStreamHandler.parseURL` refuses server-based parsing outright
     * and leaves the host empty. An empty host resolves to localhost, so opening one of these would
     * connect to 127.0.0.1 (port 80, or 443 for https) with no DNS lookup at all.
     */
    private fun hostlessAuthorityUrls(port: Int) = listOf(
        "http://@:$port/a.png",
        "http://user:pass@:$port/a.png",
        "http://@/a.png",
        "http://user@/a.png",
        "https://@:443/a.png",
        "http://:8080/a.png",
        "http://a@b@c/a.png",
        "http://@@host/a.png",
        "https://a@b@c/a.png",
        "http://a@b@127.0.0.1:8080/x",
    )

    @Test
    fun anAuthorityWithoutAHostIsUnsupportedAndThrowsNothingUnchecked() {
        // The platform HTTP stack throws a StringIndexOutOfBoundsException on these, which would
        // escape fetch() as an unchecked exception; assertThrows(ImageFetchException) pins that it
        // does not. It pins the reported error and nothing more — that no connection is opened is
        // a separate claim, and aHostlessAuthorityOpensNoConnectionAtAll is what proves it.
        hostlessAuthorityUrls(server.address.port).forEach { expectError(it, ImageError.UNSUPPORTED) }
    }

    @Test(timeout = 60_000)
    fun aHostlessAuthorityOpensNoConnectionAtAll() {
        // An error code says nothing about whether a socket was opened, and for these authorities
        // that is the whole question: the connection would go to loopback. So route every outgoing
        // connection of this JVM through a trap socket on loopback and count what arrives there.
        val trap = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closeables += trap
        val arrived = AtomicInteger()
        Thread {
            while (true) {
                val accepted = try { trap.accept() } catch (e: Exception) { return@Thread }
                closeables += accepted
                arrived.incrementAndGet()
            }
        }.apply { isDaemon = true }.start()

        val trapProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", trap.localPort))
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(trapProxy)
            override fun connectFailed(uri: URI, sa: SocketAddress, e: IOException) = Unit
        })
        try {
            val fetcher = HttpImageFetcher(connectTimeoutMs = 1_000, readTimeoutMs = 1_000, totalTimeoutMs = 2_000, hostPolicy = HostPolicy.ANY_HOST)
            // Control first: without it a count of zero below would also be what a broken trap
            // looks like. The trap never answers, so this fetch can only fail — that is fine.
            assertThrows(ImageFetchException::class.java) { fetcher.fetch(url("/a.png")) }
            assertWithMessage("the trap must see the control connection").that(arrived.get()).isAtLeast(1)

            arrived.set(0)
            val outcomes = hostlessAuthorityUrls(server.address.port).associateWith {
                runCatching { fetcher.fetch(it) }.exceptionOrNull()
            }
            // The count is asserted before the error codes: the code is the weaker claim, and a
            // failure here is the one worth reading.
            assertWithMessage("connections opened for hostless authorities %s", outcomes.keys)
                .that(arrived.get()).isEqualTo(0)
            outcomes.forEach { (target, thrown) ->
                assertWithMessage("error for <%s>", target)
                    .that((thrown as? ImageFetchException)?.error).isEqualTo(ImageError.UNSUPPORTED)
            }
        } finally {
            ProxySelector.setDefault(previous)
        }
    }

    @Test
    fun anAuthorityThatDoesNameAHostIsNotRefusedByTheGate() {
        // Every one of these names a host in a shape the hand-written gate had to special-case: a
        // registry-based name (URI.getHost() is null for it), a non-ASCII one, a bracketed IPv6
        // literal with and without userinfo, an empty port, a fully qualified name. The gate must
        // let them through; what the network then makes of them is not this test's business.
        val fetcher = HttpImageFetcher(connectTimeoutMs = 2_000, readTimeoutMs = 2_000, totalTimeoutMs = 6_000, hostPolicy = HostPolicy.ANY_HOST)
        listOf(
            "http://my_host.invalid/a.png",
            "http://\u65e5\u672c.invalid/a.png",
            "http://[::1]:1/a.png",
            "http://@[::1]:1/a.png",
            "http://host.invalid:/a.png",
            "http://example.invalid./a.png",
        ).forEach {
            val e = assertThrows("expected a non-UNSUPPORTED failure for <$it>", ImageFetchException::class.java) {
                fetcher.fetch(it)
            }
            assertWithMessage("error for <%s>", it).that(e.error).isNotEqualTo(ImageError.UNSUPPORTED)
        }
    }

    @Test
    fun unicodeCaseFoldingOfTheSchemeIsCaughtHere() {
        // ImageSource.parse matches prefixes case-insensitively, and '\u017F' uppercases to 'S', so
        // "httpſ://" classifies as Remote. The exact scheme check here is what stops it.
        expectError("http\u017F://evil.example/a.png", ImageError.UNSUPPORTED)
        expectError("HTTP\u017F://evil.example/a.png", ImageError.UNSUPPORTED)
        expectError("\uFF48\uFF54\uFF54\uFF50://evil.example/a.png", ImageError.UNSUPPORTED)
        expectError("http\u0455://evil.example/a.png", ImageError.UNSUPPORTED)
    }

    @Test(timeout = 30_000)
    fun dribblingResponseHeadersHitTheTotalTimeout() {
        val target = rawHeaderDribbleServer()
        val started = System.nanoTime()
        expectError(target, ImageError.TIMEOUT, HttpImageFetcher(readTimeoutMs = 10_000, totalTimeoutMs = 500, hostPolicy = HostPolicy.ANY_HOST))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertWithMessage("elapsed ms").that(elapsedMs).isLessThan(5_000)
    }

    @Test
    fun aBodyExactlyAtTheCapIsStillReturned() {
        val body = ByteArray(1_000) { it.toByte() }
        serve("/exact", body)
        assertThat(HttpImageFetcher(maxBytes = 1_000, hostPolicy = HostPolicy.ANY_HOST).fetch(url("/exact"))).isEqualTo(body)
    }

    @Test
    fun oneByteOverTheCapIsRejectedEvenWhenUndeclared() {
        serve("/justover", ByteArray(1_001), declaredLength = 0)
        expectError(url("/justover"), ImageError.TOO_LARGE, HttpImageFetcher(maxBytes = 1_000, hostPolicy = HostPolicy.ANY_HOST))
    }

    /**
     * A connection the fetcher will really open and really use, so that [disconnect] can be made to
     * throw on demand. `fetch` takes a string and opens the connection itself, so this is the only
     * seam the JDK leaves: a stream handler of our own.
     */
    private class SpyConnection(url: URL, private val onDisconnect: () -> Unit) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 404
        override fun disconnect() = onDisconnect()
    }

    @Test
    fun anUncheckedExceptionFromDisconnectDoesNotReplaceTheRealFailure() {
        // The watchdog thread and the calling thread can both be inside
        // sun.net.www.protocol.http.HttpURLConnection.disconnect() at once. It is unsynchronised
        // and re-reads its `http` field after a separate null check, so whichever thread gets there
        // second can see it nulled and throw a NullPointerException. The watchdog's own call is
        // wrapped; the one in the finally block must be too, or that NPE escapes fetch() unchecked
        // and displaces the ImageFetchException already on its way out — precisely what the
        // RuntimeException backstop inside fetch() exists to prevent.
        installSpyHttpsHandler()
        spyDisconnect.set { throw IllegalStateException("disconnect() raced the watchdog") }
        try {
            val e = assertThrows(ImageFetchException::class.java) {
                HttpImageFetcher(hostPolicy = HostPolicy.ANY_HOST).fetch("https://$SPY_HOST/a.png")
            }
            assertWithMessage("the 404 must survive the throwing disconnect()")
                .that(e.error).isEqualTo(ImageError.NETWORK)
        } finally {
            spyDisconnect.set {}
        }
    }

    private companion object {
        private const val SPY_HOST = "spy.invalid"
        private val spyDisconnect = AtomicReference<() -> Unit>({})
        /** The last connection the fetcher opened through the spy handler, so its settings can be read. */
        private val spyConnection = AtomicReference<HttpURLConnection?>(null)
        private var spyHandlerInstalled = false

        /**
         * Installs, once per JVM, an https stream handler that hands out [SpyConnection]s for
         * [SPY_HOST]. The factory can only be set once and is global, which is tolerable here:
         * setting it clears the handler cache, so it wins whatever ran first, it parses exactly
         * like the real https handler (both inherit `URLStreamHandler.parseURL`), and no test in
         * this module opens a real https connection. http is left untouched.
         */
        fun installSpyHttpsHandler() {
            if (spyHandlerInstalled) return
            URL.setURLStreamHandlerFactory { protocol ->
                if (!protocol.equals("https", ignoreCase = true)) null
                else object : URLStreamHandler() {
                    override fun getDefaultPort() = 443
                    override fun openConnection(u: URL): URLConnection =
                        if (u.host == SPY_HOST) SpyConnection(u, spyDisconnect.get()).also { spyConnection.set(it) }
                        else throw IOException("no real https connection in unit tests")
                }
            }
            spyHandlerInstalled = true
        }
    }

    /**
     * `totalTimeoutMs` is supposed to bound the call, and the connect phase is not something the
     * watchdog can shorten — it can only close a connection that already exists. So the connect
     * timeout has to be clamped to what is left of the budget, exactly as the read timeout is. The
     * spy connection is the only seam that can be asked what the fetcher actually set, since `fetch`
     * takes a string and opens the connection itself.
     */
    @Test
    fun theConnectTimeoutIsClampedToWhatIsLeftOfTheTotalBudget() {
        installSpyHttpsHandler()

        spyConnection.set(null)
        expectError(
            "https://$SPY_HOST/a.png", ImageError.NETWORK,
            HttpImageFetcher(connectTimeoutMs = 10_000, totalTimeoutMs = 500, hostPolicy = HostPolicy.ANY_HOST),
        )
        assertWithMessage("connect timeout against a 500 ms total budget")
            .that(requireNotNull(spyConnection.get()).connectTimeout).isAtMost(500)

        // And the other direction, so the clamp cannot become "always the smaller of nothing".
        spyConnection.set(null)
        expectError(
            "https://$SPY_HOST/a.png", ImageError.NETWORK,
            HttpImageFetcher(connectTimeoutMs = 3_000, totalTimeoutMs = 60_000, hostPolicy = HostPolicy.ANY_HOST),
        )
        assertWithMessage("connect timeout against a 60 s total budget")
            .that(requireNotNull(spyConnection.get()).connectTimeout).isEqualTo(3_000)
    }

    @Test
    fun nonPositiveTimeoutsAreRejectedByTheConstructor() {
        // 0 means "no timeout" to the platform, which would silently remove the bound.
        assertThrows(IllegalArgumentException::class.java) { HttpImageFetcher(readTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { HttpImageFetcher(connectTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { HttpImageFetcher(totalTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { HttpImageFetcher(maxBytes = 0) }
    }
}
