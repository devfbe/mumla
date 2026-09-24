package se.lublin.mumla.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.network.HttpException
import coil3.network.NetworkResponse
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import se.lublin.mumla.Settings
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatImageLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private var externalAllowed = true
    private val clock = AtomicLong(1_000_000L)

    @Before
    fun startServer() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
        ChatImageLoaders.setForTests(null)
        Settings.getInstance(context).isTorEnabled = false
    }

    /** The real client and Coil set-up, except that the test server's loopback address counts as public. */
    private fun loader(maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES): ChatImageLoader {
        val http = chatImageHttpClient(
            networkAllowed = { externalAllowed },
            userAgent = "Mumla/test",
            policy = AddressPolicy { it.isLoopbackAddress },
            maxBytes = maxBytes,
        )
        return ChatImageLoader(
            context, ChatImageLoader.imageLoader(context, http), http, { externalAllowed }, nowMillis = clock::get,
        )
    }

    private fun url(path: String = "/a.png") = server.url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun serve(bytes: ByteArray) = server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())

    private fun ImageResult.size(): Pair<Int, Int> =
        (this as ImageResult.Ready).bitmap.let { it.width to it.height }

    // Thumbnails.

    @Test
    fun aThumbnailFitsTheBoundsKeepsTheAspectRatioAndIsNeverEnlarged() = runBlocking {
        val loader = loader()
        assertThat(loader.loadThumbnail(TestImages.dataUri(TestImages.png(300, 300)), 240, 240).size())
            .isEqualTo(240 to 240)
        assertThat(loader.loadThumbnail(TestImages.dataUri(TestImages.png(480, 240)), 240, 240).size())
            .isEqualTo(240 to 120)
        assertThat(loader.loadThumbnail(TestImages.dataUri(TestImages.png(100, 50)), 240, 240).size())
            .isEqualTo(100 to 50)
    }

    @Test
    fun aRemoteThumbnailIsFetchedThroughTheGuardedClientOnceAndThenServedFromMemory() = runBlocking {
        serve(TestImages.png(300, 300))
        val loader = loader()

        assertThat(loader.loadThumbnail(url(), 240, 240).size()).isEqualTo(240 to 240)
        assertThat(loader.loadThumbnail(url(), 240, 240).size()).isEqualTo(240 to 240)

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("Mumla/test")
    }

    @Test
    fun malformedBytesFailAndTheFailureIsRememberedForGood() = runBlocking {
        serve("not an image".toByteArray())
        val loader = loader()

        assertThat(loader.loadThumbnail(url(), 240, 240)).isEqualTo(ImageResult.Failed(ImageError.MALFORMED))
        clock.addAndGet(365L * 24 * 60 * 60 * 1000)
        assertThat(loader.loadThumbnail(url(), 240, 240)).isEqualTo(ImageResult.Failed(ImageError.MALFORMED))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun aNetworkFailureIsRememberedForTheTtlAndThenRetried() = runBlocking {
        server.enqueue(MockResponse(404))
        serve(TestImages.png(40, 40))
        val loader = loader()

        assertThat(loader.loadThumbnail(url(), 240, 240)).isEqualTo(ImageResult.Failed(ImageError.NETWORK))
        clock.addAndGet(ChatImageLoader.TRANSIENT_ERROR_TTL_MS - 1)
        assertThat(loader.loadThumbnail(url(), 240, 240)).isEqualTo(ImageResult.Failed(ImageError.NETWORK))
        assertThat(server.requestCount).isEqualTo(1)

        clock.addAndGet(1)
        assertThat(loader.loadThumbnail(url(), 240, 240).size()).isEqualTo(40 to 40)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun aBodyOverTheByteCapIsTooLarge() = runBlocking {
        serve(TestImages.png(300, 300))
        assertThat(loader(maxBytes = 100).loadThumbnail(url(), 240, 240))
            .isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
    }

    /** The setting (and Tor, which turns it off) can change while the log is on screen. */
    @Test
    fun disabledExternalImagesAreNeitherFetchedNorRemembered() = runBlocking {
        serve(TestImages.png(40, 40))
        val loader = loader()
        externalAllowed = false

        assertThat(loader.loadThumbnail(url(), 240, 240))
            .isEqualTo(ImageResult.Failed(ImageError.EXTERNAL_DISABLED))
        assertThat(server.requestCount).isEqualTo(0)

        externalAllowed = true
        assertThat(loader.loadThumbnail(url(), 240, 240).size()).isEqualTo(40 to 40)
    }

    @Test
    fun disabledExternalImagesStillShowInlineDataUris() = runBlocking {
        externalAllowed = false
        assertThat(loader().loadThumbnail(TestImages.dataUri(TestImages.png(40, 40)), 240, 240).size())
            .isEqualTo(40 to 40)
    }

    @Test
    fun sourcesThatAreNeitherHttpNorAnImageDataUriAreUnsupported() = runBlocking {
        val loader = loader()
        for (source in listOf(
            "file:///etc/passwd", "content://x/y", "javascript:alert(1)", "//x.org/a.png", "ftp://x.org/a.png",
            "data:text/html;base64,PGI+", "data:image/png,plain", "",
        )) {
            assertWithMessage(source).that(loader.loadThumbnail(source, 240, 240))
                .isEqualTo(ImageResult.Failed(ImageError.UNSUPPORTED))
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun anOversizedDataUriIsRefusedWithoutDecodingIt() = runBlocking {
        val source = "data:image/png;base64," + "A".repeat(ChatImageLoader.MAX_SOURCE_LENGTH)
        assertThat(loader().loadThumbnail(source, 240, 240)).isEqualTo(ImageResult.Failed(ImageError.TOO_LARGE))
        assertThrows(ImageFetchException::class.java) { runBlocking { loader().fetchBytes(source) } }
            .also { assertThat(it.error).isEqualTo(ImageError.TOO_LARGE) }
        Unit
    }

    @Test
    fun aDataUriAtTheLengthLimitIsStillDecoded() = runBlocking {
        val small = TestImages.dataUri(TestImages.png(40, 40))
        val source = small + " ".repeat(ChatImageLoader.MAX_SOURCE_LENGTH - small.length)
        assertThat(source.length).isEqualTo(ChatImageLoader.MAX_SOURCE_LENGTH)
        assertThat(loader().loadThumbnail(source, 240, 240).size()).isEqualTo(40 to 40)
    }

    @Test
    fun nonPositiveBoundsSkipTheLoad() = runBlocking {
        val loader = loader()
        assertThat(loader.loadThumbnail(url(), 0, 240)).isEqualTo(ImageResult.Skipped)
        assertThat(loader.loadThumbnail(url(), 240, -1)).isEqualTo(ImageResult.Skipped)
        assertThat(loader.decodeFull(TestImages.png(4, 4), 0, 0)).isEqualTo(ImageResult.Skipped)
        assertThat(server.requestCount).isEqualTo(0)
    }

    // The viewer's path.

    @Test
    fun fetchBytesReturnsExactlyWhatWasServed() = runBlocking {
        val png = TestImages.png(40, 40)
        serve(png)
        val loader = loader()
        assertThat(loader.fetchBytes(url())).isEqualTo(png)
        assertThat(loader.fetchBytes(TestImages.dataUri(png))).isEqualTo(png)
    }

    @Test
    fun fetchBytesReportsEveryRefusalByThrowing() {
        fun errorOf(loader: ChatImageLoader, source: String) =
            assertThrows(ImageFetchException::class.java) { runBlocking { loader.fetchBytes(source) } }.error

        serve(TestImages.png(300, 300))
        assertThat(errorOf(loader(maxBytes = 100), url())).isEqualTo(ImageError.TOO_LARGE)
        server.enqueue(MockResponse(500))
        assertThat(errorOf(loader(), url())).isEqualTo(ImageError.NETWORK)
        assertThat(errorOf(loader(), "file:///etc/passwd")).isEqualTo(ImageError.UNSUPPORTED)

        externalAllowed = false
        val requests = server.requestCount
        assertThat(errorOf(loader(), url())).isEqualTo(ImageError.EXTERNAL_DISABLED)
        assertThat(server.requestCount).isEqualTo(requests)
    }

    @Test
    fun decodeFullIsBoundedAndNeverCached() = runBlocking {
        val loader = loader()
        val bytes = TestImages.png(2000, 1000)
        val first = loader.decodeFull(bytes, 640, 940) as ImageResult.Ready
        val second = loader.decodeFull(bytes, 640, 940) as ImageResult.Ready

        assertThat(first.bitmap.width to first.bitmap.height).isEqualTo(640 to 320)
        assertThat(second.bitmap).isNotSameInstanceAs(first.bitmap)
        assertThat(loader.decodeFull("nope".toByteArray(), 640, 940))
            .isEqualTo(ImageResult.Failed(ImageError.MALFORMED))
    }

    // The production wiring.

    /** The process-wide loader refuses a loopback `<img src>` without the server ever being asked. */
    @Test
    fun theRealLoaderRefusesTheLocalNetwork() = runBlocking {
        serve(TestImages.png(40, 40))
        val result = ChatImageLoaders.get(context).loadThumbnail(url(), 240, 240)

        assertWithMessage("a loopback <img src> from a chat message was fetched")
            .that(server.requestCount).isEqualTo(0)
        assertThat(result).isEqualTo(ImageResult.Failed(ImageError.NETWORK))
    }

    @Test
    fun theRealLoaderLoadsNothingExternalUnderTor() = runBlocking {
        Settings.getInstance(context).isTorEnabled = true
        val loader = ChatImageLoaders.get(context)

        assertThat(loader.loadThumbnail("https://example.org/a.png", 240, 240))
            .isEqualTo(ImageResult.Failed(ImageError.EXTERNAL_DISABLED))
        assertThat(assertThrows(ImageFetchException::class.java) {
            runBlocking { loader.fetchBytes("https://example.org/a.png") }
        }.error).isEqualTo(ImageError.EXTERNAL_DISABLED)
        // Inline images carry no traffic and stay visible.
        assertThat(loader.loadThumbnail(TestImages.dataUri(TestImages.png(8, 8)), 240, 240).size())
            .isEqualTo(8 to 8)
    }

    @Test
    fun theProcessWideLoaderIsSharedAndOverridableForTests() {
        assertThat(ChatImageLoaders.get(context)).isSameInstanceAs(ChatImageLoaders.get(context))
        val stub = loader()
        ChatImageLoaders.setForTests(stub)
        assertThat(ChatImageLoaders.get(context)).isSameInstanceAs(stub)
    }

    @Test
    fun failuresMapOntoImageErrors() {
        val http404 = HttpException(NetworkResponse(code = 404))
        assertThat(ChatImageLoader.errorOf(ImageRefusedException(ImageError.TOO_LARGE)))
            .isEqualTo(ImageError.TOO_LARGE)
        assertThat(ChatImageLoader.errorOf(ImageFetchException(ImageError.UNSUPPORTED)))
            .isEqualTo(ImageError.UNSUPPORTED)
        assertThat(ChatImageLoader.errorOf(SocketTimeoutException())).isEqualTo(ImageError.TIMEOUT)
        assertThat(ChatImageLoader.errorOf(http404)).isEqualTo(ImageError.NETWORK)
        assertThat(ChatImageLoader.errorOf(IllegalStateException(ImageRefusedException(ImageError.TOO_LARGE))))
            .isEqualTo(ImageError.TOO_LARGE)
        assertThat(ChatImageLoader.errorOf(IllegalStateException("BitmapFactory returned a null bitmap")))
            .isEqualTo(ImageError.MALFORMED)
    }

    // The key, also the share file's name.

    @Test
    fun cacheKeysAreStableAndSourceSpecific() {
        assertThat(ChatImageLoader.cacheKey("abc")).isEqualTo(ChatImageLoader.cacheKey("abc"))
        assertThat(ChatImageLoader.cacheKey("abc")).isNotEqualTo(ChatImageLoader.cacheKey("abd"))
        // Used as a file name by ImageShareExporter, so it must stay filesystem-safe.
        assertThat(ChatImageLoader.cacheKey("a/b?c=d")).matches("[0-9a-f]{40}")
    }

    /**
     * The key is hashed in chunks to avoid a full UTF-8 copy; a chunk boundary between the halves
     * of a surrogate pair must not change the key.
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
            "a".repeat(8_191) + "😀" + "b".repeat(20_000), // the pair straddles a boundary
            "ä€😀".repeat(5_000),
        ).forEach {
            assertWithMessage("key of a %s character source", it.length)
                .that(ChatImageLoader.cacheKey(it)).isEqualTo(reference(it))
        }
    }
}
