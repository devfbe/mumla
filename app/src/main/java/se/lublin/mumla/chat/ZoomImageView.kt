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
 * **Across a configuration change** the zoom factor is kept exactly and the pan position only
 * roughly. [ZoomState.scale] is relative to the fit, so "three times as close" still *means* the
 * same thing after a rotation -- though not the same size on screen, because the fit follows the
 * limiting axis and the limiting axis changes. The offsets are view pixels and are restored as
 * pixels, then re-clamped into the new bounds rather than thrown away, so a pan halfway to the edge
 * can come back three quarters of the way there; [ZoomState] says what that costs. The restored
 * state is applied to the first image that arrives, because a dialog restores its views before the
 * image has finished loading; a *second*, genuinely new image resets to the fit like any other.
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
    ).apply {
        // Off deliberately. ScaleGestureDetector turns this on by itself from targetSdk M upwards,
        // which hangs a second, continuous zoom off the double-tap that [onDoubleTap] below already
        // owns: two zoom sources on one gesture, neither of them chosen. Measured with it on, a
        // drag after a double-tap ran the scale from 2.5 to 4.69.
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
        // Set here rather than left to setOnClickListener: these two flags are what an accessibility
        // service reads to decide which actions to offer, and TalkBack's click and long-click go
        // straight to performClick/performLongClick without ever reaching [onTouchEvent].
        isClickable = true
        isLongClickable = true
    }

    /**
     * Every event goes to both detectors, ACTION_CANCEL included -- see the class KDoc for what
     * happens when one does not.
     *
     * The parent is asked to keep its hands off while there is something here to pan or pinch. A
     * `ViewPager2` or a scrolling container otherwise takes the drag away mid-pan, and what this
     * view gets in exchange is precisely the ACTION_CANCEL that the two cancel tests are about. The
     * criterion is deliberately coarse -- zoomed in at all, or more than one finger -- rather than
     * per axis: at the fit there is nothing to pan on either axis, and that is the case where a
     * pager must win.
     *
     * `super.onTouchEvent` is not called, and that is a decision rather than an omission. View's own
     * click handling posts a `performClick` from ACTION_UP, which would land *alongside* the one
     * [GestureDetector.SimpleOnGestureListener.onSingleTapConfirmed] sends -- a single tap counted
     * twice. The confirmed one is the one worth keeping: it waits out the double-tap window, so the
     * first tap of a double-tap does not also dismiss the dialog. What View's path would otherwise
     * have contributed is covered above: the accessibility actions by the two flags in [init], the
     * long press by `onLongPress`.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1 || state.scale > ZoomState.MIN_SCALE)
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

    override fun onSaveInstanceState(): Parcelable {
        // A restore that has not found an image yet lives in pendingRestore, not in state -- and
        // the dialog loads over the network, so a second rotation inside that window is ordinary.
        // Saving `state` there would save the default and throw the user's zoom away.
        val saved = pendingRestore ?: state
        return Bundle().apply {
            putParcelable(KEY_SUPER, super.onSaveInstanceState())
            putFloat(KEY_SCALE, saved.scale)
            putFloat(KEY_TX, saved.tx)
            putFloat(KEY_TY, saved.ty)
        }
    }

    /**
     * Everything read here is *input*, not an invariant: the bytes were written by some other
     * process, possibly by an older build of this app, and both halves of that are real.
     *
     * "Not a Bundle" is only the easy half of an id collision -- somebody else's Bundle is the
     * common one, and letting it through is silent: there is no [KEY_SUPER] in it, so super is
     * restored from null and the real super state is dropped, and the default zoom is then adopted
     * as if it had been saved. One of our own keys is the marker that tells the two apart.
     *
     * And the zoom is coerced rather than required. The ceiling is per image and can drop between
     * releases, so a stored zoom above it is an ordinary event, not a bug: asserting it here would
     * turn the first rotation after an update into a crash inside `restoreHierarchyState`. What is
     * coerced to here is only what [ZoomState] can hold at all -- the *ceiling* is applied by
     * [applyState], once, for every state however it arrived, rather than a second time here where
     * no test could tell the two apart.
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

    /**
     * Scales around ([focusX], [focusY]) in view coordinates. Ignored while there is nothing to be
     * relative to: a focus point means nothing without a measured view, and a scale means nothing
     * without an image, so remembering either would only land as a bogus offset at the first
     * layout.
     */
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
