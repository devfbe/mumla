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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
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
 * The URL comes from another client, so it is hostile input:
 *
 *  * **Scheme.** Only `http`/`https` with a non-empty host (as [URL] parses it) are fetched;
 *    anything else is refused with [ImageError.UNSUPPORTED] before a connection object exists.
 *    This is the authoritative scheme check ([ImageSource.parse]'s prefix match is lenient).
 *  * **Host.** [hostPolicy] is asked about every host, including each redirect hop; a refusal is
 *    [ImageError.NETWORK] (retryable, see [allowedUrl]).
 *  * **Redirects.** Followed by hand, at most [MAX_REDIRECTS], same scheme only (so https ->
 *    `file:` reads nothing, and http -> https upgrades are refused too). The platform would follow
 *    a same-scheme redirect to any host without re-checking it.
 *  * **Size.** [maxBytes] is enforced against `Content-Length` and, independently, against the bytes
 *    read. A body shorter than its declared length is [ImageError.NETWORK], not a truncated image.
 *  * **Time.** [totalTimeoutMs] bounds connect and reads (timeouts clamped to the remaining budget)
 *    and a watchdog closes the connection at the deadline, which stops a server that dribbles bytes.
 *    Name resolution is **not** bounded: `getAllByName` takes no timeout, and each hop may resolve
 *    twice (policy + connect). [fetch] blocks and is not cancellable.
 */
class HttpImageFetcher(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val totalTimeoutMs: Long = 20_000,
    private val hostPolicy: HostPolicy = PublicHostsOnly(),
) : ImageFetcher {

    init {
        // 0 means "no timeout" to the platform.
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(totalTimeoutMs > 0) { "totalTimeoutMs must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Throws(ImageFetchException::class)
    override fun fetch(url: String): ByteArray {
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000L
        var target = allowedUrl(url, ImageError.UNSUPPORTED)
        // The message's fault until a Location decides where to look; see [allowedUrl].
        var blame = ImageError.UNSUPPORTED
        var redirects = 0
        while (true) {
            when (val hop = fetchHop(target, deadline, blame)) {
                is Hop.Body -> return hop.bytes
                is Hop.Redirect -> {
                    // Counted before resolving, so the unfollowed hop costs no lookup.
                    if (++redirects > MAX_REDIRECTS) throw ImageFetchException(ImageError.NETWORK)
                    target = allowedUrl(redirectTarget(target, hop.location), ImageError.NETWORK)
                    blame = ImageError.NETWORK
                }
            }
        }
    }

    /** One request: either the body, or where the server says to look instead. */
    private sealed interface Hop {
        class Body(val bytes: ByteArray) : Hop
        class Redirect(val location: String?) : Hop
    }

    /** [unopenable] is what a [target] that yields no [HttpURLConnection] costs; see [fetch]. */
    private fun fetchHop(target: URL, deadline: Long, unopenable: ImageError): Hop {
        val connection = try {
            // Only reachable with a custom URLStreamHandlerFactory.
            target.openConnection() as? HttpURLConnection
                ?: throw ImageFetchException(unopenable)
        } catch (e: IOException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        } catch (e: RuntimeException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        }
        // A blocking socket read is only interruptible by closing the connection; without the
        // watchdog a server dribbling headers blocks getResponseCode() indefinitely.
        val expired = AtomicBoolean(false)
        var watchdog: ScheduledFuture<*>? = null
        try {
            // Inside the try, so a rejected task cannot leak the connection.
            watchdog = WATCHDOG.schedule({
                expired.set(true)
                runCatching { connection.disconnect() }
            }, remainingMs(deadline), TimeUnit.MILLISECONDS)
            // Clamped: the watchdog cannot close a connection that does not exist yet.
            connection.connectTimeout = clamped(connectTimeoutMs, deadline)
            connection.readTimeout = clamped(readTimeoutMs, deadline)
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
                // With two Location headers the platform picks one; either goes through allowedUrl.
                return Hop.Redirect(connection.getHeaderField("Location"))
            }
            if (code !in 200..299) throw ImageFetchException(ImageError.NETWORK)
            if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw ImageFetchException(ImageError.TOO_LARGE)
            connection.readTimeout = clamped(readTimeoutMs, deadline)
            val body = connection.inputStream.use { readCapped(it, deadline, declared) }
            // A watchdog close can surface as a plain EOF, i.e. a silently truncated image.
            if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
            // A short body is a broken transfer; as MALFORMED it would be cached for good.
            if (declared > 0 && body.size < declared) throw ImageFetchException(ImageError.NETWORK)
            return Hop.Body(body)
        } catch (e: SocketTimeoutException) {
            throw ImageFetchException(ImageError.TIMEOUT, e)
        } catch (e: IOException) {
            throw ImageFetchException(if (expired.get()) ImageError.TIMEOUT else ImageError.NETWORK, e)
        } catch (e: RuntimeException) {
            // Platform HTTP stacks throw unchecked exceptions on malformed authorities and closed
            // connections; fetch() promises ImageFetchException only.
            throw ImageFetchException(if (expired.get()) ImageError.TIMEOUT else ImageError.NETWORK, e)
        } finally {
            watchdog?.cancel(false)
            // Racing the watchdog's unsynchronised disconnect() can throw, which would replace the
            // exception already on its way out.
            runCatching { connection.disconnect() }
        }
    }

    /**
     * [supportedUrl] plus the [hostPolicy] check, asked for every redirect hop. A policy that throws
     * refuses.
     *
     * [syntaxFailure] is what a bad spelling costs: [ImageError.UNSUPPORTED] for the message's URL
     * (terminal), [ImageError.NETWORK] for a `Location` (the server's fault). A policy refusal is
     * always [ImageError.NETWORK], since it judges a resolver answer, which can change with the
     * network.
     */
    private fun allowedUrl(url: String, syntaxFailure: ImageError): URL {
        val target = supportedUrl(url, syntaxFailure)
        val allowed = try {
            hostPolicy.isAllowed(target.host)
        } catch (e: RuntimeException) {
            false
        }
        if (!allowed) throw ImageFetchException(ImageError.NETWORK)
        return target
    }

    /**
     * Where a `Location` header points, resolved against the current URL (relative is legal). A
     * scheme change is not followed. A missing or unparseable `Location` is [ImageError.NETWORK]:
     * the server's answer was wrong, not the message.
     */
    private fun redirectTarget(current: URL, location: String?): String {
        if (location.isNullOrBlank()) throw ImageFetchException(ImageError.NETWORK)
        val next = try {
            current.toURI().resolve(location)
        } catch (e: URISyntaxException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        } catch (e: IllegalArgumentException) {
            throw ImageFetchException(ImageError.NETWORK, e)
        }
        if (!next.scheme.equals(current.protocol, ignoreCase = true)) {
            throw ImageFetchException(ImageError.NETWORK)
        }
        return next.toString()
    }

    /**
     * Accepts [url] only if it is an absolute http(s) URL with a non-empty host. Purely syntactic.
     *
     * The host is judged on the [URL] that is about to be opened, not on the [URI]: the parsers
     * disagree (for `http://a@b@c/x` URI sees host `c`, URL sees none), and an empty host would
     * connect to localhost without any DNS lookup.
     */
    private fun supportedUrl(url: String, failure: ImageError): URL {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            // Spaces, control characters, CR/LF, ...
            throw ImageFetchException(failure, e)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw ImageFetchException(failure)
        val target = try {
            uri.toURL()
        } catch (e: MalformedURLException) {
            throw ImageFetchException(failure, e)
        } catch (e: IllegalArgumentException) {
            throw ImageFetchException(failure, e)
        }
        if (target.host.isNullOrEmpty()) throw ImageFetchException(failure)
        return target
    }

    /** [timeoutMs], never longer than what is left of the total budget. */
    private fun clamped(timeoutMs: Int, deadline: Long): Int =
        remainingMs(deadline).coerceAtMost(timeoutMs.toLong()).toInt()

    /** What is left of the total budget, clamped to a valid, non-zero timeout. */
    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong())

    /**
     * Reads [input] into a buffer pre-sized from a plausible [declaredLength] that never grows beyond
     * the cap, so a truthful `Content-Length` needs no growth or final copy.
     */
    private fun readCapped(input: InputStream, deadline: Long, declaredLength: Long): ByteArray {
        val cap = maxBytes.coerceAtMost(MAX_CAP).toInt()
        var buffer = ByteArray(if (declaredLength in 1..cap.toLong()) declaredLength.toInt() else INITIAL_CAPACITY.coerceAtMost(cap))
        var size = 0
        while (true) {
            if (size == buffer.size) {
                // Probe one byte before growing; at the cap it proves the body is too large.
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
        /** Long enough for the usual canonicalisation chain, short enough to bound the work. */
        private const val MAX_REDIRECTS = 5
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val MAX_CAP = (Int.MAX_VALUE - 8).toLong()

        /** One shared daemon thread for the whole app. */
        private val WATCHDOG: ScheduledExecutorService =
            ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "mumla-image-fetch-watchdog").apply { isDaemon = true }
            }.apply {
                // Otherwise cancelled tasks (and their captured connections) stay queued until due.
                removeOnCancelPolicy = true
            }
    }
}
