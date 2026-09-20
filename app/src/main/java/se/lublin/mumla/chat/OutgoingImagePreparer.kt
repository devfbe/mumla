package se.lublin.mumla.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Prepares a picked image for sending: reads the bytes once and decodes them straight to the
 * outgoing bounds. Nothing here touches the main thread.
 *
 * **One allocation.** The decode target is the final, aspect-fitted size, so `ImageDecoder` samples
 * the codec down to that size and writes the bitmap that is kept — there is no full-size bitmap and
 * no scaled copy of one. The path this replaces decoded a picked photo at full size
 * (`BitmapFactory.decodeStream`), copied it again for the EXIF rotation and only then fitted it:
 * up to ~96 MB in flight for a 12 MP photo.
 *
 * **Orientation belongs to the decoder, and only to the decoder.** `ImageDecoder` applies the EXIF
 * origin itself and reports the already-oriented size to [ImageDecoder.OnHeaderDecodedListener]
 * (`frameworks/base/libs/hwui/hwui/ImageDecoder.cpp`: the constructor swaps `mTargetSize` for an
 * origin that swaps axes, `width()`/`height()` — which is what `ImageInfo.getSize()` carries —
 * swap with it, and `decode()` pre-concatenates `SkEncodedOriginToMatrix` into the output matrix).
 * `BitmapFactory` does none of this, which is why the old path read `ExifInterface` and rotated by
 * hand. Doing both turns a quarter turn into a half turn, so this class reads no EXIF at all.
 * Measured against the real decoder in `OutgoingImagePreparerTest`, per orientation.
 * The one case the decoder does not cover is a PNG carrying an `eXIf` orientation; that is pinned
 * as a limitation rather than patched, because patching it means a second source of truth for
 * orientation running beside the decoder's, with no way to tell which of the two already acted —
 * and the "no way to tell" is only half true, which is precisely what makes a patch worse than the
 * gap. For the four axis-swapping orientations the decoder's action **is** detectable, by comparing
 * `info.size` against the container's own header dimensions; for 180°, for either mirroring and for
 * any square image it is **not**. A correction that lands on four of eight cases and double-applies
 * or drops the other four is worse than one documented gap in a container no camera emits.
 */
