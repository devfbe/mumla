package se.lublin.mumla.chat

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fetches raw image bytes for a URL. Blocking; call from an IO dispatcher. */
fun interface ImageFetcher {
    @Throws(ImageFetchException::class)
    fun fetch(url: String): ByteArray
}

/**
 * Fetches an `<img src>` over http(s) with hard bounds on both time and size.
 *
 * The URL comes from a chat message, i.e. from another client, so it is hostile input:
 *
 *  * **Scheme.** Only `http` and `https` with a non-empty host are fetched. Everything else —
 *    `file:`, `javascript:`, `content:`, `ftp:`, `jar:`, a scheme-relative `//host/x`, a bare path,
 *    or an authority without a host such as `http://@:8080/x` — is refused up front with
 *    [ImageError.UNSUPPORTED], before any connection object is created, so nothing is ever opened,
 *    let alone read. This is the second half of the guarantee that [ImageSource.parse] starts:
 *    `ChatContentParser` deliberately passes the raw `src` through, and [ImageSource.parse] matches
 *    the scheme prefix case-insensitively, which folds some exotic characters together (`httpſ://`
 *    matches `https://`). The scheme check here is the authoritative one.
 *  * **Redirects.** Followed only within the same scheme, which `HttpURLConnection` enforces
 *    itself: a `Location` with a different protocol is not followed, the 30x response is returned
 *    instead and reported as [ImageError.NETWORK]. An https → `file:` redirect therefore reads
 *    nothing from disk. The same rule also blocks a legitimate http → https upgrade redirect, which
 *    is a deliberate trade, not a bug: the alternative is re-entering the loader for a scheme the
 *    caller did not ask for. Note that a same-scheme redirect to *any host* is followed without
 *    re-entering the gate above, so a future host policy has to be applied per hop.
 *  * **Size.** [maxBytes] is enforced twice: against `Content-Length` (so an oversized body is
 *    refused before it is read) and, independently, against the bytes actually read. The header is
 *    never trusted as the end of the body, so a server that understates it, omits it or streams
 *    chunked forever is still cut off at the cap. The accumulation buffer is pre-sized from a
 *    plausible `Content-Length` and never grows past the cap.
 *  * **Time.** [totalTimeoutMs] bounds the whole call: the socket read timeout is clamped to the
 *    remaining budget before the response is read and again before the body is read, the remaining
 *    budget is checked after every read, and a watchdog closes the connection at the deadline. A
 *    server that dribbles one byte at a time — of the headers or of the body, so that every single
 *    read succeeds and [readTimeoutMs] alone never fires — is stopped by that watchdog. The one
 *    phase the watchdog cannot shorten is the TCP connect itself, which [connectTimeoutMs] bounds.
 */
