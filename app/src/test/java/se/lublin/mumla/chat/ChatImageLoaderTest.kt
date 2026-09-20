package se.lublin.mumla.chat

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @After
    fun dropTestLoader() {
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
        val l = ChatImageLoader(fetcher, { true }, 8L * 1024 * 1024, work, work, clock::get)
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
        val l = ChatImageLoader(fetcher, { true }, 8L * 1024 * 1024, work, work, clock::get)
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
     * Peak memory is the fetcher's cap per fetch in flight, so the number of fetches in flight is
     * part of the memory bound. Without the limit all four of these are in the fetcher at once.
     */
    @Test
    fun noMoreThanMaxConcurrentLoadsFetchAtTheSameTime() = runBlocking(Dispatchers.IO) {
        val inFetch = AtomicInteger()
        val peak = AtomicInteger()
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        fetcher = ImageFetcher { _ ->
            started.countDown()
            val now = inFetch.incrementAndGet()
            peak.getAndUpdate { maxOf(it, now) }
            release.await(5, TimeUnit.SECONDS)
            inFetch.decrementAndGet()
            remoteBody
        }
        val l = ChatImageLoader(
            fetcher, { true }, 8L * 1024 * 1024, Dispatchers.IO, Dispatchers.Default, clock::get,
            maxConcurrentLoads = 2,
        )
        val jobs = (1..4).map { async { l.loadThumbnail("https://x.org/$it.png", 240, 240) } }
        // Give every one of the four a fair chance to reach the fetcher before looking at the peak.
        assertThat(started.await(1, TimeUnit.SECONDS)).isFalse()
        assertThat(peak.get()).isEqualTo(2)
        release.countDown()
        assertThat(jobs.awaitAll()).hasSize(4)
        assertThat(peak.get()).isEqualTo(2)
        Unit
    }

    @Test
    fun cacheKeysAreStableAndSourceSpecific() {
        assertThat(ChatImageLoader.cacheKey("abc")).isEqualTo(ChatImageLoader.cacheKey("abc"))
        assertThat(ChatImageLoader.cacheKey("abc")).isNotEqualTo(ChatImageLoader.cacheKey("abd"))
        // Used as a file name by ImageShareExporter (Task 8), so it must stay filesystem-safe.
        assertThat(ChatImageLoader.cacheKey("a/b?c=d")).matches("[0-9a-f]{40}")
    }

    @Test
    fun theProcessWideLoaderIsSharedAndOverridableForTests() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(ChatImageLoaders.get(context)).isSameInstanceAs(ChatImageLoaders.get(context))
        val stub = loader()
        ChatImageLoaders.setForTests(stub)
        assertThat(ChatImageLoaders.get(context)).isSameInstanceAs(stub)
    }
}
