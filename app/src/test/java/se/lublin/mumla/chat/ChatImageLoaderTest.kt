package se.lublin.mumla.chat

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatImageLoaderTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fetched = mutableListOf<String>()
    private var remoteBody: ByteArray = TestImages.png(300, 300)
    private var fetcher: ImageFetcher = ImageFetcher { url -> fetched += url; remoteBody }
    private var externalAllowed = true
    private val clock = AtomicLong(1_000_000L)
    private val url = "https://x.org/a.png"

    /**
     * Robolectric invents a 100x100 bitmap for undecodable data unless this is off, which would make
     * [malformedBytesFailAndTheFailureIsCached] green without decoding anything.
     */
    @Before
    fun realisticDecoding() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @Before
    fun watchTheDigest() {
        Sha1Probe.install()
    }

    @After
    fun dropTestLoader() {
        Sha1Probe.uninstall()
        ChatImageLoaders.setForTests(null)
    }

    private fun loader(
        maxCacheBytes: Long = 8L * 1024 * 1024,
        dispatcher: CoroutineDispatcher = this.dispatcher,
    ) = ChatImageLoader(fetcher, { externalAllowed }, maxCacheBytes, dispatcher, dispatcher, clock::get)

    @Test
    fun decodesDataUriToThumbnailBounds() = runTest(dispatcher) {
        val result = loader().loadThumbnail(TestImages.dataUri(TestImages.png(1000, 500)), 240, 240) as ImageResult.Ready
        assertThat(result.bitmap.width).isEqualTo(240)
        assertThat(result.bitmap.height).isEqualTo(120)
        assertThat(fetched).isEmpty()
    }

    @Test
    fun remoteImageIsFetchedOnceAndCached() = runTest(dispatcher) {
        val l = loader()
        val first = l.loadThumbnail(url, 240, 240) as ImageResult.Ready
        val second = l.loadThumbnail(url, 240, 240) as ImageResult.Ready
        assertThat(fetched).containsExactly(url)
        assertThat(second.bitmap).isSameInstanceAs(first.bitmap)
    }

    @Test
    fun thumbnailsOfDifferentBoundsAreCachedSeparately() = runTest(dispatcher) {
        val l = loader()
        val big = l.loadThumbnail(url, 240, 240) as ImageResult.Ready
        val small = l.loadThumbnail(url, 120, 120) as ImageResult.Ready
        assertThat(big.bitmap.width).isEqualTo(240)
        assertThat(small.bitmap.width).isEqualTo(120)
        assertThat(fetched).containsExactly(url) // second call reuses the last fetched bytes
    }

    @Test
    fun concurrentLoadsOfTheSameKeyShareOneFetch() = runTest {
        val l = loader(dispatcher = StandardTestDispatcher(testScheduler))
        val first = async { l.loadThumbnail(url, 240, 240) }
        val second = async { l.loadThumbnail(url, 240, 240) }
        advanceUntilIdle()
        assertThat(first.await()).isInstanceOf(ImageResult.Ready::class.java)
        assertThat(second.await()).isSameInstanceAs(first.await())
        assertThat(fetched).containsExactly(url)
    }

    /**
     * The classic RecyclerView failure: a row scrolls away and its job is cancelled while another
     * row is waiting on the very same fetch. The waiter must not be dragged down with it.
     */
    @Test
    fun cancellingOneCallerDoesNotCancelAnotherWaitingOnTheSameFetch() = runTest {
        val work = StandardTestDispatcher(TestCoroutineScheduler()) // advanced by hand, not by runTest
        // Unconfined for the decode side: loadThumbnail hashes the source on that dispatcher before
        // it ever reaches the shared fetch, and a TestDispatcher of a *second* scheduler cannot be
        // switched to from inside runTest's. What this test is about is the fetch, which stays on
        // `work` and therefore stays parked until this test advances it by hand.
        val l = ChatImageLoader(fetcher, { true }, 8L * 1024 * 1024, work, Dispatchers.Unconfined, clock::get)
        val scrolledAway = async { l.loadThumbnail(url, 240, 240) }
        val stillVisible = async { l.loadThumbnail(url, 240, 240) }
        runCurrent() // both are registered as waiters; the fetch is parked on `work`
        assertThat(fetched).isEmpty()

        scrolledAway.cancel()
        runCurrent()
        work.scheduler.advanceUntilIdle()
        advanceUntilIdle()

        assertThat(stillVisible.await()).isInstanceOf(ImageResult.Ready::class.java)
        assertThat(fetched).containsExactly(url)
    }

    /** The other half: when the last caller is gone, the download must not run on regardless. */
    @Test
    fun cancellingTheLastCallerAbandonsTheFetch() = runTest {
        val work = StandardTestDispatcher(TestCoroutineScheduler())
        val l = ChatImageLoader(fetcher, { true }, 8L * 1024 * 1024, work, Dispatchers.Unconfined, clock::get)
        val scrolledAway = async { l.loadThumbnail(url, 240, 240) }
        runCurrent()

        scrolledAway.cancel()
        runCurrent()
        work.scheduler.advanceUntilIdle()

        assertThat(fetched).isEmpty()
    }

    @Test
    fun malformedBytesFailAndTheFailureIsCached() = runTest(dispatcher) {
        remoteBody = "garbage".toByteArray()
        val l = loader()
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.MALFORMED))
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.MALFORMED))
        assertThat(fetched).hasSize(1)
    }

    @Test
    fun transientFetchErrorsAreCachedOnlyForTheTtl() = runTest(dispatcher) {
        var calls = 0
        fetcher = ImageFetcher { calls++; throw ImageFetchException(ImageError.TIMEOUT) }
        val l = loader()
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TIMEOUT))
        clock.addAndGet(ChatImageLoader.TRANSIENT_ERROR_TTL_MS - 1)
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TIMEOUT))
        assertThat(calls).isEqualTo(1)

        clock.addAndGet(2)
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TIMEOUT))
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun terminalFetchErrorsAreCachedForGood() = runTest(dispatcher) {
        var calls = 0
        fetcher = ImageFetcher { calls++; throw ImageFetchException(ImageError.TOO_LARGE) }
        val l = loader()
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
        clock.addAndGet(10 * ChatImageLoader.TRANSIENT_ERROR_TTL_MS)
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
        assertThat(calls).isEqualTo(1)
    }

    /**
     * The boundary itself, from the expired side: an entry whose expiry equals the current
     * millisecond is gone, not kept for one more. Without this the comparison could be either way
     * round and every other TTL test would stay green.
     */
    @Test
    fun aTransientErrorExpiresExactlyAtTheTtl() = runTest(dispatcher) {
        var calls = 0
        fetcher = ImageFetcher { calls++; throw ImageFetchException(ImageError.TIMEOUT) }
        val l = loader()
        l.loadThumbnail(url, 240, 240)
        clock.addAndGet(ChatImageLoader.TRANSIENT_ERROR_TTL_MS)
        l.loadThumbnail(url, 240, 240)
        assertThat(calls).isEqualTo(2)
    }

    /** A cached NETWORK failure must not survive as long as a cached bitmap does. */
    @Test
    fun networkFailuresExpireLikeTimeouts() = runTest(dispatcher) {
        var calls = 0
        fetcher = ImageFetcher { calls++; throw ImageFetchException(ImageError.NETWORK) }
        val l = loader()
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.NETWORK))
        clock.addAndGet(ChatImageLoader.TRANSIENT_ERROR_TTL_MS + 1)
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.NETWORK))
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun disabledExternalImagesAreNeitherFetchedNorCached() = runTest(dispatcher) {
        externalAllowed = false
        val l = loader()
        assertThat(l.loadThumbnail(url, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.EXTERNAL_DISABLED))
        assertThat(fetched).isEmpty()
        externalAllowed = true
        assertThat(l.loadThumbnail(url, 240, 240)).isInstanceOf(ImageResult.Ready::class.java)
    }

    /** The setting is about *external* images; an inline data: URI carries no request anywhere. */
    @Test
    fun disabledExternalImagesStillShowInlineDataUris() = runTest(dispatcher) {
        externalAllowed = false
        val result = loader().loadThumbnail(TestImages.dataUri(TestImages.png(60, 60)), 240, 240)
        assertThat(result).isInstanceOf(ImageResult.Ready::class.java)
    }

    @Test
    fun unsupportedSourceFails() = runTest(dispatcher) {
        assertThat(loader().loadThumbnail("ftp://x/a.png", 240, 240)).isEqualTo(ImageResult.Failed(ImageError.UNSUPPORTED))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedWhenOverBudget() = runTest(dispatcher) {
        // A 240x240 ARGB_8888 thumbnail is 230400 bytes (225 KiB); a 300 KiB budget fits exactly one.
        val l = loader(maxCacheBytes = 300L * 1024)
        l.loadThumbnail("https://x.org/a.png", 240, 240)
        l.loadThumbnail("https://x.org/b.png", 240, 240)
        l.loadThumbnail("https://x.org/a.png", 240, 240)
        assertThat(fetched).containsExactly("https://x.org/a.png", "https://x.org/b.png", "https://x.org/a.png").inOrder()
    }

    /**
     * Measured against the bitmaps the cache really holds, not against the cache's own accounting:
     * an [android.util.LruCache] sized in kibibytes rounds every entry down, so twenty-two-pixel
     * thumbnails of 2024 bytes each count as one KiB and ten of them fit a ten KiB budget — twice
     * what was asked for. The whole point of this cache is to bound memory, so the bound is what is
     * asserted.
     */
    @Test
    fun theCacheStaysWithinItsByteBudgetForManySmallBitmaps() = runTest(dispatcher) {
        remoteBody = TestImages.png(22, 23) // 22 * 23 * 4 = 2024 bytes decoded
        val budget = 10L * 1024
        val l = loader(maxCacheBytes = budget)
        repeat(10) { l.loadThumbnail("https://x.org/$it.png", 240, 240) }
        assertThat(l.cachedBitmapBytes()).isAtMost(budget)
        assertThat(l.cachedBitmapBytes()).isAtLeast(4 * 2024L) // and it is still a useful cache
    }

    @Test
    fun loadFullDecodesEveryTimeAndReusesTheFetchedBytes() = runTest(dispatcher) {
        val l = loader()
        val first = l.loadFull(url, 200, 200) as ImageResult.Ready
        val second = l.loadFull(url, 200, 200) as ImageResult.Ready
        assertThat(first.bitmap.width).isEqualTo(200)
        assertThat(second.bitmap).isNotSameInstanceAs(first.bitmap)
        assertThat(fetched).containsExactly(url)
    }

    /**
     * A view that has not been measured yet is 0 px wide, which is a legitimate state, not a bug —
     * but `BoundedBitmapDecoder` throws on a non-positive bound, and rightly so (a negative bound
     * hangs its sampling loop). The loader therefore has to catch this itself.
     */
    @Test
    fun nonPositiveBoundsSkipTheLoadInsteadOfCrashing() = runTest(dispatcher) {
        val l = loader()
        assertThat(l.loadThumbnail(url, 0, 240)).isEqualTo(ImageResult.Skipped)
        assertThat(l.loadThumbnail(url, 240, 0)).isEqualTo(ImageResult.Skipped)
        assertThat(l.loadThumbnail(url, -1, -1)).isEqualTo(ImageResult.Skipped)
        assertThat(l.loadFull(url, 0, 0)).isEqualTo(ImageResult.Skipped)
        assertThat(fetched).isEmpty()
        // Nothing was cached either, so the next bind — after layout — really loads.
        assertThat(l.loadThumbnail(url, 240, 240)).isInstanceOf(ImageResult.Ready::class.java)
    }

    /**
     * `ImageSource.parse` decodes the whole base64 payload before anything bounded sees it, so the
     * only place to bound it is the length of the source string. TOO_LARGE rather than MALFORMED is
     * what proves the payload was never decoded: these bytes are not an image, so a decode attempt
     * would have reported MALFORMED.
     */
    @Test
    fun anOversizedDataUriIsRefusedWithoutDecodingIt() = runTest(dispatcher) {
        val payload = "A".repeat(ChatImageLoader.MAX_SOURCE_LENGTH)
        assertThat(loader().loadThumbnail("data:image/png;base64,$payload", 240, 240))
            .isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
    }

    @Test
    fun aDataUriAtTheLengthLimitIsStillDecoded() = runTest(dispatcher) {
        val png = TestImages.png(20, 20)
        val source = TestImages.dataUri(png)
        val padded = source + " ".repeat(ChatImageLoader.MAX_SOURCE_LENGTH - source.length)
        assertThat(padded.length).isEqualTo(ChatImageLoader.MAX_SOURCE_LENGTH)
        assertThat(loader().loadThumbnail(padded, 240, 240)).isInstanceOf(ImageResult.Ready::class.java)
    }

    @Test
    fun fetchBytesReusesWhatTheThumbnailAlreadyDownloaded() = runTest(dispatcher) {
        val l = loader()
        l.loadThumbnail(url, 240, 240)
        assertThat(l.fetchBytes(url)).isEqualTo(remoteBody)
        assertThat(fetched).containsExactly(url)
    }

    @Test
    fun fetchBytesReportsAnUnsupportedSourceByThrowing() = runTest(dispatcher) {
        val l = loader()
        val thrown = try {
            l.fetchBytes("file:///etc/passwd")
            null
        } catch (e: ImageFetchException) {
            e
        }
        assertThat(thrown?.error).isEqualTo(ImageError.UNSUPPORTED)
    }

    /**
     * Holds [loads] loads inside the fetcher at once and reports how many ever got in together.
     * A `null` limit means the production default, which is the number the app really runs with.
     */
    private fun peakConcurrentFetches(loads: Int, maxConcurrentLoads: Int?): Int {
        val inFetch = AtomicInteger()
        val peak = AtomicInteger()
        val started = CountDownLatch(loads)
        val release = CountDownLatch(1)
        fetcher = ImageFetcher { _ ->
            started.countDown()
            val now = inFetch.incrementAndGet()
            peak.getAndUpdate { maxOf(it, now) }
            release.await(5, TimeUnit.SECONDS)
            inFetch.decrementAndGet()
            remoteBody
        }
        val l = if (maxConcurrentLoads == null) {
            ChatImageLoader(fetcher, { true }, 8L * 1024 * 1024, Dispatchers.IO, Dispatchers.Default, clock::get)
        } else {
            ChatImageLoader(
                fetcher, { true }, 8L * 1024 * 1024, Dispatchers.IO, Dispatchers.Default, clock::get,
                maxConcurrentLoads = maxConcurrentLoads,
            )
        }
        return runBlocking(Dispatchers.IO) {
            val jobs = (1..loads).map { async { l.loadThumbnail("https://x.org/$it.png", 240, 240) } }
            // Give every one of them a fair chance to reach the fetcher before looking at the peak.
            assertWithMessage("all %s loads reached the fetcher at once", loads)
                .that(started.await(1, TimeUnit.SECONDS)).isFalse()
            val whileHeld = peak.get()
            release.countDown()
            assertThat(jobs.awaitAll()).hasSize(loads)
            maxOf(whileHeld, peak.get())
        }
    }

    /**
     * Peak memory is the fetcher's cap per fetch in flight, so the number of fetches in flight is
     * part of the memory bound. Without the limit all four of these are in the fetcher at once.
     */
    @Test(timeout = 60_000)
    fun noMoreThanMaxConcurrentLoadsFetchAtTheSameTime() {
        assertThat(peakConcurrentFetches(loads = 4, maxConcurrentLoads = 2)).isEqualTo(2)
    }

    /**
     * And the *default*, which is the only value the app ever uses and which the test above hid by
     * always handing one in: raising it to 100 left the whole suite green.
     */
    @Test(timeout = 60_000)
    fun theDefaultLimitsThreeFetchesAtATime() {
        assertThat(peakConcurrentFetches(loads = 6, maxConcurrentLoads = null)).isEqualTo(3)
    }

    /**
     * The check existed but could never run: property initialisers go first, so `Semaphore(0)` threw
     * its own message before `init {}` was reached — and `Semaphore(-1)` is what would have been
     * worth a message of our own.
     */
    @Test
    fun aNonPositiveConcurrencyLimitIsRejectedByThisClass() {
        listOf(0, -1).forEach { limit ->
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                ChatImageLoader(fetcher, { true }, maxConcurrentLoads = limit)
            }
            assertWithMessage("message for maxConcurrentLoads = %s", limit)
                .that(thrown).hasMessageThat().contains("maxConcurrentLoads")
        }
    }

    /**
     * A remembered failure is a small object, but not free: a server that sends a thousand distinct
     * broken URLs would otherwise fill the map without ever paying for it, and the LRU would never
     * evict anything because nothing ever grew. At 256 bytes an entry a 1 KiB budget holds four, so
     * the fifth failure has to push the first one out and asking for it again really re-fetches.
     */
    @Test
    fun rememberedFailuresAreChargedAgainstTheBudget() = runTest(dispatcher) {
        var calls = 0
        fetcher = ImageFetcher { calls++; throw ImageFetchException(ImageError.TOO_LARGE) }
        val l = loader(maxCacheBytes = 1024)
        repeat(5) { l.loadThumbnail("https://x.org/$it.png", 240, 240) }
        assertThat(calls).isEqualTo(5)

        assertThat(l.loadThumbnail("https://x.org/0.png", 240, 240))
            .isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
        assertWithMessage("the oldest remembered failure was never evicted").that(calls).isEqualTo(6)
        // ... while one that is still in the cache costs nothing.
        l.loadThumbnail("https://x.org/4.png", 240, 240)
        assertThat(calls).isEqualTo(6)
    }

    @Test
    fun cacheKeysAreStableAndSourceSpecific() {
        assertThat(ChatImageLoader.cacheKey("abc")).isEqualTo(ChatImageLoader.cacheKey("abc"))
        assertThat(ChatImageLoader.cacheKey("abc")).isNotEqualTo(ChatImageLoader.cacheKey("abd"))
        // Used as a file name by ImageShareExporter (Task 8), so it must stay filesystem-safe.
        assertThat(ChatImageLoader.cacheKey("a/b?c=d")).matches("[0-9a-f]{40}")
    }

    /**
     * `fetcher: ImageFetcher = HttpImageFetcher()` is the single line that gives the process-wide
     * loader its host policy, its byte cap, its timeouts and its redirect limit, and nothing used to
     * look at it: replacing it with `ImageFetcher { ByteArray(0) }` left the whole suite green, and
     * the only test on the real constructor path checked instance identity and never loaded
     * anything. This is the same argument the fetcher's own
     * `theDefaultPolicyRefusesTheDevicesOwnNetworkWithoutOpeningAnything` makes one layer down.
     *
     * Both ends of the production path are exercised: the default constructor argument, and
     * `ChatImageLoaders.get`, which is what a chat row actually calls and which brings the real
     * `Settings` lookup with it. A loopback `<img src>` must be refused without the server ever
     * being asked — a stub fetcher would report MALFORMED for its empty bytes instead, and a
     * fetcher wired to `ANY_HOST` would trip the wire.
     */
    @Test(timeout = 120_000)
    fun theRealLoaderIsWiredToTheRealFetcherAndItsHostPolicy() = runBlocking {
        val reached = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/wired.png") { exchange ->
            reached.set(true)
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.start()
        try {
            val target = "http://127.0.0.1:${server.address.port}/wired.png"

            val fromTheDefaultArgument = ChatImageLoader(externalImagesAllowed = { true })
                .loadThumbnail(target, 240, 240)
            val processWide = ChatImageLoaders.get(ApplicationProvider.getApplicationContext())
                .loadThumbnail(target, 240, 240)

            // The tripwire first: the error code is the weaker claim, and a fetcher wired to
            // ANY_HOST would fail this one and the codes both.
            assertWithMessage("a loopback <img src> from a chat message was fetched")
                .that(reached.get()).isFalse()
            assertWithMessage("the default constructor argument").that(fromTheDefaultArgument)
                .isEqualTo(ImageResult.Failed(ImageError.NETWORK))
            assertWithMessage("ChatImageLoaders.get").that(processWide)
                .isEqualTo(ImageResult.Failed(ImageError.NETWORK))
        } finally {
            server.stop(0)
        }
        Unit
    }

    @Test
    fun theProcessWideLoaderIsSharedAndOverridableForTests() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(ChatImageLoaders.get(context)).isSameInstanceAs(ChatImageLoaders.get(context))
        val stub = loader()
        ChatImageLoaders.setForTests(stub)
        assertThat(ChatImageLoaders.get(context)).isSameInstanceAs(stub)
    }

    // --- Where the key is computed. cacheKey() hashes the whole source, and for a `data:` source
    // --- the source *is* the image — megabytes of it. Everything below is about making sure that
    // --- work never happens on the thread that binds the row.

    /**
     * The control for the two tests below: they assert that SHA-1 was *not* invoked, or not on a
     * particular thread, and a count of zero is also what a probe that never took effect looks like.
     */
    @Test
    fun theDigestProbeReallySeesThisClassesHashing() {
        Sha1Probe.reset()
        ChatImageLoader.cacheKey("abc")
        assertThat(Sha1Probe.calls()).isEqualTo(1)
        assertThat(Sha1Probe.threads()).contains(Thread.currentThread().name.substringBefore(" @"))
    }

    /**
     * The length cap is the whole defence against a megabytes-long source, so nothing expensive may
     * run before it — and hashing the source is the most expensive thing this class does. The error
     * code alone cannot tell the two orders apart, so the digest itself is counted.
     */
    @Test
    fun anOversizedSourceIsRefusedBeforeItsKeyIsComputed() = runTest(dispatcher) {
        val l = loader()
        val source = "data:image/png;base64," + "A".repeat(ChatImageLoader.MAX_SOURCE_LENGTH)
        Sha1Probe.reset()

        assertThat(l.loadThumbnail(source, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
        assertThat(l.loadFull(source, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))

        assertWithMessage("SHA-1 invocations while refusing a %s character source", source.length)
            .that(Sha1Probe.calls()).isEqualTo(0)
    }

    /**
     * The expensive half: a `data:` source at the cap is 7 MB, and every bind needs its key — cache
     * hits included, because the key is what the lookup is by. Doing that on the thread that called
     * `loadThumbnail` is 7 MB allocated and hashed on the main thread per bound row, which is the
     * "not responding" disease this whole stream exists to cure. So: which threads hashed, and how
     * much did the caller's thread allocate.
     */
    @Test(timeout = 120_000)
    fun bindingNeverHashesTheSourceOnTheCallersThread() {
        val small = TestImages.dataUri(TestImages.png(40, 40))
        val source = small + " ".repeat(ChatImageLoader.MAX_SOURCE_LENGTH - small.length)
        val callerName = "mumla-test-caller"
        val caller = Executors.newSingleThreadExecutor { r -> Thread(r, callerName) }
        try {
            val l = ChatImageLoader(
                fetcher, { true }, 8L * 1024 * 1024, Dispatchers.IO, Dispatchers.Default, clock::get,
            )
            // First bind: a miss, so the key is computed and the image is decoded.
            Sha1Probe.reset()
            val first = caller.submit<ImageResult> { runBlocking { l.loadThumbnail(source, 240, 240) } }.get()
            assertThat(first).isInstanceOf(ImageResult.Ready::class.java)
            assertWithMessage("something has to hash the source").that(Sha1Probe.calls()).isAtLeast(1)
            assertWithMessage("threads that hashed a %s character source on a miss", source.length)
                .that(Sha1Probe.threads()).doesNotContain(callerName)

            // Second bind: a cache hit. The key is still needed, so this is the common case.
            Sha1Probe.reset()
            val second = caller.submit<ImageResult> { runBlocking { l.loadThumbnail(source, 240, 240) } }.get()
            assertThat(second).isInstanceOf(ImageResult.Ready::class.java)
            assertWithMessage("a cache hit still needs the key").that(Sha1Probe.calls()).isAtLeast(1)
            assertWithMessage("threads that hashed a %s character source on a hit", source.length)
                .that(Sha1Probe.threads()).doesNotContain(callerName)
        } finally {
            caller.shutdownNow()
        }
    }

    /**
     * The key is hashed in chunks so that no full UTF-8 copy of a megabytes-long source is ever
     * allocated. That is only allowed to be cheaper, never different: a chunk boundary that fell
     * between the halves of a surrogate pair would encode them apart and silently change the key of
     * every source long enough to have one.
     */
    @Test
    fun theKeyIsTheSha1OfTheWholeSourceHoweverLongItIs() {
        fun reference(s: String) = MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        listOf(
            "abc",
            "",
            "a".repeat(8_192),
            "a".repeat(8_191) + "\uD83D\uDE00" + "b".repeat(20_000), // the pair straddles a boundary
            "\u00e4\u20ac\uD83D\uDE00".repeat(5_000),
        ).forEach {
            assertWithMessage("key of a %s character source", it.length)
                .that(ChatImageLoader.cacheKey(it)).isEqualTo(reference(it))
        }
    }
}

/**
 * A JCE provider that hands out a recording SHA-1. Installed for the life of each test, it counts
 * every `MessageDigest.getInstance("SHA-1")` and records the thread that pushed bytes through it —
 * which is the only way from outside to tell *where* a key was computed, rather than what it was.
 * The digest itself is delegated to whatever provider would otherwise have answered, so nothing
 * about the resulting key changes.
 */
class RecordingSha1 : MessageDigestSpi() {
    private val delegate: MessageDigest = Sha1Probe.realSha1()

    init {
        Sha1Probe.countInstance()
    }

    override fun engineUpdate(input: Byte) {
        Sha1Probe.countThread()
        delegate.update(input)
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        Sha1Probe.countThread()
        delegate.update(input, offset, len)
    }

    override fun engineDigest(): ByteArray = delegate.digest()

    override fun engineReset() = delegate.reset()
}

object Sha1Probe {
    private const val NAME = "MumlaSha1Probe"
    private val instances = AtomicInteger()
    private val threadNames = ConcurrentHashMap.newKeySet<String>()

    @Suppress("DEPRECATION") // the (String, String, String) constructor is not in the Android API
    private class ProbeProvider : Provider(NAME, 1.0, "records who computes a SHA-1, and where") {
        init {
            putService(Service(this, "MessageDigest", "SHA-1", RecordingSha1::class.java.name, null, null))
        }
    }

    fun install() {
        if (Security.getProvider(NAME) == null) Security.insertProviderAt(ProbeProvider(), 1)
        reset()
    }

    fun uninstall() = Security.removeProvider(NAME)

    fun reset() {
        instances.set(0)
        threadNames.clear()
    }

    fun calls(): Int = instances.get()

    fun threads(): Set<String> = threadNames.toSet()

    fun countInstance() {
        instances.incrementAndGet()
    }

    /**
     * The *bare* thread name: kotlinx.coroutines renames a thread to "name @coroutine#n" for as long
     * as a coroutine runs on it, so recording the name verbatim would make every assertion about a
     * named thread pass for the wrong reason.
     */
    fun countThread() {
        threadNames += Thread.currentThread().name.substringBefore(" @")
    }

    /** The digest that would have answered without the probe in the way. */
    fun realSha1(): MessageDigest = Security.getProviders("MessageDigest.SHA-1")
        .first { it.name != NAME }
        .let { MessageDigest.getInstance("SHA-1", it) }
}
