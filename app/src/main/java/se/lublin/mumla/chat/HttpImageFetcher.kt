package se.lublin.mumla.chat

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

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
 *  * **Scheme.** Only `http` and `https` with a host are fetched. Everything else — `file:`,
 *    `javascript:`, `content:`, `ftp:`, `jar:`, a scheme-relative `//host/x` or a bare path — is
 *    refused up front with [ImageError.UNSUPPORTED], before any connection object is created, so
 *    nothing is ever opened, let alone read. This is the second half of the guarantee that
 *    [ImageSource.parse] starts: `ChatContentParser` deliberately passes the raw `src` through, so
 *    a dangerous source has to die in one of these two places.
 *  * **Redirects.** Followed only within the same scheme, which `HttpURLConnection` enforces
 *    itself: a `Location` with a different protocol is not followed, the 30x response is returned
 *    instead and reported as [ImageError.NETWORK]. An https → `file:` redirect therefore reads
 *    nothing from disk.
 *  * **Size.** [maxBytes] is enforced twice: against `Content-Length` (so an oversized body is
 *    refused before it is read) and, independently, against the bytes actually read. The header is
 *    never trusted as the end of the body, so a server that understates it, omits it or streams
 *    chunked forever is still cut off at the cap.
 *  * **Time.** [totalTimeoutMs] bounds the whole call, not just the connect: the remaining budget
 *    is checked after every read, and the socket read timeout is clamped to what is left of it. A
 *    server that dribbles one byte at a time — every read succeeding, so [readTimeoutMs] alone
 *    never fires — is stopped by the deadline.
 */
class HttpImageFetcher(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val totalTimeoutMs: Long = 20_000,
) : ImageFetcher {

    override fun fetch(url: String): ByteArray {
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000L
        val target = supportedUrl(url)
        val connection = try {
            target.openConnection() as? HttpURLConnection
                ?: throw ImageFetchException(ImageError.UNSUPPORTED)
        } catch (e: IOException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) throw ImageFetchException(ImageError.NETWORK)
            if (connection.contentLengthLong > maxBytes) throw ImageFetchException(ImageError.TOO_LARGE)
            connection.readTimeout = remainingMs(deadline).coerceAtMost(readTimeoutMs.toLong()).toInt()
            return connection.inputStream.use { readCapped(it, deadline) }
        } catch (e: SocketTimeoutException) {
            throw ImageFetchException(ImageError.TIMEOUT, e)
        } catch (e: IOException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses [url] and accepts it only if it is an absolute http(s) URL with a host. Purely
     * syntactic: it opens nothing, so an unsupported source costs no I/O at all.
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
        // getHost() is null for a registry-based authority (an underscore in the name, say), which
        // is still a host; only a missing or empty authority — "http://", "http:///a.png" — is not.
        if (uri.host.isNullOrEmpty() && uri.authority.isNullOrEmpty()) {
            throw ImageFetchException(ImageError.UNSUPPORTED)
        }
        return try {
            uri.toURL()
        } catch (e: MalformedURLException) {
            throw ImageFetchException(ImageError.UNSUPPORTED, e)
        } catch (e: IllegalArgumentException) {
            throw ImageFetchException(ImageError.UNSUPPORTED, e)
        }
    }

    /** What is left of the total budget, clamped to a valid, non-zero socket timeout. */
    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong())

    private fun readCapped(input: InputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size().toLong() + n > maxBytes) throw ImageFetchException(ImageError.TOO_LARGE)
            out.write(buffer, 0, n)
            if (System.nanoTime() - deadline >= 0) throw ImageFetchException(ImageError.TIMEOUT)
        }
        return out.toByteArray()
    }
}
