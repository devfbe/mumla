package se.lublin.mumla.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import se.lublin.mumla.Settings
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** What a load of one chat image ended in. */
sealed class ImageResult {
    /** Shared and cached: owned by the loader. Draw it, never recycle or mutate it. */
    class Ready(val bitmap: Bitmap) : ImageResult()

    data class Failed(val error: ImageError) : ImageResult()

    /** Bounds were not positive (view not measured yet). Not cached: ask again after layout. */
    data object Skipped : ImageResult()
}

/**
 * Loads chat images off the main thread.
 *
 * Thumbnails are cached in an [LruCache] keyed by the SHA-1 of the source and the requested bounds,
 * with a budget counted in bytes (default 1/8 of the max heap). Terminal failures are cached for
 * good, [ImageError.NETWORK]/[ImageError.TIMEOUT] for [TRANSIENT_ERROR_TTL_MS], and
 * [ImageError.EXTERNAL_DISABLED] never (the setting can change while the log is on screen).
 *
 * Concurrent loads of the same key share one fetch and decode in the loader's own scope, so the row
 * that scrolls away first does not cancel one still on screen. The job is cancelled when its last
 * caller leaves, but a blocking fetch that has started runs to the end; the result is then dropped.
 *
 * At most [maxConcurrentLoads] loads (fetch + decode) hold a permit, which bounds transient memory
 * to roughly `(maxConcurrentLoads + 1) x ~10 MB + maxCacheBytes`; the `+ 1` is [fetchBytes], which
 * takes no permit. [OutOfMemoryError] is deliberately not caught.
 */
