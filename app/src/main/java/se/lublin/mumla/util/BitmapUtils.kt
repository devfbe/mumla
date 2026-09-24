package se.lublin.mumla.util

import android.graphics.Bitmap

object BitmapUtils {
    /**
     * Scales [image] down to fit within [maxWidth] x [maxHeight], keeping the aspect ratio. Never
     * upscales (an image within bounds is returned as the same instance); the scaled side is at
     * least one pixel.
     *
     * @throws IllegalArgumentException if [maxWidth] or [maxHeight] is not positive.
     */
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
