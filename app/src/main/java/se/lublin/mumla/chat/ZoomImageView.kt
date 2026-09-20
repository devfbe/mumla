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
 * An ImageView with pinch-zoom, drag and double-tap, driven by [ZoomState].
 *
 * The arithmetic lives in [ZoomState]; this class is the thin layer that turns gestures and layout
 * into calls on it. It keeps no gesture-scoped state of its own -- no "dragging" flag, no anchor
 * point, no pending focus. That is *not* the same as there being no gesture-scoped state: the two
 * detectors it borrows keep plenty, and only they can unwind it. `GestureDetector.mIsDoubleTapping`
 * is cleared by an ACTION_UP or by `cancel()` and by nothing else -- not by a fresh ACTION_DOWN --
 * so an ACTION_CANCEL that does not reach it leaves every later drag routed to `onDoubleTapEvent`
 * instead of `onScroll`, i.e. dead. Forwarding every event to both detectors, cancels included, is
 * therefore load-bearing and is pinned as such.
 * Every path that changes the state ends in [applyState], so the invariant "what is on screen is
 * the clamped state" holds after every single event.
 *
 * **Across a configuration change** the zoom factor is kept and the pan position is kept as far as
 * it still exists. That is a deliberate split: [ZoomState.scale] is relative to the fit scale, so
 * "three times as close" means the same thing in portrait and in landscape, while the offsets are
 * view pixels and half a screen is a different number of pixels after a rotation -- they are
 * re-clamped into the new bounds instead of being thrown away. The restored state is applied to the
 * first image that arrives, because a dialog restores its views before the image has finished
 * loading; a *second*, genuinely new image resets to the fit like any other.
 *
 * Two things the host has to get right for that to work. The view needs an `android:id`, because
 * View saves no state for a view without one. And nothing may be shown in *this* view before the
 * image: a placeholder or an error icon is a drawable like any other, it would spend the restored
 * zoom, and the real image arriving afterwards would count as the second image and reset to the
 * fit. Those belong in a separate view on top.
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
    )

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
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /**
     * The one place a new image resets the zoom. `setImageBitmap` reaches it through here, so there
     * is a single mechanism rather than one guard per entry point.
     */
    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        state = ZoomState()
        applyState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyState()
    }

    override fun onSaveInstanceState(): Parcelable =
        Bundle().apply {
            putParcelable(KEY_SUPER, super.onSaveInstanceState())
            putFloat(KEY_SCALE, state.scale)
            putFloat(KEY_TX, state.tx)
            putFloat(KEY_TY, state.ty)
        }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) {
            // Somebody else's state -- two views in one hierarchy sharing an id. Not ours to read.
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(BundleCompat.getParcelable(state, KEY_SUPER, Parcelable::class.java))
        pendingRestore = ZoomState(
            scale = state.getFloat(KEY_SCALE, ZoomState.MIN_SCALE),
            tx = state.getFloat(KEY_TX),
            ty = state.getFloat(KEY_TY),
        )
        applyState()
    }

    /**
     * Scales around ([focusX], [focusY]) in view coordinates. Ignored while there is nothing to be
     * relative to: a focus point means nothing without a measured view, and a scale means nothing
     * without an image, so remembering either would only land as a bogus offset at the first
     * layout.
     */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (!canFit()) return
        state = state.scaledBy(factor, focusX, focusY, width.toFloat(), height.toFloat())
        applyState()
    }

    /** Moves the image by ([dx], [dy]) view pixels, within the bounds. Ignored as [zoomBy] is. */
    fun panBy(dx: Float, dy: Float) {
        if (!canFit()) return
        state = state.pannedBy(dx, dy)
        applyState()
    }

    /**
     * Whether there is an image with a size, in a view with a size. One predicate for all three
     * callers, so "not ready yet" cannot come to mean two different things in the same class.
     */
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
        const val DOUBLE_TAP_SCALE = 2.5f
        const val KEY_SUPER = "super"
        const val KEY_SCALE = "scale"
        const val KEY_TX = "tx"
        const val KEY_TY = "ty"
    }
}
