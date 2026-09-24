package se.lublin.mumla.chat

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.BundleCompat

/**
 * An ImageView with pinch-zoom, drag and double-tap; the arithmetic lives in [ZoomState].
 *
 * Every event, ACTION_CANCEL included, must reach both detectors: `GestureDetector` clears its
 * double-tap flag only on ACTION_UP or `cancel()`, and a missed cancel leaves later drags dead.
 * Every state change ends in [applyState], so the screen always shows the clamped state.
 *
 * Across a configuration change the zoom factor is kept and the pan offsets (view pixels) are
 * re-clamped. The restored state applies to the first image that arrives (dialogs restore views
 * before the image loads); a later image resets to the fit. The host must give the view an
 * `android:id` and must not show a placeholder in it, which would consume the restored zoom.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    var state: ZoomState = ZoomState()
        private set

    /** Restored zoom waiting for an image to apply itself to; consumed once, by [applyState]. */
    private var pendingRestore: ZoomState? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    ).apply {
        // On by default since targetSdk M; it would add a second zoom to the double-tap gesture.
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                panBy(-distanceX, -distanceY)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomBy(if (state.scale > ZoomState.MIN_SCALE) 1f / state.scale else DOUBLE_TAP_SCALE, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performLongClick()
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        // Accessibility services read these flags; TalkBack's clicks bypass [onTouchEvent].
        isClickable = true
        isLongClickable = true
    }

    /**
     * Every event goes to both detectors, ACTION_CANCEL included. The parent may not intercept
     * while zoomed in or multi-touch; at the fit a pager must win.
     *
     * `super.onTouchEvent` is not called: View's click handling would count a single tap twice, and
     * `onSingleTapConfirmed` waits out the double-tap window.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1 || state.scale > ZoomState.MIN_SCALE)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The one place a new image resets the zoom (`setImageBitmap` goes through here). */
    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        state = ZoomState()
        applyState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyState()
    }

    override fun onSaveInstanceState(): Parcelable {
        // A restore still waiting for its image (e.g. rotated twice while loading) must be kept.
        val saved = pendingRestore ?: state
        return Bundle().apply {
            putParcelable(KEY_SUPER, super.onSaveInstanceState())
            putFloat(KEY_SCALE, saved.scale)
            putFloat(KEY_TX, saved.tx)
            putFloat(KEY_TY, saved.ty)
        }
    }

    /**
     * The saved state is untrusted input (an id collision or an older build). A Bundle without our
     * [KEY_SCALE] is handed to super unchanged. The zoom is coerced, not required, so a lowered
     * ceiling cannot crash a restore; the per-image ceiling is applied by [applyState].
     */
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle || !state.containsKey(KEY_SCALE)) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(BundleCompat.getParcelable(state, KEY_SUPER, Parcelable::class.java))
        pendingRestore = ZoomState(
            scale = state.finite(KEY_SCALE).coerceIn(ZoomState.MIN_SCALE, ZoomState.ABSOLUTE_MAX_SCALE),
            tx = state.finite(KEY_TX),
            ty = state.finite(KEY_TY),
        )
        applyState()
    }

    /** A stored float, with anything that is not a number at all read as 0. */
    private fun Bundle.finite(key: String): Float = getFloat(key).let { if (it.isFinite()) it else 0f }

    /** Scales around ([focusX], [focusY]) in view coordinates. Ignored until [canFit]. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (!canFit()) return
        val d = checkNotNull(drawable)
        state = state.scaledBy(
            factor,
            focusX,
            focusY,
            width.toFloat(),
            height.toFloat(),
            d.intrinsicWidth.toFloat(),
            d.intrinsicHeight.toFloat(),
        )
        applyState()
    }

    /** Moves the image by ([dx], [dy]) view pixels, within the bounds. Ignored as [zoomBy] is. */
    fun panBy(dx: Float, dy: Float) {
        if (!canFit()) return
        state = state.pannedBy(dx, dy)
        applyState()
    }

    /** Whether there is an image with a size, in a view with a size. */
    private fun canFit(): Boolean {
        val d = drawable ?: return false
        if (width == 0 || height == 0) return false
        return d.intrinsicWidth > 0 && d.intrinsicHeight > 0
    }

    private fun applyState() {
        if (!canFit()) return
        val d = checkNotNull(drawable)
        pendingRestore?.let {
            state = it
            pendingRestore = null
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        state = state.clamped(vw, vh, iw, ih)
        imageMatrix = state.toMatrix(vw, vh, iw, ih)
    }

    private companion object {
        /** Requested double-tap zoom; [ZoomState.scaledBy] may cap it at the image's ceiling. */
        const val DOUBLE_TAP_SCALE = 2.5f
        const val KEY_SUPER = "super"
        const val KEY_SCALE = "scale"
        const val KEY_TX = "tx"
        const val KEY_TY = "ty"
    }
}
