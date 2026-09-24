package se.lublin.mumla.chat

import android.graphics.Matrix

/**
 * Zoom/pan state of an image that is fit-centered in a view.
 *
 * [scale] is relative to the `FIT_CENTER` scale (1 = fit, also for images smaller than the view), so
 * it keeps its meaning across a rotation; [tx]/[ty] are offsets in view pixels from the centered
 * position and are only re-clamped, not rescaled, after a size change.
 *
 * Every instance is finite by construction; NaN or a runaway factor fails loudly. The per-image
 * zoom ceiling is not a property of the state: see [maxScale], applied by [scaledBy] and [clamped].
 */
data class ZoomState(
    val scale: Float = MIN_SCALE,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    init {
        // NaN fails this too: no comparison with NaN is true.
        require(scale in MIN_SCALE..ABSOLUTE_MAX_SCALE) {
            "scale $scale outside [$MIN_SCALE, $ABSOLUTE_MAX_SCALE]"
        }
        require(tx.isFinite() && ty.isFinite()) { "offsets must be finite, were ($tx, $ty)" }
    }

    /**
     * Scales by [factor] keeping the image point under ([focusX], [focusY]) fixed, saturating at
     * [MIN_SCALE] and at this image's [maxScale]. Offsets are left for [clamped].
     */
    fun scaledBy(
        factor: Float,
        focusX: Float,
        focusY: Float,
        viewWidth: Float,
        viewHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): ZoomState {
        val ceiling = maxScale(viewWidth, viewHeight, imageWidth, imageHeight)
        val newScale = (scale * factor).coerceIn(MIN_SCALE, ceiling)
        val k = newScale / scale
        return ZoomState(
            scale = newScale,
            tx = (focusX - viewWidth / 2f) * (1 - k) + tx * k,
            ty = (focusY - viewHeight / 2f) * (1 - k) + ty * k,
        )
    }

    fun pannedBy(dx: Float, dy: Float): ZoomState = copy(tx = tx + dx, ty = ty + dy)

    /**
     * Keeps the image covering the view on axes where it is larger, centered where it is smaller,
     * and the zoom inside this image's [maxScale] (also for restored states, not only gestures).
     */
    fun clamped(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): ZoomState {
        // The floor is enforced by the constructor.
        val capped = scale.coerceAtMost(maxScale(viewWidth, viewHeight, imageWidth, imageHeight))
        val s = fitScale(viewWidth, viewHeight, imageWidth, imageHeight) * capped
        return copy(
            scale = capped,
            tx = clampAxis(tx, imageWidth * s, viewWidth),
            ty = clampAxis(ty, imageHeight * s, viewHeight),
        )
    }

    fun toMatrix(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Matrix {
        val s = fitScale(viewWidth, viewHeight, imageWidth, imageHeight) * scale
        return Matrix().apply {
            setScale(s, s)
            postTranslate((viewWidth - imageWidth * s) / 2f + tx, (viewHeight - imageHeight * s) / 2f + ty)
        }
    }

    private fun clampAxis(offset: Float, displayed: Float, view: Float): Float {
        val slack = (displayed - view) / 2f
        return if (slack <= 0f) 0f else offset.coerceIn(-slack, slack)
    }

    companion object {
        const val MIN_SCALE = 1f

        /** No image earns a lower ceiling than this, so even a small one stays inspectable. */
        const val MIN_CEILING = 2f

        /** And none earns a higher one, whatever its pixel budget says. */
        const val MAX_CEILING = 5f

        /** A sanity bound against broken arithmetic, not a policy; see [maxScale]. */
        const val ABSOLUTE_MAX_SCALE = 100f

        /**
         * The zoom ceiling for this image in this view: `1 / fitScale` (one source pixel per screen
         * pixel; beyond that is only interpolation), held between [MIN_CEILING] so small images stay
         * inspectable and [MAX_CEILING].
         */
        fun maxScale(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Float =
            (1f / fitScale(viewWidth, viewHeight, imageWidth, imageHeight)).coerceIn(MIN_CEILING, MAX_CEILING)

        /**
         * The `FIT_CENTER` scale of [imageWidth] x [imageHeight] inside [viewWidth] x [viewHeight].
         * All four must be positive (an unmeasured view or a drawable without intrinsic size throws
         * rather than producing a degenerate matrix).
         */
        fun fitScale(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Float {
            require(viewWidth > 0f && viewHeight > 0f) { "view not measured: ${viewWidth}x$viewHeight" }
            require(imageWidth > 0f && imageHeight > 0f) { "image has no size: ${imageWidth}x$imageHeight" }
            return minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        }
    }
}