class ChatImageLoader(
    private val fetcher: ImageFetcher = HttpImageFetcher(),
    private val externalImagesAllowed: () -> Boolean,
    maxCacheBytes: Long = Runtime.getRuntime().maxMemory() / 8,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
) {
    private class Entry(val result: ImageResult, val expiresAtMillis: Long)

    /** One shared fetch+decode and the number of callers still interested in it. */
    private class Shared {
        lateinit var job: Deferred<ImageResult>
        var waiters = 0
    }

    private val cache = object : LruCache<String, Entry>(maxCacheBytes.coerceIn(1L, MAX_CACHE_BYTES).toInt()) {
        override fun sizeOf(key: String, value: Entry): Int = when (val result = value.result) {
            is ImageResult.Ready -> result.bitmap.byteCount.coerceAtLeast(1)
            // Charged so a flood of distinct broken URLs cannot fill the map for free.
            else -> FAILURE_COST_BYTES
        }
    }

    /** Not tied to any caller's lifecycle, so shared work outlives one row. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    // Checked inline: Semaphore(0) would throw before an init {} check runs.
    private val gate = Semaphore(
        maxConcurrentLoads.also { require(it > 0) { "maxConcurrentLoads must be positive, was $it" } },
    )

    /**
     * Guarded by `synchronized(inFlight)`. Nothing inside the monitor suspends or fetches: jobs are
     * [CoroutineStart.LAZY] and started by [Deferred.await] outside it, since an immediate dispatcher
     * would otherwise run the blocking fetch under the lock.
     *
     * Every `waiters++` in [shared] must be followed by the awaiting `try/finally` that decrements it;
     * a path that leaves in between leaks the entry for the life of the process.
     */
    private val inFlight = HashMap<String, Shared>()

    /** The most recently fetched source and its bytes, so the viewer's share action can reuse them. */
    private val lastBytes = AtomicReference<Pair<String, ByteArray>?>(null)

    /**
     * Thumbnail bounded by [maxWidth] x [maxHeight] px; cached per source and bounds. Non-positive
     * bounds (an unmeasured view) yield [ImageResult.Skipped]; [BoundedBitmapDecoder] would throw.
     */
    suspend fun loadThumbnail(source: String, maxWidth: Int, maxHeight: Int): ImageResult {
        if (maxWidth <= 0 || maxHeight <= 0) return ImageResult.Skipped
        if (source.length > MAX_SOURCE_LENGTH) return ImageResult.Failed(ImageError.TOO_LARGE)
        val sourceKey = withContext(decodeDispatcher) { cacheKey(source) }
        val key = thumbnailKey(sourceKey, maxWidth, maxHeight)
        cached(key)?.let { return it }
        return shared(key) { loadAndCache(key, source, sourceKey, maxWidth, maxHeight) }
    }

    /**
     * Decode bounded by the given size (e.g. the screen); never cached as a bitmap. Uses
     * [BoundedBitmapDecoder.decodeAtMost] to avoid a second screen-sized bitmap for the exact fit.
     */
    suspend fun loadFull(source: String, maxWidth: Int, maxHeight: Int): ImageResult {
        if (maxWidth <= 0 || maxHeight <= 0) return ImageResult.Skipped
        if (source.length > MAX_SOURCE_LENGTH) return ImageResult.Failed(ImageError.TOO_LARGE)
        return load(source, withContext(decodeDispatcher) { cacheKey(source) }, maxWidth, maxHeight, exactFit = false)
    }

    /**
     * Raw bytes of [source], on [ioDispatcher]; throws [ImageFetchException]. The last result is
     * remembered so viewing and then sharing an image downloads it once.
     *
     * The array is the loader's (not copied): read it, never write to it. Takes no permit (the gate
     * is not reentrant and [load] already holds one), so direct callers are not counted against
     * [maxConcurrentLoads].
     */
    suspend fun fetchBytes(source: String): ByteArray {
        // Early refusal; ImageSource.parse enforces the same cap authoritatively.
        if (source.length > MAX_SOURCE_LENGTH) throw ImageFetchException(ImageError.TOO_LARGE)
        // cacheKey is O(length) and a data: source is the whole image, so hash off the caller's thread.
        return withContext(ioDispatcher) { fetchBytes(source, cacheKey(source)) }
    }

    /** Blocking; [sourceKey] is [cacheKey] of [source], already computed by the caller. */
    private fun fetchBytes(source: String, sourceKey: String): ByteArray {
        lastBytes.get()?.takeIf { it.first == sourceKey }?.let { return it.second }
        val bytes = when (val parsed = ImageSource.parse(source)) {
            is ImageSource.Data -> parsed.bytes
            is ImageSource.Remote -> {
                if (!externalImagesAllowed()) throw ImageFetchException(ImageError.EXTERNAL_DISABLED)
                fetcher.fetch(parsed.url)
            }
            ImageSource.TooLarge -> throw ImageFetchException(ImageError.TOO_LARGE)
            ImageSource.Unsupported -> throw ImageFetchException(ImageError.UNSUPPORTED)
        }
        lastBytes.set(sourceKey to bytes)
        return bytes
    }

    /** Bytes held by cached bitmaps, measured on the bitmaps rather than the cache's accounting. */
    @VisibleForTesting
    fun cachedBitmapBytes(): Long =
        cache.snapshot().values.sumOf { (it.result as? ImageResult.Ready)?.bitmap?.byteCount?.toLong() ?: 0L }

    /**
     * Runs [produce] once per [key] and hands every caller the same result. A cancelled caller drops
     * out; the work stops only when the last one has.
     */
    private suspend fun shared(key: String, produce: suspend () -> ImageResult): ImageResult {
        val entry = synchronized(inFlight) {
            // `isCompleted`, not `isActive`: a not-yet-started LAZY job is not active either.
            val running = inFlight[key]?.takeIf { !it.job.isCompleted }
            val shared = running ?: Shared().also {
                inFlight[key] = it
                // LAZY so nothing runs while this monitor is held; see [inFlight].
                it.job = scope.async(start = CoroutineStart.LAZY) { produce() }
            }
            shared.waiters++
            shared
        }
        try {
            // await() starts the LAZY job, outside the monitor.
            return entry.job.await()
        } finally {
            synchronized(inFlight) {
                if (--entry.waiters == 0) {
                    entry.job.cancel()
                    if (inFlight[key] === entry) inFlight.remove(key)
                }
            }
        }
    }

    private suspend fun loadAndCache(
        key: String,
        source: String,
        sourceKey: String,
        maxWidth: Int,
        maxHeight: Int,
    ): ImageResult {
        val result = load(source, sourceKey, maxWidth, maxHeight)
        ttlMillisFor(result)?.let { ttl ->
            val expiry = if (ttl == Long.MAX_VALUE) Long.MAX_VALUE else nowMillis() + ttl
            cache.put(key, Entry(result, expiry))
        }
        return result
    }

    private fun cached(key: String): ImageResult? {
        val entry = cache.get(key) ?: return null
        if (entry.expiresAtMillis <= nowMillis()) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    /** How long [result] may be served from the cache; `null` means do not cache it at all. */
    private fun ttlMillisFor(result: ImageResult): Long? = when {
        // Unreachable today (loadThumbnail returns early); never cache "not measured yet".
        result is ImageResult.Skipped -> null
        result is ImageResult.Failed && result.error == ImageError.EXTERNAL_DISABLED -> null
        result is ImageResult.Failed &&
            (result.error == ImageError.NETWORK || result.error == ImageError.TIMEOUT) -> TRANSIENT_ERROR_TTL_MS
        else -> Long.MAX_VALUE
    }

    private suspend fun load(
        source: String,
        sourceKey: String,
        maxWidth: Int,
        maxHeight: Int,
        exactFit: Boolean = true,
    ): ImageResult = gate.withPermit {
        val bytes = try {
            withContext(ioDispatcher) { fetchBytes(source, sourceKey) }
        } catch (e: ImageFetchException) {
            return@withPermit ImageResult.Failed(e.error)
        }
        val bitmap = withContext(decodeDispatcher) {
            if (exactFit) {
                BoundedBitmapDecoder.decode(bytes, maxWidth, maxHeight)
            } else {
                BoundedBitmapDecoder.decodeAtMost(bytes, maxWidth, maxHeight)
            }
        }
        if (bitmap == null) ImageResult.Failed(ImageError.MALFORMED) else ImageResult.Ready(bitmap)
    }

    private fun thumbnailKey(sourceKey: String, maxWidth: Int, maxHeight: Int): String =
        sourceKey + ":" + maxWidth + "x" + maxHeight

    companion object {
        /** How long a NETWORK/TIMEOUT failure stays cached before the source is tried again. */
        const val TRANSIENT_ERROR_TTL_MS = 30_000L

        /** Longest source string that is looked at; owned by [ImageSource.MAX_SOURCE_LENGTH]. */
        const val MAX_SOURCE_LENGTH = ImageSource.MAX_SOURCE_LENGTH

        /** Loads in flight at once; a memory bound, see the class KDoc. */
        const val DEFAULT_MAX_CONCURRENT_LOADS = 3

        /** What a cached failure is charged against the budget: the entry, its key and the error. */
        private const val FAILURE_COST_BYTES = 256

        private val MAX_CACHE_BYTES = Int.MAX_VALUE.toLong()

        private const val HEX = "0123456789abcdef"

        /** How much of a source is turned into bytes at once; see [cacheKey]. */
        private const val HASH_CHUNK_CHARS = 8 * 1024

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
            val bytes = digest.digest()
            val hex = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                hex[i * 2] = HEX[b ushr 4]
                hex[i * 2 + 1] = HEX[b and 0x0F]
            }
            return String(hex)
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
            instance ?: ChatImageLoader(
                externalImagesAllowed = { Settings.getInstance(app).shouldLoadExternalImages },
            ).also { instance = it }
        }
    }

    /** Test seam: installs [loader] as the process-wide instance, or `null` to restore the real one. */
    @VisibleForTesting
    fun setForTests(loader: ChatImageLoader?) {
        instance = loader
    }
}
