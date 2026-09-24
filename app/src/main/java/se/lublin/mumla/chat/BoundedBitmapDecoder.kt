package se.lublin.mumla.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import se.lublin.mumla.util.BitmapUtils

/** Memory-bounded decoding: a bounds pass, a power-of-two `inSampleSize`, then an exact fit. */
object BoundedBitmapDecoder {

    /**
     * Largest power-of-two sample size whose result is still at or above the aspect-fitted target
     * size (fitting toward the target, not the raw bounds, lets extreme aspect ratios sample down).
     * A non-positive [width] or [height] (unreadable header) yields 1.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive; a negative
     *   bound would make the doubling loop never terminate.
     */
    fun sampleSizeFor(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        requirePositiveBounds(maxWidth, maxHeight)
        if (width <= 0 || height <= 0) return 1
        val fit = minOf(maxWidth / width.toFloat(), maxHeight / height.toFloat()).coerceAtMost(1f)
        var sample = 1
        while (1f / (sample * 2) >= fit) sample *= 2
        return sample
    }

    /**
     * Smallest power-of-two sample size whose result is at or below the aspect-fitted target size,
     * so no second scaled bitmap is needed (at the cost of up to one halving of detail). Otherwise
     * the same contract as [sampleSizeFor].
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive.
     */
    fun sampleSizeAtMost(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        requirePositiveBounds(maxWidth, maxHeight)
        if (width <= 0 || height <= 0) return 1
        // The clamp is inert here; kept so both samplers read alike.
        val fit = minOf(maxWidth / width.toFloat(), maxHeight / height.toFloat()).coerceAtMost(1f)
        var sample = 1
        while (1f / sample > fit) sample *= 2
        return sample
    }

    /**
     * Decodes [bytes] to a bitmap no larger than [maxWidth] x [maxHeight], aspect ratio kept. The
     * header is read first and the image sampled down during decode, so the full-size bitmap is
     * never allocated.
     *
     * Returns `null` for anything that is not a decodable image (including truncated bodies). A
     * [RuntimeException] from the decoder also yields `null`; an [Error] such as [OutOfMemoryError]
     * reaches the caller.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive (a programming
     *   error, not "not an image").
     */
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? =
        decode(bytes, maxWidth, maxHeight, exactFit = true)

    /**
     * Decodes [bytes] to a bitmap at or below [maxWidth] x [maxHeight], never rescaled, so only one
     * bitmap is allocated. [decode]'s exact fit keeps the sampled bitmap and a scaled copy alive at
     * once (up to ~5x the result), which for a screen-sized bound is tens of megabytes. Used by the
     * fullscreen viewer, whose zoom ceiling follows the bitmap's intrinsic size; thumbnails keep the
     * exact fit.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive.
     */
    fun decodeAtMost(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? =
        decode(bytes, maxWidth, maxHeight, exactFit = false)

    private fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int, exactFit: Boolean): Bitmap? {
        requirePositiveBounds(maxWidth, maxHeight)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
            .getOrElse { if (it is RuntimeException) return null else throw it }
        // Only saves the second decode: a header without a size cannot produce a bitmap either.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = if (exactFit) {
                sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
            } else {
                sampleSizeAtMost(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
            }
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrElse { if (it is RuntimeException) return null else throw it }
            ?: return null
        // The at-most sample already fits the box, so resizing it would be a no-op.
        return if (exactFit) BitmapUtils.resizeKeepingAspect(decoded, maxWidth, maxHeight) else decoded
    }

    private fun requirePositiveBounds(maxWidth: Int, maxHeight: Int) {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }
}
