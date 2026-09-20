package se.lublin.mumla.chat

import android.graphics.Matrix

/**
 * Zoom/pan state of an image that is fit-centered in a view.
 *
 * [scale] is relative to the fit scale, so 1 means "fills the view the way `ScaleType.FIT_CENTER`
 * would" for any image and any view size -- including an image smaller than the view, which is
 * scaled *up*, exactly as `FIT_CENTER` does. [tx]/[ty] are offsets in view pixels from that
 * centered position.
 *
 * Two consequences of that choice, both deliberate:
 *  - [scale] survives a configuration change unchanged and still means the same thing, because it
 *    is a ratio, not a pixel count. [tx]/[ty] do not: half a screen is a different number of pixels
 *    after a rotation. [ZoomImageView] therefore restores all three and lets [clamped] pull the
 *    offsets back into the new bounds, which keeps the zoom exactly and the position approximately.
 *  - the state is the same size for a 100x100 and a 4000x3000 image, so nothing here scales with
 *    the image.
 *
 * Every instance is valid by construction: there is no out-of-range or non-finite zoom state to
 * check for anywhere else. A factor that would leave the range saturates in [scaledBy]; a factor
 * that is not a number at all is a programming error and fails loudly here rather than turning the
 * view's matrix into NaN for the rest of its life.
 */
data class ZoomState(
    val scale: Float = MIN_SCALE,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    init {
        // NaN fails this too: no comparison with NaN is true.
        require(scale in MIN_SCALE..MAX_SCALE) { "scale $scale outside [$MIN_SCALE, $MAX_SCALE]" }
        require(tx.isFinite() && ty.isFinite()) { "offsets must be finite, were ($tx, $ty)" }
    }

    /**
     * Scales by [factor] keeping the image point under ([focusX], [focusY]) fixed, saturating at
     * [MIN_SCALE] and [MAX_SCALE]. The focus point is not required to be inside the view or inside
     * the image; the same linear rule applies wherever it is. The offsets are *not* clamped here --
     * that needs the image size, and is [clamped]'s job.
     */
    fun scaledBy(factor: Float, focusX: Float, focusY: Float, viewWidth: Float, viewHeight: Float): ZoomState {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val k = newScale / scale
        return ZoomState(
            scale = newScale,
            tx = (focusX - viewWidth / 2f) * (1 - k) + tx * k,
            ty = (focusY - viewHeight / 2f) * (1 - k) + ty * k,
        )
    }

    fun pannedBy(dx: Float, dy: Float): ZoomState = copy(tx = tx + dx, ty = ty + dy)

    /** Keeps the image covering the view on axes where it is larger, centered where it is smaller. */
    fun clamped(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): ZoomState {
        val s = fitScale(viewWidth, viewHeight, imageWidth, imageHeight) * scale
        return copy(tx = clampAxis(tx, imageWidth * s, viewWidth), ty = clampAxis(ty, imageHeight * s, viewHeight))
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
        const val MAX_SCALE = 5f

        /**
         * The `FIT_CENTER` scale of [imageWidth] x [imageHeight] inside [viewWidth] x [viewHeight].
         *
         * All four are required to be positive, and that is load-bearing rather than pedantic: a
         * view that has not been measured yet has a width of 0 (Task 5 already had to handle one),
         * and a drawable without an intrinsic size reports -1. Returning 0 or Infinity for those
         * would put a degenerate matrix on screen and hide the mistake; throwing makes the caller's
         * "not measured yet, skip" check an observable one.
         */
        fun fitScale(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Float {
            require(viewWidth > 0f && viewHeight > 0f) { "view not measured: ${viewWidth}x$viewHeight" }
            require(imageWidth > 0f && imageHeight > 0f) { "image has no size: ${imageWidth}x$imageHeight" }
            return minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        }
    }
}
