package se.lublin.mumla.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.imageDecoderEnabled
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.serviceLoaderEnabled
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okio.Buffer
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.Settings
import se.lublin.mumla.util.toHex
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import coil3.decode.ImageSource as CoilImageSource
import coil3.request.ImageResult as CoilImageResult

/** What a load of one chat image ended in. */
sealed class ImageResult {
    /** Shared and cached: owned by the loader. Draw it, never recycle or mutate it. */
    class Ready(val bitmap: Bitmap) : ImageResult()

    data class Failed(val error: ImageError) : ImageResult()

    /** Bounds were not positive (view not measured yet). Not cached: ask again after layout. */
    data object Skipped : ImageResult()
}

/**
 * Loads chat images off the main thread: Coil decodes and caches the bitmaps (memory only, never on
 * disk), [http] fetches remote ones.
 *
 * Remote sources are only fetched while [externalImagesAllowed]; [http] must enforce the same gate
 * and the destination rules on its own (see [chatImageHttpClient]). Failures are remembered per
 * source: terminal ones for good, [ImageError.NETWORK]/[ImageError.TIMEOUT] for
 * [TRANSIENT_ERROR_TTL_MS], [ImageError.EXTERNAL_DISABLED] never (the setting can change while the
 * log is on screen).
 */
class ChatImageLoader(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val http: Call.Factory,
    private val externalImagesAllowed: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Failure(val error: ImageError, val expiresAtMillis: Long)

    private val failures = LruCache<String, Failure>(MAX_REMEMBERED_FAILURES)

    /**
     * Thumbnail bounded by [maxWidth] x [maxHeight] px, never enlarged. Non-positive bounds (an
     * unmeasured view) yield [ImageResult.Skipped].
     */
    suspend fun loadThumbnail(source: String, maxWidth: Int, maxHeight: Int): ImageResult {
        if (maxWidth <= 0 || maxHeight <= 0) return ImageResult.Skipped
        if (source.length > MAX_SOURCE_LENGTH) return ImageResult.Failed(ImageError.TOO_LARGE)
        // O(length), and a data: source is the whole image.
        val key = withContext(Dispatchers.Default) { cacheKey(source) }
        failure(key)?.let { return ImageResult.Failed(it) }

        val data: Any = if (source.trimStart().startsWith(DATA_PREFIX, ignoreCase = true)) {
            InlineImage(key, source)
        } else {
            when (val parsed = ImageSource.parse(source)) {
                is ImageSource.Remote -> {
                    if (!externalImagesAllowed()) return ImageResult.Failed(ImageError.EXTERNAL_DISABLED)
                    parsed.url.toString()
                }
                else -> return remember(key, ImageResult.Failed(ImageError.UNSUPPORTED))
            }
        }
        return remember(key, decode(data, maxWidth, maxHeight, cached = true))
    }

    /**
     * Raw bytes of [source]; throws [ImageFetchException]. The array is the caller's; the viewer
     * decodes it with [decodeFull] and shares exactly these bytes.
     */
    suspend fun fetchBytes(source: String): ByteArray = withContext(ioDispatcher) {
        when (val parsed = ImageSource.parse(source)) {
            is ImageSource.Data -> parsed.bytes
            is ImageSource.Remote -> {
                if (!externalImagesAllowed()) throw ImageFetchException(ImageError.EXTERNAL_DISABLED)
                download(parsed.url)
            }
            ImageSource.TooLarge, ImageSource.Unsupported -> throw ImageFetchException(
                if (parsed == ImageSource.TooLarge) ImageError.TOO_LARGE else ImageError.UNSUPPORTED,
            )
        }
    }

    /** Decodes [bytes] bounded by the given size (e.g. the screen); never cached. */
    suspend fun decodeFull(bytes: ByteArray, maxWidth: Int, maxHeight: Int): ImageResult {
        if (maxWidth <= 0 || maxHeight <= 0) return ImageResult.Skipped
        return decode(bytes, maxWidth, maxHeight, cached = false)
    }

    /** Blocking. */
    private fun download(url: HttpUrl): ByteArray = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ImageRefusedException(ImageError.NETWORK)
            response.body.bytes()
        }
    } catch (e: IOException) {
        throw ImageFetchException(errorOf(e), e)
    }

    private suspend fun decode(data: Any, maxWidth: Int, maxHeight: Int, cached: Boolean): ImageResult {
        val policy = if (cached) CachePolicy.ENABLED else CachePolicy.DISABLED
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(maxWidth, maxHeight)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .memoryCachePolicy(policy)
            .build()
        return when (val result: CoilImageResult = imageLoader.execute(request)) {
            is SuccessResult -> ImageResult.Ready(result.image.toBitmap())
            is ErrorResult -> ImageResult.Failed(errorOf(result.throwable))
        }
    }

    private fun failure(key: String): ImageError? {
        val entry = failures.get(key)
        val live = entry != null && entry.expiresAtMillis > nowMillis()
        if (entry != null && !live) failures.remove(key)
        return if (live) entry.error else null
    }

    private fun remember(key: String, result: ImageResult): ImageResult {
        when (val error = (result as? ImageResult.Failed)?.error) {
            null, ImageError.EXTERNAL_DISABLED -> Unit
            ImageError.NETWORK, ImageError.TIMEOUT ->
                failures.put(key, Failure(error, nowMillis() + TRANSIENT_ERROR_TTL_MS))
            else -> failures.put(key, Failure(error, Long.MAX_VALUE))
        }
        return result
    }

    /** A `data:` source, decoded inside Coil's fetch so a memory-cache hit skips the base64 decode. */
    private class InlineImage(val key: String, val source: String)

    private class InlineImageFetcher(private val data: InlineImage, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val bytes = when (val parsed = ImageSource.parse(data.source)) {
                is ImageSource.Data -> parsed.bytes
                ImageSource.TooLarge -> throw ImageFetchException(ImageError.TOO_LARGE)
                else -> throw ImageFetchException(ImageError.UNSUPPORTED)
            }
            return SourceFetchResult(
                source = CoilImageSource(Buffer().write(bytes), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.MEMORY,
            )
        }
    }

    companion object {
        /** How long a NETWORK/TIMEOUT failure is remembered before the source is tried again. */
        const val TRANSIENT_ERROR_TTL_MS = 30_000L

        /** Longest source string that is looked at; owned by [ImageSource.MAX_SOURCE_LENGTH]. */
        const val MAX_SOURCE_LENGTH = ImageSource.MAX_SOURCE_LENGTH

        /** Fetches and decodes at once; with [ImageSource.MAX_SOURCE_LENGTH] a memory bound. */
        const val MAX_CONCURRENT_LOADS = 3

        private const val MAX_REMEMBERED_FAILURES = 256
        private const val HEAP_FRACTION_FOR_CACHE = 8
        private const val DATA_PREFIX = "data:"

        /** How much of a source is turned into bytes at once; see [cacheKey]. */
        private const val HASH_CHUNK_CHARS = 8 * 1024

        /**
         * The Coil image loader behind [ChatImageLoader]: remote images only through [http], no disk
         * cache, no components found through the service loader (which would bring an unguarded
         * network fetcher), software bitmaps from `BitmapFactory`.
         */
        fun imageLoader(
            context: Context,
            http: Call.Factory,
            maxCacheBytes: Long = Runtime.getRuntime().maxMemory() / HEAP_FRACTION_FOR_CACHE,
            dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOADS),
        ): ImageLoader = ImageLoader.Builder(context)
            .serviceLoaderEnabled(false)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                add(Fetcher.Factory<InlineImage> { data, options, _ -> InlineImageFetcher(data, options) })
                add(Keyer<InlineImage> { data, _ -> data.key })
            }
            .diskCache(null)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(maxCacheBytes).build() }
            .fetcherCoroutineContext(dispatcher)
            .decoderCoroutineContext(dispatcher)
            .imageDecoderEnabled(false)
            .allowHardware(false)
            .build()

        /** Which [ImageError] a failed fetch or decode was; anything unrecognised failed to decode. */
        internal fun errorOf(error: Throwable): ImageError =
            generateSequence(error) { it.cause }.firstNotNullOfOrNull { cause ->
                when (cause) {
                    is ImageRefusedException -> cause.error
                    is ImageFetchException -> cause.error
                    is InterruptedIOException -> ImageError.TIMEOUT
                    is HttpException, is IOException -> ImageError.NETWORK
                    else -> null
                }
            } ?: ImageError.MALFORMED

        /**
         * Stable, filesystem-safe key for a source (also the share file name): the SHA-1 of the
         * source's UTF-8. The whole source is hashed, since for a `data:` source a partial key would
         * let an attacker make two images collide. Fed in chunks that never split a surrogate pair to
         * avoid copying the whole string. O(length): call off the main thread.
         */
        fun cacheKey(source: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            var start = 0
            while (start < source.length) {
                var end = (start + HASH_CHUNK_CHARS).coerceAtMost(source.length)
                if (end < source.length && end - 1 > start && source[end - 1].isHighSurrogate()) end--
                digest.update(source.substring(start, end).toByteArray(Charsets.UTF_8))
                start = end
            }
            return digest.digest().toHex()
        }
    }
}

/** Process-wide loader so the thumbnail cache survives fragment recreation. */
object ChatImageLoaders {
    @Volatile
    private var instance: ChatImageLoader? = null

    fun get(context: Context): ChatImageLoader {
        instance?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            instance ?: create(app).also { instance = it }
        }
    }

    private fun create(app: Context): ChatImageLoader {
        val allowed = { Settings.getInstance(app).shouldLoadExternalImages }
        val http = chatImageHttpClient(networkAllowed = allowed, userAgent = "Mumla/" + BuildConfig.VERSIONTAG)
        return ChatImageLoader(app, ChatImageLoader.imageLoader(app, http), http, allowed)
    }

    /** Test seam: installs [loader] as the process-wide instance, or `null` to restore the real one. */
    @VisibleForTesting
    fun setForTests(loader: ChatImageLoader?) {
        instance = loader
    }
}
