package se.lublin.mumla.util

import android.graphics.Bitmap

object BitmapUtils {
    /**
     * Scales [image] down so it fits within [maxWidth] x [maxHeight] while keeping the aspect ratio.
     *
     * Never upscales: an image already within the bounds is returned as the very same instance, not
     * a copy. The scaled side is floored at one pixel, so an extreme aspect ratio cannot collapse to
     * a zero-sized bitmap.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive. Both are
     *   caller-supplied limits, never image data, so a non-positive one is a programming error; the
     *   previous version either ignored it silently or failed deep inside [Bitmap.createScaledBitmap].
     */
    @JvmStatic
    fun resizeKeepingAspect(image: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }

        val width = image.width
        val height = image.height
        if (width <= maxWidth && height <= maxHeight) {
            return image
        }

        val ratioBitmap = width.toFloat() / height.toFloat()
        val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()

        val finalWidth: Int
        val finalHeight: Int
        if (ratioMax > ratioBitmap) {
            finalHeight = maxHeight
            finalWidth = (maxHeight * ratioBitmap).toInt().coerceAtLeast(1)
        } else {
            finalWidth = maxWidth
            finalHeight = (maxWidth / ratioBitmap).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(image, finalWidth, finalHeight, true)
    }
}
