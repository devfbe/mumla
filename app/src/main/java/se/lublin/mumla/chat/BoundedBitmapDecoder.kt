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
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive. This is load
     *   bearing, not tidiness: a *negative* bound makes the doubling loop below never terminate.
     *   Measured on the JVM for (1000, 500, 240, -1): `fit` is -0.002, so every halving stays above
     *   it; `sample` doubles to 2^30, then overflows to `Int.MIN_VALUE`, then to 0, and from there
     *   `1f / 0` is `+Infinity`, which is `>= fit` forever. A zero bound does terminate, but with
     *   2^30 as the sample size. So what this rejects is a hung decoding thread, not merely an
     *   absurd return value.
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
     * Smallest power-of-two sample size whose result is at or **below** the aspect-fitted target
     * size — [sampleSizeFor]'s rounding turned the other way.
     *
     * One halving more than [sampleSizeFor] takes, and that one halving is the difference between
     * holding one bitmap and holding two. [sampleSizeFor] stops while the sampled image is still
     * larger than the box, so an exact fit needs a second, scaled bitmap and both are alive at
     * once; this one lands at or under the box, so the decoded bitmap is the only one there ever
     * is. The cost is up to one halving of detail. [decodeAtMost] is where that trade is taken and
     * its KDoc says why the fullscreen viewer can afford it.
     *
     * Same contract as [sampleSizeFor] otherwise: a non-positive [width] or [height] means the
     * header could not be read, and the bounds must be positive.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive.
     */
    fun sampleSizeAtMost(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        requirePositiveBounds(maxWidth, maxHeight)
        if (width <= 0 || height <= 0) return 1
        val fit = minOf(maxWidth / width.toFloat(), maxHeight / height.toFloat()).coerceAtMost(1f)
        var sample = 1
        while (1f / sample > fit) sample *= 2
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
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? =
        decode(bytes, maxWidth, maxHeight, exactFit = true)

    /**
     * Decodes [bytes] to a bitmap that is at or **below** [maxWidth] x [maxHeight] — never above,
     * never rescaled — so the decode allocates exactly one bitmap and the peak is what is kept.
     *
     * [decode] cannot do that. An exact fit needs a scaled copy of the sampled bitmap, and the two
     * are alive together: sampling stops while the intermediate is still in [1x, 2x) of the target
     * per axis, i.e. up to 4x the pixels, for a peak of just over **5x** what is kept. There is no
     * decoder flag that removes the copy. `inScaled` is this same two-step done inside
     * `BitmapFactory::doDecode`, and when it scales it redirects `inBitmap` to the heap allocator,
     * so reuse is no escape either; `ImageDecoder.setTargetSize` scales in the codec only where the
     * codec can (JPEG, WebP) and **not for PNG**, which is what the worst case is built from.
     *
     * So the fit is given up instead. Measured at twice a 1080x2340 screen, which is what the
     * fullscreen viewer asks for: a 4319 x 9359 image — just under twice the bound, the worst case
     * — samples at 1 under [sampleSizeFor] and at 2 here, which is 161.7 MB + 40.4 MB held at once
     * against 40.4 MB. The 128 MiB `heapgrowthlimit` this spec measures itself against sits between
     * the two.
     *
     * It costs nothing on screen: `ZoomImageView` draws with `ScaleType.MATRIX` and derives its
     * zoom ceiling from the bitmap's own intrinsic size, so a smaller bitmap moves the ceiling
     * rather than clipping the picture. It is not free for a **thumbnail**, which is drawn into a
     * fixed box and would simply be softer, which is why [decode] keeps the exact fit and only the
     * viewer's path comes here.
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
        // Optimisation, not a guard: a header that yielded no size cannot produce a bitmap either,
        // so this only saves the second decode. Behaviour is unchanged without it, which is why no
        // test can kill it.
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
        // The at-most sample already put the decode inside the box, so there is nothing left to
        // scale and nothing to hold a second bitmap for.
        return if (exactFit) BitmapUtils.resizeKeepingAspect(decoded, maxWidth, maxHeight) else decoded
    }

    private fun requirePositiveBounds(maxWidth: Int, maxHeight: Int) {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }
}
