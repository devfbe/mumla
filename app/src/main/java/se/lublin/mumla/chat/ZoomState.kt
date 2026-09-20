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
 *  - [scale] survives a configuration change unchanged, and keeps *meaning* the same thing: "n
 *    times closer than the fit". It does not keep the image the same size on screen. The fit is
 *    decided by the limiting axis, the limiting axis changes with the rotation, and the matrix
 *    scale changes with it -- measured, 2.0 becomes 2.67 for a 4:3 image turned into landscape.
 *    The invariant that does hold is about that axis: the visible fraction along the limiting axis
 *    is 1 / [scale]. [tx]/[ty] survive less well again. They are view pixels, [ZoomImageView]
 *    restores them as such and lets [clamped] pull them into the new bounds, so the offset is kept
 *    in pixels and not in proportion: a pan halfway to the edge can come out three quarters of the
 *    way there. "Approximately" means that, and no more.
 *  - the state is the same size for a 100x100 and a 4000x3000 image, so nothing here scales with
 *    the image.
 *
 * Every instance is *finite* by construction, and that is all the constructor promises. A factor
 * that is not a number at all is a programming error and fails loudly here rather than turning the
 * view's matrix into NaN for the rest of its life; the absolute bound it is checked against is far
 * past any policy, so it catches arithmetic that has gone wrong and nothing else.
 *
 * The zoom *ceiling* deliberately does not live here, because it is not a property of a state: it
 * depends on the image, and a state that is legitimate for one image is over the limit for the
 * next. It lives in [maxScale] and is applied by [scaledBy] and [clamped], both of which know the
 * geometry. See [maxScale] for why the number is what it is.
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
     * [MIN_SCALE] and at this image's [maxScale]. The focus point is not required to be inside the
     * view or inside the image; the same linear rule applies wherever it is. The *offsets* are not
     * clamped here -- that is [clamped]'s job, so that a gesture can overshoot within one event and
     * be pulled back once, in one place.
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
     * and the zoom inside this image's [maxScale].
     *
     * The scale is clamped here as well as in [scaledBy] because this is the one method the view
     * runs after *every* change, including the ones that did not come from a gesture: a zoom
     * restored from an older release, or the same state surviving into a different image. Without
     * it a restored zoom above the ceiling would simply stay there until the next pinch snapped it
     * down under the user's fingers.
     */
    fun clamped(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): ZoomState {
        // coerceAtMost, not coerceIn: the floor is the constructor's, and a second one here would
        // be a guard no test could ever make fire.
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

        /**
         * A sanity bound, not a policy: the ceiling is per image (see [maxScale]) and cannot be
         * checked in the constructor, so what is left here only catches arithmetic that has gone
         * wrong -- an overflow, a runaway factor, a number that is not one.
         */
        const val ABSOLUTE_MAX_SCALE = 100f

        /**
         * The zoom ceiling for this image in this view: `1 / fitScale`, held between [MIN_CEILING]
         * and [MAX_CEILING].
         *
         * `1 / fitScale` is the zoom at which one source pixel covers exactly one screen pixel.
         * Past it there is no more detail to uncover, only interpolation -- and that is not a
         * theoretical worry here, because the viewer decodes with `loadFull(source, screenWidth,
         * screenHeight)` and `BitmapUtils.resizeKeepingAspect` never enlarges, so the bitmap
         * arrives exactly view-sized on the limiting axis and the budget is 1. A flat ceiling of 5
         * meant every one of those images could be blown up until a single source pixel covered
         * twenty-five screen ones.
         *
         * The two bounds are the two ways the budget is useless. Below [MIN_CEILING]: an image the
         * fit already had to enlarge has a budget under 1, and refusing to zoom it at all would
         * make a small picture impossible to look at. Above [MAX_CEILING]: a very large source has
         * a budget of 20 or more, but the decoder threw those pixels away long before this class
         * saw them, so the budget is describing detail that is not in the bitmap.
         */
        fun maxScale(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Float =
            (1f / fitScale(viewWidth, viewHeight, imageWidth, imageHeight)).coerceIn(MIN_CEILING, MAX_CEILING)

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