class HttpImageFetcher(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val totalTimeoutMs: Long = 20_000,
) : ImageFetcher {

    init {
        // 0 means "no timeout" to the platform, which would defeat the point of all three.
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(totalTimeoutMs > 0) { "totalTimeoutMs must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Throws(ImageFetchException::class)
    override fun fetch(url: String): ByteArray {
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000L
        val target = supportedUrl(url)
        val connection = try {
            target.openConnection() as? HttpURLConnection
                ?: throw ImageFetchException(ImageError.UNSUPPORTED)
        } catch (e: IOException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        } catch (e: RuntimeException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        }
        // A blocking socket read is only interruptible by closing the connection, so the deadline
        // needs a second pair of hands: without this, a server that dribbles response headers
        // blocks in getResponseCode() for as long as it likes, whatever the budget says.
        val expired = AtomicBoolean(false)
        val watchdog = WATCHDOG.schedule({
            expired.set(true)
            runCatching { connection.disconnect() }
        }, remainingMs(deadline), TimeUnit.MILLISECONDS)
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = clampedReadTimeout(deadline)
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) throw ImageFetchException(ImageError.NETWORK)
            if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw ImageFetchException(ImageError.TOO_LARGE)
            connection.readTimeout = clampedReadTimeout(deadline)
            val body = connection.inputStream.use { readCapped(it, deadline, declared) }
            // A connection closed by the watchdog can surface as a plain EOF rather than an error,
            // which would hand the caller a silently truncated image.
            if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
            return body
        } catch (e: SocketTimeoutException) {
            throw ImageFetchException(ImageError.TIMEOUT, e)
        } catch (e: IOException) {
            throw ImageFetchException(if (expired.get()) ImageError.TIMEOUT else ImageError.NETWORK, e)
        } catch (e: RuntimeException) {
            // The platform HTTP stacks throw unchecked exceptions of their own on malformed
            // authorities and on a connection closed underneath them. fetch() promises
            // ImageFetchException and nothing else, so nothing unchecked may escape here.
            throw ImageFetchException(if (expired.get()) ImageError.TIMEOUT else ImageError.NETWORK, e)
        } finally {
            watchdog.cancel(false)
            connection.disconnect()
        }
    }

    /**
     * Parses [url] and accepts it only if it is an absolute http(s) URL with a non-empty host.
     * Purely syntactic: it opens nothing, so an unsupported source costs no I/O at all.
     */
    private fun supportedUrl(url: String): URL {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            // Spaces, control characters, a stray CR/LF, ... — anything that is not a URL.
            throw ImageFetchException(ImageError.UNSUPPORTED, e)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw ImageFetchException(ImageError.UNSUPPORTED)
        if (!hasHost(uri)) throw ImageFetchException(ImageError.UNSUPPORTED)
        return try {
            uri.toURL()
        } catch (e: MalformedURLException) {
            throw ImageFetchException(ImageError.UNSUPPORTED, e)
        } catch (e: IllegalArgumentException) {
            throw ImageFetchException(ImageError.UNSUPPORTED, e)
        }
    }

    /**
     * True when [uri] really names a host. `getHost()` is null for a registry-based authority (an
     * underscore in the name, say), which is still a host, so the authority is inspected by hand in
     * that case — the presence of an authority is not enough, because `@`, `user@` and `@:8080` are
     * authorities without a host and the platform HTTP stack throws unchecked exceptions on them.
     */
    private fun hasHost(uri: URI): Boolean {
        if (!uri.host.isNullOrEmpty()) return true
        val authority = uri.authority ?: return false
        val afterUserInfo = authority.substringAfterLast('@')
        val host = if (afterUserInfo.startsWith("[")) {
            afterUserInfo.substringBefore(']')
        } else {
            afterUserInfo.substringBefore(':')
        }
        return host.isNotEmpty() && host != "["
    }

    /** The socket read timeout, never longer than what is left of the total budget. */
    private fun clampedReadTimeout(deadline: Long): Int =
        remainingMs(deadline).coerceAtMost(readTimeoutMs.toLong()).toInt()

    /** What is left of the total budget, clamped to a valid, non-zero timeout. */
    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong())

    /**
     * Reads [input] into a buffer that is pre-sized from [declaredLength] when that is plausible and
     * that never grows beyond the cap, so the peak allocation is the image's own size for a truthful
     * `Content-Length` rather than a doubling `ByteArrayOutputStream` plus its final copy.
     */
    private fun readCapped(input: InputStream, deadline: Long, declaredLength: Long): ByteArray {
        val cap = maxBytes.coerceAtMost(MAX_CAP).toInt()
        var buffer = ByteArray(if (declaredLength in 1..cap.toLong()) declaredLength.toInt() else INITIAL_CAPACITY.coerceAtMost(cap))
        var size = 0
        while (true) {
            if (size == buffer.size) {
                // Probe a single byte instead of growing blindly: a truthful Content-Length then
                // needs no growth and no final copy at all, and at the cap this is the one byte
                // that proves the body is too large.
                val probe = input.read()
                if (probe < 0) break
                if (size == cap) throw ImageFetchException(ImageError.TOO_LARGE)
                buffer = buffer.copyOf((buffer.size * 2).coerceIn(size + 1, cap))
                buffer[size++] = probe.toByte()
            } else {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            if (System.nanoTime() - deadline >= 0) throw ImageFetchException(ImageError.TIMEOUT)
        }
        return if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    private companion object {
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val MAX_CAP = (Int.MAX_VALUE - 8).toLong()

        /** Shared, daemon: one idle thread for the whole app, and never a reason to keep it alive. */
        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mumla-image-fetch-watchdog").apply { isDaemon = true }
            }
    }
}
