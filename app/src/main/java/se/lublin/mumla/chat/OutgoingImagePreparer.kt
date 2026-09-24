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
 * The decode target is the final, aspect-fitted size, so `ImageDecoder` allocates only the bitmap
 * that is kept.
 *
 * `ImageDecoder` applies the EXIF orientation itself and reports the already-oriented size, so this
 * class reads no EXIF (rotating again would double the turn). Known gap: a PNG `eXIf` orientation
 * is not applied; a partial manual fix could not tell which cases the decoder already handled.
 */
class OutgoingImagePreparer(
    context: Context,
    private val maxWidth: Int = MAX_WIDTH,
    private val maxHeight: Int = MAX_HEIGHT,
    /** The defaults are the production wiring and keep both halves off the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** From the application context: [prepare] may outlive the Activity that started it. */
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    init {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }

    /** Null when the URI cannot be read or does not hold an image. */
    suspend fun prepare(uri: Uri): Bitmap? {
        val bytes = withContext(ioDispatcher) { read(uri) } ?: return null
        return withContext(decodeDispatcher) { decode(bytes) }
    }

    /**
     * The whole encoded file in one read (unbounded in the file's size; still far smaller than a
     * full-size bitmap).
     */
    private fun read(uri: Uri): ByteArray? =
        try {
            // openInputStream returns null for a provider that opens nothing.
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            // A picked URI's grant expires, e.g. after a process restart.
            null
        }

    /** The CPU half of [prepare]: blocking, no I/O, safe to call from any background thread. */
    fun decode(bytes: ByteArray): Bitmap? =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val target = boundedSize(info.size.width, info.size.height, maxWidth, maxHeight)
                decoder.setTargetSize(target.width, target.height)
                // Otherwise a device returns a HARDWARE bitmap, which the JPEG encoder reads back
                // from the GPU per quality rung and `getPixels` refuses. Robolectric cannot test this.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            // DecodeException is an IOException: not an image, no bytes, or truncated. ImageDecoder
            // throws unchecked exceptions only for bad listener arguments, which are bounded above.
            // (A codec reporting a zero-sized header would pass through boundedSize and throw.)
            null
        }

    companion object {
        const val MAX_WIDTH = 600
        const val MAX_HEIGHT = 400

        /**
         * The largest size within [maxWidth] x [maxHeight] that keeps [width] : [height], never
         * larger than the image itself, never smaller than one pixel on either axis (the same
         * arithmetic as `BitmapUtils.resizeKeepingAspect`).
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
