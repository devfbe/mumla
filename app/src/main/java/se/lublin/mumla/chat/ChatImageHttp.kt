package se.lublin.mumla.chat

import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The OkHttp client for `<img src>` URLs from chat messages. The URL comes from another client, so
 * it is hostile input:
 *
 *  * **Gate.** While [networkAllowed] is false (external images off, or Tor on) every request fails
 *    with [ImageError.EXTERNAL_DISABLED] before anything is resolved.
 *  * **Destination.** [policy] judges every address a name resolves to, every IP literal (which
 *    OkHttp connects to without asking [Dns]) and the address actually connected to. A refusal is
 *    [ImageError.NETWORK], since resolver answers change with the network. No proxy is used, so the
 *    checked address is the one connected to.
 *  * **Redirects.** Followed here rather than by OkHttp, at most [MAX_REDIRECTS], same scheme only,
 *    each hop through the same checks.
 *  * **Size.** At most [maxBytes] of body, checked against `Content-Length` and while streaming.
 *    Compression is not requested, so the cap is on what reaches the decoder.
 *  * **Time.** Connect and read timeouts per operation, plus a total budget for the whole call.
 *
 * No cookies and no HTTP cache.
 */
fun chatImageHttpClient(
    networkAllowed: () -> Boolean,
    userAgent: String,
    policy: AddressPolicy = AddressPolicy.PUBLIC_ONLY,
    dns: Dns = Dns.SYSTEM,
    maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
): OkHttpClient = OkHttpClient.Builder()
    .dns(PolicyDns(policy, dns))
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    // Each response body can be up to maxBytes in memory; this bounds how many are in flight.
    .dispatcher(Dispatcher().apply { maxRequests = MAX_CONCURRENT_REQUESTS })
    .addInterceptor(RequestGuard(networkAllowed, userAgent, policy, maxBytes))
    .addNetworkInterceptor(ConnectedAddressGuard(policy))
    .build()

const val DEFAULT_MAX_IMAGE_BYTES = 5L * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val READ_TIMEOUT_MS = 10_000L
private const val CALL_TIMEOUT_MS = 20_000L
private const val MAX_CONCURRENT_REQUESTS = 3

/** Long enough for the usual canonicalisation chain, short enough to bound the work. */
private const val MAX_REDIRECTS = 5

/** What OkHttp treats as an IP literal and connects to without a [Dns] lookup. */
private val IP_LITERAL = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")

/** Refuses a name when any of its addresses is refused: the connection may use any of them. */
private class PolicyDns(private val policy: AddressPolicy, private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.any { !policy.isAllowed(it) }) throw UnknownHostException("refused: $hostname")
        return addresses
    }
}

private class RequestGuard(
    private val networkAllowed: () -> Boolean,
    private val userAgent: String,
    private val policy: AddressPolicy,
    private val maxBytes: Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            // OkHttp inflates gzip after the network interceptors, past any cap applied there.
            .header("Accept-Encoding", "identity")
            .build()
        var redirects = 0
        while (true) {
            if (!networkAllowed()) throw ImageRefusedException(ImageError.EXTERNAL_DISABLED)
            checkLiteral(request.url.host)
            val response = chain.proceed(request)
            if (!response.isRedirect) return capped(response)
            val location = response.header("Location")
            response.close()
            if (++redirects > MAX_REDIRECTS) throw ImageRefusedException(ImageError.NETWORK)
            val next = location?.let { request.url.resolve(it) }
            // A scheme change is refused both ways: no https -> http downgrade, and no upgrade either.
            if (next == null || next.scheme != request.url.scheme) {
                throw ImageRefusedException(ImageError.NETWORK)
            }
            request = request.newBuilder().url(next).build()
        }
    }

    /** OkHttp connects to an IP literal without asking [Dns]; judge it before any socket exists. */
    private fun checkLiteral(host: String) {
        if (!IP_LITERAL.matches(host)) return
        val address = try {
            InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            throw ImageRefusedException(ImageError.NETWORK, e)
        }
        if (!policy.isAllowed(address)) throw ImageRefusedException(ImageError.NETWORK)
    }

    private fun capped(response: Response): Response {
        val body = response.body
        if (body.contentLength() > maxBytes) {
            response.close()
            throw ImageRefusedException(ImageError.TOO_LARGE)
        }
        return response.newBuilder().body(CappedBody(body, maxBytes)).build()
    }
}

/** The last word on the destination: the address the socket really went to, before the request is sent. */
private class ConnectedAddressGuard(private val policy: AddressPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address == null || !policy.isAllowed(address)) throw ImageRefusedException(ImageError.NETWORK)
        return chain.proceed(chain.request())
    }
}

/** Fails with [ImageError.TOO_LARGE] as soon as more than [maxBytes] have been read. */
private class CappedBody(private val delegate: ResponseBody, private val maxBytes: Long) : ResponseBody() {
    private val capped: BufferedSource = object : ForwardingSource(delegate.source()) {
        private var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            // One byte past the cap is enough to prove the body is too large.
            val read = super.read(sink, minOf(byteCount, maxBytes - total + 1))
            if (read > 0) {
                total += read
                if (total > maxBytes) throw ImageRefusedException(ImageError.TOO_LARGE)
            }
            return read
        }
    }.buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source(): BufferedSource = capped
}