class OutgoingImagePreparer(
    context: Context,
    private val maxWidth: Int = MAX_WIDTH,
    private val maxHeight: Int = MAX_HEIGHT,
    /**
     * The two defaults are the production wiring: `ChannelChatFragment.onImagePicked` constructs
     * `OutgoingImagePreparer(requireContext())` and awaits it on `lifecycleScope`, i.e. from the
     * main thread. They are pinned by
     * `theDefaultDispatchersKeepBothHalvesOffTheMainAndTheCallersThread`, which constructs this
     * class exactly as the fragment does — every other test injects dispatchers, and while that was
     * the only coverage, `Dispatchers.IO` replaced by `Dispatchers.Main` left these two classes' tests green
     * while putting a content-provider open and a whole-file read back on the main thread.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Taken from the application context: [prepare] outlives the tap that started it, and a
     * resolver held from an Activity or Fragment context would hold that context with it.
     *
     * Unpinned, and the mutation that would pin it was run: replacing this with
     * `context.contentResolver` changes no test, because under Robolectric the application *is*
     * the context every test passes and the two expressions are the same object. It is not a
     * behavioural line — it decides what this object keeps alive, not what it returns.
     */
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    init {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }

    /**
     * Null when the URI cannot be read or does not hold an image; both are ordinary outcomes of
     * letting someone pick a file, not exceptional ones.
     */
    suspend fun prepare(uri: Uri): Bitmap? {
        // Optimisation, not a guard: bytes that could not be read would decode to null anyway
        // (`decode(ByteArray(0))` is pinned), so this only saves a dispatch and a decoder. Measured:
        // replacing `return null` with an empty array leaves all 434 tests of
        // `:app:testFossDebugUnitTest` green, these two classes' 37 among them.
        val bytes = withContext(ioDispatcher) { read(uri) } ?: return null
        return withContext(decodeDispatcher) { decode(bytes) }
    }

    /**
     * The whole file, in one read. The old path opened the URI twice — once for `ExifInterface`,
     * once for the decode — which is two content-provider round trips for one picked image.
     *
     * The bytes are held whole because [ImageDecoder] is given a [ByteBuffer]; that is the encoded
     * file, not a bitmap, and it is far smaller than the full-size bitmap the old path built from
     * it (a 4320x9360 PNG is 138 712 bytes encoded against 162 MB decoded). It is, however,
     * unbounded in the file's size, which is the one way this path is worse than a streaming decode.
     */
    private fun read(uri: Uri): ByteArray? =
        try {
            // The null arm is the platform's documented @Nullable return, and it is reachable and
            // pinned (`aProviderThatOpensNothingGivesNull`): a ContentProvider whose
            // openAssetFile/openFile answer null makes openAssetFileDescriptor answer null, and
            // openInputStream hands that straight back instead of throwing. Measured through a
            // real provider hosted by Robolectric.setupContentProvider. Without the `?.` that is a
            // NullPointerException caught by neither catch below, so it escapes prepare and crashes
            // the app — the same shape as the SecurityException arm underneath, one type further.
            //
            // Note what is NOT reproducible: a Robolectric input-stream supplier that answers null
            // throws FileNotFoundException rather than falling through, so the supplier fixture
            // every other test here uses cannot reach this arm. That is what made it look unpinnable.
            //
            // And note what must NOT be added: a `catch (RuntimeException)` backstop here would
            // swallow exactly that NullPointerException, and the pin above would pass with the `?.`
            // deleted. Two catches over one observable are one catch and a lie (spec 4.04).
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            // A picked URI is a grant, and a grant expires: the same URI after a process restart
            // answers with this rather than with an IOException.
            null
        }

    /** The CPU half of [prepare]: blocking, no I/O, safe to call from any background thread. */
    fun decode(bytes: ByteArray): Bitmap? =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val target = boundedSize(info.size.width, info.size.height, maxWidth, maxHeight)
                decoder.setTargetSize(target.width, target.height)
                // Without this the decoder returns a HARDWARE bitmap on a device
                // (`libs/hwui/jni/ImageDecoder.cpp`: `isHardware` is the default allocator together
                // with a non-mutable result), which the JPEG encoder then reads back from the GPU
                // once per quality rung, and which `getPixels` refuses outright.
                // Unpinned and measured: deleting this line leaves all 434 tests of
                // `:app:testFossDebugUnitTest` green, these two classes' 37 among them, because
                // Robolectric has no GPU and hands back a software bitmap either way. Asserting
                // `config != HARDWARE` here would pass with or without the line, which is a cover
                // that does not exist rather than a test.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            // ImageDecoder.DecodeException is an IOException, so this is also "not an image",
            // "no bytes at all" and "cut short mid-stream".
            //
            // Deliberately NOT a `catch (RuntimeException)` backstop as well, although these bytes
            // are arbitrary and user-chosen and although the fix round one task earlier in this
            // stream added exactly that to `HttpImageFetcher.fetch`. The difference is where the
            // exception would come from. There, a platform StringIndexOutOfBoundsException had
            // actually escaped, thrown out of *this project's own* arithmetic on a URI authority.
            // Here `ImageDecoder`'s throw surface over the *bytes* is IOException by documentation;
            // its IllegalArgumentException/IllegalStateException surface is over the *arguments the
            // listener sets*, which are this file's own and are bounded by the `require`s above and
            // by `boundedSize`'s one-pixel floor. Neither the review nor this round could construct
            // an input that makes it throw anything else. An unreachable catch is an unpinnable
            // branch that no later round can close, and it would read as covered (spec 4.04).
            //
            // The one residual route, named because it is the only one: `boundedSize`'s
            // pass-through arm returns a zero dimension unchanged, and `setTargetSize(0, h)` throws
            // IllegalArgumentException. It needs a codec that reports a zero-sized header, which no
            // fixture here can produce. If one is ever found, the fix is the floor in `boundedSize`
            // — not a catch, which would hide it.
            null
        }

    companion object {
        const val MAX_WIDTH = 600
        const val MAX_HEIGHT = 400

        /**
         * The largest size within [maxWidth] x [maxHeight] that keeps [width] : [height], never
         * larger than the image itself, never smaller than one pixel on either axis.
         *
         * This is `BitmapUtils.resizeKeepingAspect`'s arithmetic, moved from after the decode to
         * before it — pinned against it case by case in `OutgoingImagePreparerTest`. Asking the
         * decoder for this size is what makes the bitmap that is kept the only one allocated.
         */
        fun boundedSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Size {
            if (width <= maxWidth && height <= maxHeight) return Size(width, height)

            val ratioImage = width.toFloat() / height.toFloat()
            val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()
            return if (ratioMax > ratioImage) {
                Size((maxHeight * ratioImage).toInt().coerceAtLeast(1), maxHeight)
            } else {
                Size(maxWidth, (maxWidth / ratioImage).toInt().coerceAtLeast(1))
            }
        }
    }
}
