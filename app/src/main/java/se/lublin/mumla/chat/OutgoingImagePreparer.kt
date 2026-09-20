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
 * orientation running beside the decoder's, with no way to tell which of the two already acted.
 */
class OutgoingImagePreparer(
    context: Context,
    private val maxWidth: Int = MAX_WIDTH,
    private val maxHeight: Int = MAX_HEIGHT,
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
        // replacing `return null` with an empty array leaves all 34 tests green.
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
            // The null arm is the platform's documented @Nullable return, and it is the one branch
            // in this file no test reaches: Robolectric's resolver cannot produce it, because a
            // registered supplier answering null falls through to a non-null stream. The mutation
            // that would pin it (`?.` to `!!`) was run and left all 34 tests green.
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
                // Unpinned and measured: deleting this line leaves all 34 tests green, because
                // Robolectric has no GPU and hands back a software bitmap either way. Asserting
                // `config != HARDWARE` here would pass with or without the line, which is a cover
                // that does not exist rather than a test.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            // ImageDecoder.DecodeException is an IOException, so this is also "not an image",
            // "no bytes at all" and "cut short mid-stream".
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
