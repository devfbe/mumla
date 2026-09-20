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
    /**
     * The bitmap is shared with every other caller that asked for the same source and bounds and it
     * stays in the cache afterwards, so it belongs to the loader: draw it, never recycle or mutate it.
     */
    class Ready(val bitmap: Bitmap) : ImageResult()

    data class Failed(val error: ImageError) : ImageResult()

    /**
     * Nothing was attempted because the requested bounds were not positive — the usual cause is a
     * view that has not been measured yet. Not an error and not cached: ask again after layout.
     */
    data object Skipped : ImageResult()
}

/**
 * Loads chat images off the main thread.
 *
 * Thumbnails are cached in an [LruCache] keyed by the SHA-1 of the source **and the requested
 * bounds**, so callers asking for different sizes do not steal each other's bitmaps. The budget
 * defaults to 1/8 of the max heap and is counted in **bytes**, not in kibibytes: rounding each
 * entry down to a whole KiB lets small thumbnails (a 22x23 one costs 2024 bytes) count as half of
 * what they are, and a cache that holds twice its budget is the disease this class exists to cure.
 *
 * Failures are cached too, but only terminal ones ([ImageError.UNSUPPORTED], [ImageError.MALFORMED],
 * [ImageError.TOO_LARGE]) for good: [ImageError.NETWORK] and [ImageError.TIMEOUT] expire after
 * [TRANSIENT_ERROR_TTL_MS] so an image that arrived while the phone was offline becomes loadable
 * again, and [ImageError.EXTERNAL_DISABLED] is never cached because the setting behind it can change
 * while the log is on screen. Without any error caching a failed row re-fetches on every single
 * bind, which is what turns one unreachable image into a scroll-speed request loop.
 *
 * Concurrent loads of the same key share one fetch and one decode, so a fling that binds the same
 * source in several rows does the work once. The shared work runs in the loader's own scope rather
 * than in the first caller's, because otherwise the row that scrolls away first cancels the row that
 * is still on screen. When its last caller is gone the job is cancelled — but cancellation is
 * cooperative and [ImageFetcher.fetch] blocks, so it never observes it: a download that has already
 * started runs to the end. What the cancellation buys is that no *further* work is queued and that
 * the result is dropped rather than cached; what bounds a fast fling is the permit below.
 *
 * At most [maxConcurrentLoads] loads hold a permit at a time, and a permit covers the decode as well
 * as the fetch, so decodes are serialised to the same number. [fetchBytes] called on its own — the
 * share path — takes no permit at all and is not counted here. Peak memory per fetch is bounded by
 * [HttpImageFetcher]'s byte cap, so the number in flight is part of the memory bound, not a
 * throughput knob; the arithmetic, including the terms this sentence does not cover, is in the
 * stream's ledger rather than here, because it depends on the heap the device gives the app.
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
            // A remembered failure is a small object and a key, but not free: a server that sends a
            // thousand distinct broken URLs would otherwise fill the map without ever paying for it.
            else -> FAILURE_COST_BYTES
        }
    }

    /** Not tied to any caller's lifecycle; see the class KDoc on why the work outlives one row. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    // The bound is checked here, not in an `init {}` block: property initialisers run first, so
    // Semaphore(0) would throw its own message before any later check could be reached.
    private val gate = Semaphore(
        maxConcurrentLoads.also { require(it > 0) { "maxConcurrentLoads must be positive, was $it" } },
    )

    /**
     * Guarded by `synchronized(inFlight)`. Nothing inside those sections suspends *or fetches*: the
     * shared job is created lazily and started outside the monitor, because on an immediate
     * dispatcher a coroutine's body runs inline and the whole blocking fetch would otherwise happen
     * with this lock held — a process-wide lock around the network, one dispatcher change away.
     */
    private val inFlight = HashMap<String, Shared>()

    /** The most recently fetched source and its bytes, so the viewer's share action can reuse them. */
    private val lastBytes = AtomicReference<Pair<String, ByteArray>?>(null)

    /**
     * Thumbnail bounded by [maxWidth] x [maxHeight] px; cached per source *and* bounds.
     *
     * Non-positive bounds yield [ImageResult.Skipped] instead of reaching [BoundedBitmapDecoder],
     * which throws on them — deliberately, since a negative bound hangs its sampling loop. An
     * unmeasured view legitimately reports 0 px, and that must not be a crash.
     */
    suspend fun loadThumbnail(source: String, maxWidth: Int, maxHeight: Int): ImageResult {
        if (maxWidth <= 0 || maxHeight <= 0) return ImageResult.Skipped
        if (source.length > MAX_SOURCE_LENGTH) return ImageResult.Failed(ImageError.TOO_LARGE)
        val sourceKey = withContext(decodeDispatcher) { cacheKey(source) }
        val key = thumbnailKey(sourceKey, maxWidth, maxHeight)
        cached(key)?.let { return it }
        return shared(key) { loadAndCache(key, source, sourceKey, maxWidth, maxHeight) }
    }

    /** Decode bounded by the given size (e.g. the screen); never cached as a bitmap. */
    suspend fun loadFull(source: String, maxWidth: Int, maxHeight: Int): ImageResult {
        if (maxWidth <= 0 || maxHeight <= 0) return ImageResult.Skipped
        if (source.length > MAX_SOURCE_LENGTH) return ImageResult.Failed(ImageError.TOO_LARGE)
        return load(source, withContext(decodeDispatcher) { cacheKey(source) }, maxWidth, maxHeight)
    }

    /**
     * Raw bytes of [source]. Throws [ImageFetchException]. Runs on [ioDispatcher].
     * Remembers the last result (one entry) so displaying an image and then sharing it does not
     * download it twice.
     *
     * **The array is the loader's**, exactly as [ImageResult.Ready.bitmap] is: it is the remembered
     * entry itself, handed out without a copy because copying five mebibytes per call is the disease
     * this class treats. Read it, never write to it.
     *
     * Takes no permit from the concurrency gate: [load] already holds one while it calls this, and
     * the gate is not reentrant. A caller that uses this directly is therefore not counted against
     * [maxConcurrentLoads].
     */
    suspend fun fetchBytes(source: String): ByteArray {
        // ImageSource.parse enforces the same cap and is the authoritative one — it is where the
        // percent-decode and the base64 decode happen. Refusing here as well keeps the work off
        // this call before it even dispatches, and is pinned from both sides.
        if (source.length > MAX_SOURCE_LENGTH) throw ImageFetchException(ImageError.TOO_LARGE)
        // cacheKey hashes the whole source, and for a `data:` source the source *is* the image.
        // That belongs on the IO dispatcher with the fetch, never on the thread that bound the row.
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

    /**
     * The bytes the cache really holds right now, measured on the bitmaps themselves rather than
     * read back out of the cache's own accounting — a test that asked [LruCache] how full it thinks
     * it is would pass under any sizing rule, including the one this measurement exists to reject.
     */
    @VisibleForTesting
    fun cachedBitmapBytes(): Long =
        cache.snapshot().values.sumOf { (it.result as? ImageResult.Ready)?.bitmap?.byteCount?.toLong() ?: 0L }

    /**
     * Runs [produce] once per [key] however many callers ask for it, and hands every caller the same
     * result. A caller that is cancelled while waiting drops out; the work stops only when the last
     * one has.
     */
    private suspend fun shared(key: String, produce: suspend () -> ImageResult): ImageResult {
        val entry = synchronized(inFlight) {
            // `isCompleted`, not `isActive`: a lazily created job has not started yet, and a caller
            // that arrived in that window has to join it rather than start a second fetch. Both a
            // finished and a cancelled job are completed, and neither may be handed to a new caller.
            // Untested, deliberately: a cancelled entry is removed inside the same critical section
            // that cancels it, and re-awaiting a finished one returns the same result, so no
            // observable failure could be built for dropping this. It is a guard, not a covered path.
            val running = inFlight[key]?.takeIf { !it.job.isCompleted }
            val shared = running ?: Shared().also {
                inFlight[key] = it
                it.job = scope.async(start = CoroutineStart.LAZY) { produce() }
            }
            shared.waiters++
            shared
        }
        entry.job.start() // outside the monitor; a no-op for everyone but the first caller
        try {
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
        // Unreachable by construction — loadThumbnail returns Skipped before anything can be cached
        // — and kept anyway, because the alternative if it ever became reachable is caching "this
        // view had not been measured yet" for the life of the process.
        result is ImageResult.Skipped -> null
        result is ImageResult.Failed && result.error == ImageError.EXTERNAL_DISABLED -> null
        result is ImageResult.Failed &&
            (result.error == ImageError.NETWORK || result.error == ImageError.TIMEOUT) -> TRANSIENT_ERROR_TTL_MS
        else -> Long.MAX_VALUE
    }

    private suspend fun load(source: String, sourceKey: String, maxWidth: Int, maxHeight: Int): ImageResult = gate.withPermit {
        val bytes = try {
            withContext(ioDispatcher) { fetchBytes(source, sourceKey) }
        } catch (e: ImageFetchException) {
            return@withPermit ImageResult.Failed(e.error)
        }
        val bitmap = withContext(decodeDispatcher) { BoundedBitmapDecoder.decode(bytes, maxWidth, maxHeight) }
        if (bitmap == null) ImageResult.Failed(ImageError.MALFORMED) else ImageResult.Ready(bitmap)
    }

    private fun thumbnailKey(sourceKey: String, maxWidth: Int, maxHeight: Int): String =
        sourceKey + ":" + maxWidth + "x" + maxHeight

    companion object {
        /** How long a NETWORK/TIMEOUT failure stays cached before the source is tried again. */
        const val TRANSIENT_ERROR_TTL_MS = 30_000L

        /**
         * Longest source string that is even looked at; see [ImageSource.MAX_SOURCE_LENGTH], which
         * owns the bound because it owns the allocations. Repeated here only so that this class's
         * own early refusal and the parser's cannot drift apart.
         */
        const val MAX_SOURCE_LENGTH = ImageSource.MAX_SOURCE_LENGTH

        /** In flight at once. Each one costs up to [HttpImageFetcher]'s cap, so this bounds memory. */
        const val DEFAULT_MAX_CONCURRENT_LOADS = 3

        /** What a cached failure is charged against the budget: the entry, its key and the error. */
        private const val FAILURE_COST_BYTES = 256

        private val MAX_CACHE_BYTES = Int.MAX_VALUE.toLong()

        private const val HEX = "0123456789abcdef"

        /** How much of a source is turned into bytes at once; see [cacheKey]. */
        private const val HASH_CHUNK_CHARS = 8 * 1024

        /**
         * Stable, filesystem-safe key for a source; also used as the share file name (Task 8).
         *
         * The **whole** source is hashed, and that is not negotiable: for a `data:` source the
         * source is the image, so any key derived from a part of it collides — by pigeonhole, on
         * inputs an attacker picks — for two sources that differ only in the part not read, and a
         * collision here shows one participant's image in place of another's and names the shared
         * file after the wrong one. There is no cheaper key that is equally collision-safe.
         *
         * What *is* negotiable is the copy. `source.toByteArray()` would allocate a second
         * megabytes-long array per call, so the string is fed to the digest in chunks instead,
         * never splitting a surrogate pair — the halves encode differently apart than together,
         * which would silently change the key of every source long enough to contain one. The
         * result is byte for byte the SHA-1 of the source's UTF-8, pinned by
         * `theKeyIsTheSha1OfTheWholeSourceHoweverLongItIs`.
         *
         * This is still O(length) work: callers run it off the thread that binds a row.
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
                externalImagesAllowed = { Settings.getInstance(app).shouldLoadExternalImages() },
            ).also { instance = it }
        }
    }

    /**
     * Test seam: installs [loader] as the process-wide instance, or restores the real one with
     * `null`. Used by `ImageViewerDialogFragmentTest` (Task 8); production code must not call this.
     */
    @VisibleForTesting
    fun setForTests(loader: ChatImageLoader?) {
        instance = loader
    }
}
