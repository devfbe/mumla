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
 * The URL comes from a chat message, i.e. from another client, so it is hostile input:
 *
 *  * **Scheme.** Only `http` and `https` with a non-empty host are fetched. Everything else —
 *    `file:`, `javascript:`, `content:`, `ftp:`, `jar:`, a scheme-relative `//host/x`, a bare path,
 *    or an authority that names no host such as `http://@:8080/x` or `http://a@b@c/x` — is refused
 *    up front with [ImageError.UNSUPPORTED], before any connection object is created, so nothing is
 *    ever opened, let alone read. "Names no host" means what [URL] makes of the authority, not what
 *    [URI] does; [supportedUrl] explains why the difference is the whole point. This is the second
 *    half of the guarantee that [ImageSource.parse] starts:
 *    `ChatContentParser` deliberately passes the raw `src` through, and [ImageSource.parse] matches
 *    the scheme prefix case-insensitively, which folds some exotic characters together (`httpſ://`
 *    matches `https://`). The scheme check here is the authoritative one.
 *  * **Host.** [hostPolicy] is asked about every host this call talks to, and refusing one costs
 *    [ImageError.NETWORK] before anything is opened. The default refuses the device's own network;
 *    see [PublicHostsOnly] for why a chat message must not be able to aim the phone at its own
 *    router. Asking costs one name lookup, which is what judging the answer instead of the spelling
 *    is worth — and because it is an *answer* that is judged, the refusal is retryable rather than
 *    terminal; [allowedUrl] says why that distinction is not cosmetic.
 *  * **Redirects.** Followed by hand, up to [MAX_REDIRECTS] of them, and only within the same
 *    scheme: a `Location` with a different protocol ends the fetch with [ImageError.NETWORK], so an
 *    https → `file:` redirect reads nothing from disk. The same rule also blocks a legitimate
 *    http → https upgrade redirect, which is a deliberate trade, not a bug: the alternative is
 *    re-entering the loader for a scheme the caller did not ask for. Following them here rather than
 *    letting `HttpURLConnection` do it is what makes the host check hold: the platform follows a
 *    same-scheme redirect to *any* host without re-entering the gate, so a policy applied only to
 *    the URL the message carried is undone by one `302`.
 *  * **Size.** [maxBytes] is enforced twice: against `Content-Length` (so an oversized body is
 *    refused before it is read) and, independently, against the bytes actually read. The header is
 *    never trusted as the end of the body, so a server that understates it, omits it or streams
 *    chunked forever is still cut off at the cap. A body that falls *short* of a declared length is
 *    refused as [ImageError.NETWORK] rather than handed over truncated. The accumulation buffer is
 *    pre-sized from a plausible `Content-Length` and never grows past the cap.
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
    private val hostPolicy: HostPolicy = PublicHostsOnly(),
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
        var target = allowedUrl(url, ImageError.UNSUPPORTED)
        var redirects = 0
        while (true) {
            when (val hop = fetchHop(target, deadline)) {
                is Hop.Body -> return hop.bytes
                is Hop.Redirect -> {
                    // The limit is counted before the Location is resolved, so the hop that is not
                    // followed costs no name lookup and the host it names cannot decide this call's
                    // error code either.
                    if (++redirects > MAX_REDIRECTS) throw ImageFetchException(ImageError.NETWORK)
                    target = allowedUrl(redirectTarget(target, hop.location), ImageError.NETWORK)
                }
            }
        }
    }

    /** One request: either the body, or where the server says to look instead. */
    private sealed interface Hop {
        class Body(val bytes: ByteArray) : Hop
        class Redirect(val location: String?) : Hop
    }

    private fun fetchHop(target: URL, deadline: Long): Hop {
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
        var watchdog: ScheduledFuture<*>? = null
        try {
            // Scheduled inside the try, so that the connection is covered from the moment it
            // exists: a rejected task would otherwise escape unchecked and leak the connection.
            watchdog = WATCHDOG.schedule({
                expired.set(true)
                runCatching { connection.disconnect() }
            }, remainingMs(deadline), TimeUnit.MILLISECONDS)
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = clampedReadTimeout(deadline)
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
                return Hop.Redirect(connection.getHeaderField("Location"))
            }
            if (code !in 200..299) throw ImageFetchException(ImageError.NETWORK)
            if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw ImageFetchException(ImageError.TOO_LARGE)
            connection.readTimeout = clampedReadTimeout(deadline)
            val body = connection.inputStream.use { readCapped(it, deadline, declared) }
            // A connection closed by the watchdog can surface as a plain EOF rather than an error,
            // which would hand the caller a silently truncated image.
            if (expired.get()) throw ImageFetchException(ImageError.TIMEOUT)
            // Neither can a body that simply stops early. The declared length is no end-of-body
            // marker (a server that understates it keeps streaming, which is what the cap is for),
            // but falling short of it is a broken transfer, and half an image would otherwise reach
            // the decoder as MALFORMED — a terminal error the loader remembers for good.
            if (declared > 0 && body.size < declared) throw ImageFetchException(ImageError.NETWORK)
            return Hop.Body(body)
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
            watchdog?.cancel(false)
            // The watchdog thread may be inside disconnect() at the same moment. The platform's
            // implementation is unsynchronised and re-reads its connection field after checking it
            // for null, so the loser of that race can throw — and an unchecked exception thrown
            // here would replace the ImageFetchException that is already on its way out.
            runCatching { connection.disconnect() }
        }
    }

    /**
     * [supportedUrl], plus the question [hostPolicy] exists to answer. Asked again for every
     * redirect hop, because that is the only place the answer can still be acted on.
     *
     * A policy that throws refuses: this runs on hostile input, and the safe reading of "the check
     * could not be made" is not "let it through".
     *
     * **The two refusals are not the same error.** [syntaxFailure] is what the *spelling* of [url]
     * costs: [ImageError.UNSUPPORTED] for the URL the message carried, which cannot become a
     * different URL later, and [ImageError.NETWORK] for a `Location`, which is the server's answer
     * and not the message's fault. A refusal by [hostPolicy] is always [ImageError.NETWORK],
     * whichever of the two produced the URL, because the policy judges a **resolver answer** and
     * resolver answers change: a DNS blocker returns `0.0.0.0` for a blocked CDN, a captive portal
     * and a split-horizon company resolver return `192.168.x.x` for a public name. Reporting that
     * as terminal would leave the image broken for the life of the process however the network
     * changes — turning the blocker off, signing in, moving to another Wi-Fi. That is the very
     * argument [PublicHostsOnly] already makes for an unresolvable host.
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
     * Where a `Location` header points, resolved against the URL that produced it — a relative one
     * is legal and common. A redirect that changes the scheme is not followed at all, which is the
     * behaviour `HttpURLConnection` used to enforce here and the reason an https → `file:` redirect
     * reads nothing from disk.
     *
     * A missing or unparseable `Location` is [ImageError.NETWORK], not [ImageError.UNSUPPORTED]:
     * the source the message carried was fine, the server's answer was not, and only the first of
     * those two deserves to be remembered for the life of the process.
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
     * Parses [url] and accepts it only if it is an absolute http(s) URL with a non-empty host.
     * Purely syntactic: it opens nothing, so an unsupported source costs no I/O at all.
     *
     * The host is judged on the [URL] that is about to be opened, never on the [URI] it came from,
     * because the two parsers disagree about the very same authority. `URI.getHost()` is null for
     * anything registry-based (`my_host.invalid`, a non-ASCII name) although those really do name a
     * host, while `URLStreamHandler.parseURL` gives up on server-based parsing entirely once the
     * authority holds more than one `@` and leaves `getHost()` empty — for `http://a@b@c/x` the URI
     * side sees the host `c` and the URL side sees nothing. An empty host is not inert: it resolves
     * to localhost, so such a URL opens a socket to 127.0.0.1:80 (443 for https) with no DNS lookup
     * at all, and [ImageSource.parse] classifies it as `Remote`, so one chat message is enough.
     * Asking the chosen URL makes it structurally impossible for the gate and the connection to
     * disagree, which matching a second hand-written parse against the platform's never was.
     */
    private fun supportedUrl(url: String, failure: ImageError): URL {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            // Spaces, control characters, a stray CR/LF, ... — anything that is not a URL.
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
        /** Long enough for the usual canonicalisation chain, short enough to bound the work. */
        private const val MAX_REDIRECTS = 5
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val MAX_CAP = (Int.MAX_VALUE - 8).toLong()

        /** Shared, daemon: one idle thread for the whole app, and never a reason to keep it alive. */
        private val WATCHDOG: ScheduledExecutorService =
            ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "mumla-image-fetch-watchdog").apply { isDaemon = true }
            }.apply {
                // Off by default, which would leave every finished fetch's cancelled task in the
                // queue — and with it the HttpURLConnection the task captured — until its deadline,
                // i.e. for up to totalTimeoutMs after the fetch itself is long over.
                removeOnCancelPolicy = true
            }
    }
}
