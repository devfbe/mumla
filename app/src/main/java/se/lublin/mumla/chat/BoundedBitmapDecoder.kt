package se.lublin.mumla.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import se.lublin.mumla.util.BitmapUtils

/** Memory-bounded decoding: a bounds pass, a power-of-two `inSampleSize`, then an exact fit. */
object BoundedBitmapDecoder {

    /**
     * Largest power-of-two sample size whose result is still at or above the aspect-fitted target
     * size. Sampling toward the fitted target (not toward the raw bounds on both axes) is what makes
     * an extreme aspect ratio, e.g. 12000 x 200 into a 240 x 240 box, sample down at all.
     *
     * A non-positive [width] or [height] means the header could not be read; there is nothing to
     * sample, so the answer is 1.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive.
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
     * Decodes [bytes] to a bitmap no larger than [maxWidth] x [maxHeight], aspect ratio kept.
     *
     * The size is taken from the header first and the image is sampled down while it is decoded, so
     * the full-size bitmap is never allocated: a 4000 x 3000 photo costs 187 KB here instead of the
     * 48 MB it would cost decoded whole and shrunk afterwards.
     *
     * Returns `null` for anything that is not a decodable image — random bytes, no bytes, or a body
     * cut short mid-stream. Undecodable data is an ordinary outcome for chat content, not an
     * exceptional one, so it is never reported by throwing.
     *
     * Catching [RuntimeException] around each decode is defence in depth. Android's
     * `decodeByteArray` reports broken data by returning `null`, not by throwing; the throwing is a
     * JVM/ImageIO behaviour, which is what the unit tests decode with. (Truncated bodies themselves
     * are real — HttpImageFetcher hands them over — it is only the *throwing* that is decoder
     * specific.) The catch keeps a decoder that does throw from turning a bad chat image into a
     * crash. It is deliberately narrowed to [RuntimeException]: an [Error], above all
     * [OutOfMemoryError], means the heap is gone, not that this was not an image, and must reach
     * the caller so it fails where it can be diagnosed. That policy is pinned by tests, not only
     * stated here — see `anErrorFrom…ReachesTheCaller` in BoundedBitmapDecoderTest.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive. Those are
     *   caller-supplied limits rather than untrusted data, so a bad one is a programming error and
     *   must not be disguised as "not an image".
     */
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        requirePositiveBounds(maxWidth, maxHeight)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
            .getOrElse { if (it is RuntimeException) return null else throw it }
        // Optimisation, not a guard: a header that yielded no size cannot produce a bitmap either,
        // so this only saves the second decode. Behaviour is unchanged without it, which is why no
        // test can kill it.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrElse { if (it is RuntimeException) return null else throw it }
            ?: return null
        return BitmapUtils.resizeKeepingAspect(decoded, maxWidth, maxHeight)
    }

    private fun requirePositiveBounds(maxWidth: Int, maxHeight: Int) {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }
}
