package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
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
        assertThat(HttpImageFetcher().fetch(url("/a.png"))).isEqualTo(body)
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
        assertThat(HttpImageFetcher().fetch(url("/a%20b.png"))).isEqualTo(body)
        assertThat(requested.get()).isEqualTo("/a%20b.png")
    }

    @Test
    fun rejectsDeclaredOversizedBody() {
        serve("/big", ByteArray(10), declaredLength = 2_000)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(maxBytes = 1_000).fetch(url("/big")) }
        assertThat(e.error).isEqualTo(ImageError.TOO_LARGE)
    }

    @Test
    fun rejectsUndeclaredOversizedBody() {
        serve("/chunked", ByteArray(2_000), declaredLength = 0)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(maxBytes = 1_000).fetch(url("/chunked")) }
        assertThat(e.error).isEqualTo(ImageError.TOO_LARGE)
    }

    @Test
    fun non2xxIsNetworkError() {
        serve("/missing", "nope".toByteArray(), status = 404)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher().fetch(url("/missing")) }
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    @Test
    fun readTimeoutIsReported() {
        serve("/slow", ByteArray(10), delayMs = 1_000)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(readTimeoutMs = 200).fetch(url("/slow")) }
        assertThat(e.error).isEqualTo(ImageError.TIMEOUT)
    }

    @Test
    fun connectionRefusedIsNetworkError() {
        val port = server.address.port
        server.stop(0)
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher(connectTimeoutMs = 500).fetch("http://127.0.0.1:$port/x") }
        assertThat(e.error).isEqualTo(ImageError.NETWORK)
    }

    @Test
    fun nonHttpUrlIsUnsupported() {
        val e = assertThrows(ImageFetchException::class.java) { HttpImageFetcher().fetch("nourl") }
        assertThat(e.error).isEqualTo(ImageError.UNSUPPORTED)
    }

    // --- The fetcher is the second half of the scheme guarantee: it must refuse a source that
    // --- ImageSource classifies as Unsupported outright, without opening anything.

    private fun expectError(url: String, error: ImageError, fetcher: HttpImageFetcher = HttpImageFetcher()) {
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
        assertThat(HttpImageFetcher().fetch("HTTP://127.0.0.1:${server.address.port}/up.png")).isEqualTo(body)
    }

    @Test
    fun credentialsInTheUrlConnectToTheHostNotTheUserinfo() {
        val body = ByteArray(10) { it.toByte() }
        serve("/cred.png", body)
        val fetched = HttpImageFetcher().fetch("http://user:pass@127.0.0.1:${server.address.port}/cred.png")
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
        assertThat(HttpImageFetcher().fetch(url("/redirect.png"))).isEqualTo(body)
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
        expectError(url("/endless"), ImageError.TOO_LARGE, HttpImageFetcher(maxBytes = 64 * 1024))
    }

    /** A raw server that declares [declared] bytes and then writes [actual] bytes anyway. */
    private fun rawLyingServer(declared: Int, actual: ByteArray): String {
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
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\n\r\n".toByteArray())
                        write(actual)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${socket.localPort}/a.png"
    }

    @Test(timeout = 30_000)
    fun aContentLengthThatUnderstatesTheBodyCannotDefeatTheCap() {
        val truthful = ByteArray(10) { it.toByte() }
        val target = rawLyingServer(declared = 10, actual = truthful + ByteArray(512 * 1024) { 0x7F })
        // The declared length is never trusted as the end of the body: the cap is enforced on the
        // bytes actually read, so understating Content-Length buys the server nothing.
        expectError(target, ImageError.TOO_LARGE, HttpImageFetcher(maxBytes = 1_000))
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
            HttpImageFetcher(readTimeoutMs = 10_000, maxBytes = 5L * 1024 * 1024, totalTimeoutMs = 500),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertThat(elapsedMs).isLessThan(5_000)
    }
}
