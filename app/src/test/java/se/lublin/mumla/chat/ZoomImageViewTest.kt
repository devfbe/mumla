package se.lublin.mumla.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.util.SparseArray
import android.view.AbsSavedState
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

// RuntimeEnvironment.getApplication() ships with robolectric itself; the plan asks not to import
// androidx.test here (its claim that androidx.test:core is absent from the classpath is stale --
// it has been in the `unit-test` bundle since the version catalog was introduced -- but there is
// no reason to depend on it either).
@RunWith(RobolectricTestRunner::class)
class ZoomImageViewTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun values(view: ZoomImageView) = FloatArray(9).also { view.imageMatrix.getValues(it) }

    private fun viewWith(width: Int = 200, height: Int = 100, side: Int = 400): ZoomImageView =
        ZoomImageView(context).apply {
            setImageBitmap(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
            layout(0, 0, side, side)
        }

    // --- touch event plumbing ------------------------------------------------------------------

    private var eventTime = 0L
    private var downTime = 0L

    private fun at(delta: Long): Long = downTime + delta

    private fun pointers(action: Int, at: Long, xs: FloatArray, ys: FloatArray): MotionEvent {
        val props = Array(xs.size) { i ->
            MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply { x = xs[i]; y = ys[i]; pressure = 1f; size = 1f }
        }
        return MotionEvent.obtain(
            downTime, at, action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun ZoomImageView.touch(action: Int, at: Long, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = at
        eventTime = at
        dispatchTouchEvent(MotionEvent.obtain(downTime, at, action, x, y, 0))
    }

    private fun ZoomImageView.touchAll(action: Int, at: Long, xs: FloatArray, ys: FloatArray) {
        if (action == MotionEvent.ACTION_DOWN) downTime = at
        eventTime = at
        dispatchTouchEvent(pointers(action, at, xs, ys))
    }

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerUp(index: Int) =
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    // --- the plan's three tests ----------------------------------------------------------------

    @Test
    fun fitsTheImageCenteredAndAppliesZoomAndPan() {
        val view = ZoomImageView(context)
        view.setImageBitmap(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888))
        view.layout(0, 0, 400, 400)

        val fit = values(view)
        assertThat(fit[Matrix.MSCALE_X]).isEqualTo(2f)
        assertThat(fit[Matrix.MTRANS_X]).isEqualTo(0f)
        assertThat(fit[Matrix.MTRANS_Y]).isEqualTo(100f)

        view.zoomBy(2f, 0f, 0f)
        val zoomed = values(view)
        assertThat(zoomed[Matrix.MSCALE_X]).isEqualTo(4f)
        assertThat(zoomed[Matrix.MTRANS_X]).isEqualTo(0f)   // top-left corner stays put, clamped
        assertThat(zoomed[Matrix.MTRANS_Y]).isEqualTo(0f)   // 400 px tall now == view height

        view.panBy(-1000f, 0f)
        assertThat(values(view)[Matrix.MTRANS_X]).isEqualTo(-400f) // right edge clamped to the view
        assertThat(view.state).isEqualTo(ZoomState(2f, -200f, 0f))
    }

    @Test
    fun aSingleTapDispatchesAClick() {
        val view = viewWith()
        var clicks = 0
        view.setOnClickListener { clicks++ }

        val down = SystemClock.uptimeMillis()
        view.touch(MotionEvent.ACTION_DOWN, down, 10f, 10f)
        view.touch(MotionEvent.ACTION_UP, down + 20, 10f, 10f)
        // onSingleTapConfirmed only fires once the double-tap window has passed.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun newImageResetsTheState() {
        val view = viewWith()
        view.zoomBy(3f, 0f, 0f)
        view.setImageBitmap(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        assertThat(view.state).isEqualTo(ZoomState())
    }

    // --- the state is reset through one funnel, not per entry point -----------------------------

    /** setImageBitmap reaches the reset through setImageDrawable, so there is only one to break. */
    @Test
    fun aNewDrawableResetsTheStateToo() {
        val view = viewWith()
        view.zoomBy(3f, 0f, 0f)
        view.setImageDrawable(ColorDrawable(0xFF00FF00.toInt()))
        assertThat(view.state).isEqualTo(ZoomState())
    }

    /**
     * And so does a resource, which ImageView routes through setImageDrawable as well -- measured,
     * not assumed. A placeholder or an error icon set by the viewer dialog therefore cannot inherit
     * the zoom of the image before it. Integration assertion: it is the platform that routes it.
     */
    @Test
    fun aNewResourceResetsTheStateToo() {
        val view = viewWith()
        view.zoomBy(3f, 0f, 0f)
        view.setImageResource(se.lublin.mumla.R.drawable.ic_mumla)
        assertThat(view.state).isEqualTo(ZoomState())
    }

    // --- the three "there is nothing to fit yet" cases ------------------------------------------

    /**
     * A view that has not been measured has a width of 0. It must skip, not throw and not divide.
     *
     * And it must not *remember* the zoom either: a focus point means nothing without a frame to
     * measure it in, so a zoom asked for at that moment would be stored around a centre of (0, 0)
     * and land as a bogus offset at the first layout. What the first layout shows is the fit.
     */
    @Test
    fun zoomingAnUnmeasuredViewIsIgnored() {
        val view = ZoomImageView(context)
        view.setImageBitmap(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888))

        view.zoomBy(2f, 0f, 0f)
        view.panBy(10f, 10f)

        assertThat(view.state).isEqualTo(ZoomState())
        assertThat(view.imageMatrix.isIdentity).isTrue()
        view.layout(0, 0, 400, 400)
        assertThat(values(view)[Matrix.MSCALE_X]).isEqualTo(2f)
        assertThat(view.state).isEqualTo(ZoomState())
    }

    /** Same for an image that is not there yet: the zoom has nothing to be relative to. */
    @Test
    fun zoomingBeforeTheImageArrivesIsIgnored() {
        val view = ZoomImageView(context)
        view.layout(0, 0, 400, 400)

        view.zoomBy(2f, 200f, 200f)
        view.panBy(10f, 10f)

        // asserted before the image arrives: setImageBitmap would reset the state anyway and hide
        // the difference between "ignored" and "forgotten a moment later".
        assertThat(view.state).isEqualTo(ZoomState())
        view.setImageBitmap(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888))
        assertThat(values(view)[Matrix.MSCALE_X]).isEqualTo(2f)
    }

    /** A ColorDrawable reports an intrinsic size of -1. */
    @Test
    fun anImageWithoutAnIntrinsicSizeIsIgnored() {
        val view = ZoomImageView(context)
        view.setImageDrawable(ColorDrawable(0xFF00FF00.toInt()))
        view.layout(0, 0, 400, 400)

        view.zoomBy(2f, 0f, 0f)

        assertThat(view.imageMatrix.isIdentity).isTrue()
    }

    @Test
    fun zoomingWithoutAnImageIsIgnored() {
        val view = ZoomImageView(context)
        view.layout(0, 0, 400, 400)

        view.zoomBy(2f, 0f, 0f)

        assertThat(view.imageMatrix.isIdentity).isTrue()
    }

    // --- gestures ------------------------------------------------------------------------------

    /**
     * A symmetric pinch around (100, 100): the focus never moves, so nothing else in the stream can
     * produce the result -- put the focus in the middle of the view instead and tx/ty come out 0.
     *
     * Two properties of ScaleGestureDetector the numbers depend on, both measured here: the gesture
     * only begins once the span has changed by more than the span slop, and the first onScale after
     * onScaleBegin reports a factor of exactly 1 and re-bases the span. So 180 -> 260 is the no-op
     * that arms it and 260 -> 360 is the zoom.
     *
     * What that is measured against is worth being exact about, because two thirds of it are not
     * Android. The *logic* is AOSP's and really runs: `ShadowGestureDetector` and
     * `ShadowScaleGestureDetector` are in the path, but they delegate to the real class through a
     * reflector. The *constants* are Robolectric's fixtures at density 1.0 -- `ShadowViewConfiguration`
     * hard-codes touch slop 16 and paging touch slop 32, and the 170 px minimum scaling span sits
     * behind `robolectric.useRealMinScalingSpan` -- so the 2 x 16 px that decides where this pinch
     * starts is about 2 x 24 px on a real 3x device. These numbers pin the rule, not the pixel
     * counts a phone would use.
     */
    @Test
    fun aPinchZoomsAroundTheGestureFocus() {
        val view = viewWith(200, 200)

        view.touchAll(MotionEvent.ACTION_DOWN, 1000, floatArrayOf(10f), floatArrayOf(100f))
        view.touchAll(pointerDown(1), 1010, floatArrayOf(10f, 190f), floatArrayOf(100f, 100f))
        view.touchAll(MotionEvent.ACTION_MOVE, 1030, floatArrayOf(-30f, 230f), floatArrayOf(100f, 100f))
        view.touchAll(MotionEvent.ACTION_MOVE, 1050, floatArrayOf(-80f, 280f), floatArrayOf(100f, 100f))
        view.touchAll(pointerUp(1), 1060, floatArrayOf(-80f, 280f), floatArrayOf(100f, 100f))
        view.touchAll(MotionEvent.ACTION_UP, 1070, floatArrayOf(-80f), floatArrayOf(100f))

        assertThat(view.state.scale).isWithin(0.001f).of(360f / 260f)
        // focus (100,100) is up and left of the centre, so the image moves down and right.
        assertThat(view.state.tx).isWithin(0.01f).of(38.46f)
        assertThat(view.state.ty).isWithin(0.01f).of(38.46f)
    }

    /** A one-finger drag pans by exactly the finger's travel, in the finger's direction. */
    @Test
    fun aDragPansTheImage() {
        val view = viewWith(200, 200)
        view.zoomBy(2f, 200f, 200f) // slack of 200 px on both axes

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 1020, 150f, 130f)
        view.touch(MotionEvent.ACTION_MOVE, 1040, 180f, 130f)
        view.touch(MotionEvent.ACTION_UP, 1060, 180f, 130f)

        assertThat(view.state).isEqualTo(ZoomState(2f, 80f, 30f))
    }

    @Test
    fun aDoubleTapZoomsInAndTheNextOneZoomsOut() {
        val view = viewWith(200, 200)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1020, 100f, 100f)
        view.touch(MotionEvent.ACTION_DOWN, 1080, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1100, 100f, 100f)

        // 2, not DOUBLE_TAP_SCALE: 200 px of source blown up into a 400 px view has no pixels to
        // spare, so this image's ceiling is the floor of 2. The offset is asserted exactly, so a
        // ceiling that stopped being applied would show up here as 150 rather than 100.
        assertThat(view.state).isEqualTo(ZoomState(2f, 100f, 100f))

        view.touch(MotionEvent.ACTION_DOWN, 2000, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 2020, 100f, 100f)
        view.touch(MotionEvent.ACTION_DOWN, 2080, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 2100, 100f, 100f)

        assertThat(view.state).isEqualTo(ZoomState()) // back to the fit, recentred
    }

    /**
     * A cancelled gesture -- an incoming call, the screen going off, a parent stealing the touch --
     * leaves the image where it was, and the gesture after it pans by its own travel.
     *
     * This one case cannot tell a forwarded ACTION_CANCEL from a swallowed one, and that is not a
     * property of the design: cancelling *mid-drag* happens to be recoverable, because a plain
     * ACTION_DOWN re-bases GestureDetector's focus anyway. The cases that are not recoverable are
     * [aCancelDuringADoubleTapDoesNotDeafenTheNextDrag] and its vertical twin, which is where the
     * forwarding is actually pinned.
     */
    @Test
    fun aCancelledGestureDoesNotMoveTheImageAndTheNextOneStartsFresh() {
        val view = viewWith(200, 200)
        view.zoomBy(2f, 200f, 200f)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 1020, 150f, 100f)
        view.touch(MotionEvent.ACTION_CANCEL, 1040, 150f, 100f)
        val afterCancel = view.state
        assertThat(afterCancel).isEqualTo(ZoomState(2f, 50f, 0f))

        // The next gesture starts from where the image is, not from where the cancelled one was.
        view.touch(MotionEvent.ACTION_DOWN, 2000, 300f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 2020, 320f, 100f)
        view.touch(MotionEvent.ACTION_UP, 2040, 320f, 100f)

        assertThat(view.state).isEqualTo(ZoomState(2f, 70f, 0f))
    }

    /**
     * Dragging on after a double-tap must not be a fourth gesture. `ScaleGestureDetector` turns
     * `isQuickScaleEnabled` on by itself from targetSdk M upwards, which puts a second, continuous
     * zoom on the same gesture this view's own [ZoomImageView.onDoubleTap] already owns -- two zoom
     * sources on one gesture, neither chosen nor pinned. Measured before it was turned off: this
     * drag took the scale from 2.5 to 4.69.
     *
     * What is left is the double-tap's own step and nothing else; `GestureDetector` is still in its
     * double-tap window, so the moves reach `onDoubleTapEvent` rather than `onScroll` and the image
     * does not pan either. The pan starts with the next gesture, which is
     * [aCancelDuringADoubleTapDoesNotDeafenTheNextDrag].
     */
    @Test
    fun draggingOnAfterADoubleTapDoesNotKeepZooming() {
        val view = viewWith(2000, 2000)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1020, 100f, 100f)
        view.touch(MotionEvent.ACTION_DOWN, 1080, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 1100, 100f, 180f)
        view.touch(MotionEvent.ACTION_MOVE, 1120, 100f, 260f)
        view.touch(MotionEvent.ACTION_MOVE, 1140, 100f, 340f)
        view.touch(MotionEvent.ACTION_UP, 1160, 100f, 340f)

        assertThat(view.state).isEqualTo(ZoomState(2.5f, 150f, 150f))
    }

    /**
     * ACTION_CANCEL arriving *during* a double-tap, which is the one the view cannot shrug off.
     *
     * `GestureDetector.mIsDoubleTapping` is cleared only by `cancel()` or by an ACTION_UP; a fresh
     * ACTION_DOWN does not clear it. Swallow the cancel and the flag stays set for the rest of the
     * view's life, so every following ACTION_MOVE is routed to `onDoubleTapEvent` instead of
     * `onScroll` -- the next drag, and every drag after it, moves nothing at all.
     *
     * The image is big enough that its zoom ceiling is the full 5x, so the
     * double-tap lands on 2.5 and the pan that follows stays inside the slack: nothing here is
     * asserted downstream of a clamp.
     */
    @Test
    fun aCancelDuringADoubleTapDoesNotDeafenTheNextDrag() {
        val view = viewWith(2000, 2000)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1020, 100f, 100f)
        view.touch(MotionEvent.ACTION_DOWN, 1080, 100f, 100f) // onDoubleTap fires here
        assertThat(view.state).isEqualTo(ZoomState(2.5f, 150f, 150f))
        view.touch(MotionEvent.ACTION_CANCEL, 1100, 100f, 100f)

        view.touch(MotionEvent.ACTION_DOWN, 2000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 2020, 200f, 100f)
        view.touch(MotionEvent.ACTION_UP, 2040, 200f, 100f)

        assertThat(view.state).isEqualTo(ZoomState(2.5f, 250f, 150f))
    }

    /**
     * The same history, dragged *down* instead of right. Split from the horizontal case on purpose:
     * with `isQuickScaleEnabled` left on, this is the one that came out as a zoom rather than as a
     * dead drag, because `ScaleGestureDetector.mAnchoredScaleMode` is reset only by an UP or a
     * CANCEL and a vertical drag is exactly what drives it. The scale is asserted as well as the
     * offset, so re-enabling quick scale without forwarding the cancel is caught too.
     */
    @Test
    fun aCancelDuringADoubleTapDoesNotTurnTheNextDragIntoAZoom() {
        val view = viewWith(2000, 2000)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1020, 100f, 100f)
        view.touch(MotionEvent.ACTION_DOWN, 1080, 100f, 100f)
        view.touch(MotionEvent.ACTION_CANCEL, 1100, 100f, 100f)

        view.touch(MotionEvent.ACTION_DOWN, 2000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 2020, 100f, 220f)
        view.touch(MotionEvent.ACTION_UP, 2040, 100f, 220f)

        assertThat(view.state).isEqualTo(ZoomState(2.5f, 150f, 270f))
    }

    /**
     * Lifting one of two fingers must not make the image jump to the surviving finger. The
     * re-basing that prevents it is AOSP's, in GestureDetector, not this view's -- so this is an
     * integration assertion against the real detector: it is here to go red if a compileSdk bump
     * changes that behaviour underneath us, not to pin code in this file.
     */
    @Test
    fun liftingOneOfTwoFingersDoesNotJumpTheImage() {
        val view = viewWith(200, 200)
        view.zoomBy(2f, 200f, 200f)

        view.touchAll(MotionEvent.ACTION_DOWN, 1000, floatArrayOf(100f), floatArrayOf(200f))
        view.touchAll(pointerDown(1), 1010, floatArrayOf(100f, 300f), floatArrayOf(200f, 200f))
        view.touchAll(pointerUp(1), 1020, floatArrayOf(100f, 300f), floatArrayOf(200f, 200f))
        val afterLift = view.state
        view.touchAll(MotionEvent.ACTION_MOVE, 1040, floatArrayOf(140f), floatArrayOf(200f))
        view.touchAll(MotionEvent.ACTION_UP, 1060, floatArrayOf(140f), floatArrayOf(200f))

        // The surviving finger travelled 40 px; the image follows it by 40 px and nothing else.
        assertThat(view.state.tx - afterLift.tx).isWithin(0.01f).of(40f)
    }

    // --- the view in somebody else's hands ------------------------------------------------------

    /**
     * Task 8 hangs this view inside a scrolling container. A pan must then win against the parent,
     * or the drag is taken away mid-gesture -- and what arrives here in exchange is the
     * ACTION_CANCEL the two cancel tests are about.
     */
    @Test
    fun aGestureOnAZoomedImageIsTakenFromAScrollingParent() {
        val parent = RecordingParent(context)
        val view = viewWith(200, 200)
        parent.addView(view)
        view.zoomBy(2f, 200f, 200f)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 1020, 150f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1040, 150f, 100f)

        assertThat(parent.disallow).contains(true)
    }

    /** At the fit there is nothing to pan, so the swipe belongs to the parent and is left to it. */
    @Test
    fun aGestureOnAnUnzoomedImageIsLeftToTheParent() {
        val parent = RecordingParent(context)
        val view = viewWith(200, 200)
        parent.addView(view)

        view.touch(MotionEvent.ACTION_DOWN, 1000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 1020, 150f, 100f)
        view.touch(MotionEvent.ACTION_UP, 1040, 150f, 100f)

        assertThat(parent.disallow).doesNotContain(true)
    }

    /** A pinch is never the parent's, zoomed or not: two fingers are unambiguous. */
    @Test
    fun aSecondFingerIsTakenFromTheParentEvenAtTheFit() {
        val parent = RecordingParent(context)
        val view = viewWith(200, 200)
        parent.addView(view)

        view.touchAll(MotionEvent.ACTION_DOWN, 1000, floatArrayOf(10f), floatArrayOf(100f))
        view.touchAll(pointerDown(1), 1010, floatArrayOf(10f, 190f), floatArrayOf(100f, 100f))

        assertThat(parent.disallow).contains(true)
    }

    // --- accessibility ---------------------------------------------------------------------------

    /**
     * The two flags an accessibility service reads to decide which actions to offer. They are set
     * in the constructor rather than left to `setOnClickListener`, so they are true before the
     * viewer has wired anything up -- and they are asserted before this test wires anything up, for
     * the same reason.
     */
    @Test
    fun theViewAdvertisesItselfAsClickableAndLongClickable() {
        val view = viewWith()

        assertThat(view.isClickable).isTrue()
        assertThat(view.isLongClickable).isTrue()
    }

    /** TalkBack's click goes to performClick directly and never touches onTouchEvent. */
    @Test
    fun anAccessibilityClickReachesTheClickListener() {
        val view = viewWith()
        var clicks = 0
        view.setOnClickListener { clicks++ }

        assertThat(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)).isTrue()

        assertThat(clicks).isEqualTo(1)
    }

    /** And a long press on the screen reaches the long-click listener, through the detector. */
    @Test
    fun aLongPressDispatchesALongClick() {
        val view = viewWith()
        var longClicks = 0
        view.setOnLongClickListener { longClicks++; true }

        val down = SystemClock.uptimeMillis()
        view.touch(MotionEvent.ACTION_DOWN, down, 10f, 10f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))

        assertThat(longClicks).isEqualTo(1)
    }

    /** A single tap still counts once, which is why super.onTouchEvent is deliberately not called. */
    @Test
    fun aSingleTapStillCountsOnceWithTheClickableFlagsSet() {
        val view = viewWith()
        var clicks = 0
        view.setOnClickListener { clicks++ }

        val down = SystemClock.uptimeMillis()
        view.touch(MotionEvent.ACTION_DOWN, down, 10f, 10f)
        view.touch(MotionEvent.ACTION_UP, down + 20, 10f, 10f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertThat(clicks).isEqualTo(1)
    }

    /** A parent that writes down every time it is told to keep out of a gesture. */
    private class RecordingParent(context: Context) : FrameLayout(context) {
        val disallow = mutableListOf<Boolean>()

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallow += disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    // --- configuration changes -----------------------------------------------------------------

    /**
     * A rotation resizes the view. The zoom factor is kept -- it is relative to the fit, so it means
     * the same thing in the new geometry -- and the pan is re-clamped into the new bounds.
     */
    @Test
    fun aSizeChangeKeepsTheZoomAndReclampsThePan() {
        val view = viewWith(200, 200, side = 400)
        view.zoomBy(2f, 0f, 0f)
        assertThat(view.state).isEqualTo(ZoomState(2f, 200f, 200f))

        view.layout(0, 0, 800, 200) // landscape

        assertThat(view.state.scale).isEqualTo(2f)
        // fit is now 1 (200x200 into 800x200), displayed 400x400: slack 0 horizontally, 100 down.
        assertThat(view.state).isEqualTo(ZoomState(2f, 0f, 100f))
        assertThat(values(view)[Matrix.MSCALE_X]).isEqualTo(2f)
    }

    /** A rotation in the middle of a pinch: the gesture continues in the new geometry. */
    @Test
    fun aSizeChangeDuringAGestureIsSurvived() {
        val view = viewWith(200, 200)

        view.touchAll(MotionEvent.ACTION_DOWN, 1000, floatArrayOf(10f), floatArrayOf(100f))
        view.touchAll(pointerDown(1), 1010, floatArrayOf(10f, 190f), floatArrayOf(100f, 100f))
        view.touchAll(MotionEvent.ACTION_MOVE, 1030, floatArrayOf(-30f, 230f), floatArrayOf(100f, 100f))
        view.layout(0, 0, 800, 200)
        view.touchAll(MotionEvent.ACTION_MOVE, 1050, floatArrayOf(-80f, 280f), floatArrayOf(100f, 100f))
        view.touchAll(MotionEvent.ACTION_UP, 1070, floatArrayOf(-80f), floatArrayOf(100f))

        assertThat(view.state.scale).isGreaterThan(1f)
        assertThat(view.state).isEqualTo(view.state.clamped(800f, 200f, 200f, 200f))
    }

    /**
     * The zoom survives the rotation of a dialog whose image is loaded asynchronously: the state is
     * restored before the image exists, and is applied to the image when it arrives. This goes
     * through the real hierarchy save/restore, which also enforces that super is called on both
     * sides -- View throws IllegalStateException if it is not.
     */
    @Test
    fun theZoomSurvivesARestoreThatArrivesBeforeTheImage() {
        val saved = savedStateOf(viewWith(200, 200).apply { zoomBy(2f, 0f, 0f) })

        val after = ZoomImageView(context).apply { id = SAVED_ID }
        after.restoreHierarchyState(saved)
        after.layout(0, 0, 400, 400)
        assertThat(after.state).isEqualTo(ZoomState()) // nothing to apply it to yet
        after.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))

        assertThat(after.state).isEqualTo(ZoomState(2f, 200f, 200f))
        // and it is spent: the next image starts from the fit again.
        after.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
        assertThat(after.state).isEqualTo(ZoomState())
    }

    /**
     * And it survives a *second* rotation taken while the image is still loading. The dialog loads
     * over the network, so that window is seconds long and is exactly when a phone gets turned; one
     * round cannot see the bug, because the round that fails is the one that has to save a zoom it
     * has not applied yet. What is saved is therefore the pending restore where there is one.
     */
    @Test
    fun theZoomSurvivesASecondRotationTakenWhileTheImageIsStillLoading() {
        val saved = savedStateOf(viewWith(200, 200).apply { zoomBy(2f, 0f, 0f) })

        val firstRotation = ZoomImageView(context).apply { id = SAVED_ID }
        firstRotation.restoreHierarchyState(saved)
        firstRotation.layout(0, 0, 400, 400)
        val savedAgain = SparseArray<Parcelable>().also { firstRotation.saveHierarchyState(it) }

        val secondRotation = ZoomImageView(context).apply { id = SAVED_ID }
        secondRotation.restoreHierarchyState(savedAgain)
        secondRotation.layout(0, 0, 400, 400)
        secondRotation.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))

        assertThat(secondRotation.state).isEqualTo(ZoomState(2f, 200f, 200f))
    }

    @Test
    fun theZoomIsRestoredImmediatelyWhenTheImageIsAlreadyThere() {
        val saved = savedStateOf(viewWith(200, 200).apply { zoomBy(2f, 0f, 0f) })

        val after = viewWith(200, 200).apply { id = SAVED_ID }
        after.restoreHierarchyState(saved)

        assertThat(after.state).isEqualTo(ZoomState(2f, 200f, 200f))
        assertThat(values(after)[Matrix.MSCALE_X]).isEqualTo(4f)
    }

    /**
     * Saved state is *input*, not an invariant of this process: the bytes were written by another
     * process, possibly by another build. A number [ZoomState] cannot hold at all has to land
     * inside the range, not throw out of `restoreHierarchyState`.
     */
    @Test
    fun aSavedZoomOutsideWhatTheStateCanHoldIsCoercedRatherThanThrown() {
        val view = ZoomImageView(context).apply { id = SAVED_ID }

        view.restoreHierarchyState(savedStateWithScale(900f))
        view.layout(0, 0, 400, 400)
        view.setImageBitmap(Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888))

        assertThat(view.state.scale).isEqualTo(5f) // this image's ceiling, applied on the way in
    }

    /**
     * And the scenario the coercion exists for: the ceiling is per image and can drop between
     * releases, so a zoom saved by yesterday's build comes back at today's ceiling. Without it a
     * user with a stored 5x crashed on the first rotation after the update.
     */
    @Test
    fun aSavedZoomAboveThisImagesCeilingComesBackAtTheCeiling() {
        val view = ZoomImageView(context).apply { id = SAVED_ID }

        view.restoreHierarchyState(savedStateWithScale(5f))
        view.layout(0, 0, 400, 400)
        view.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))

        assertThat(view.state.scale).isEqualTo(2f) // 200 px of source into 400: no pixels to spare
    }

    /** And a scale or an offset that is not a number at all is dropped, not propagated as NaN. */
    @Test
    fun aNonFiniteSavedZoomFallsBackToTheFit() {
        val saved = savedStateOf(viewWith(200, 200))
        (saved.get(SAVED_ID) as Bundle).apply {
            putFloat("scale", Float.NaN)
            putFloat("tx", Float.POSITIVE_INFINITY)
        }
        val view = ZoomImageView(context).apply { id = SAVED_ID }

        view.restoreHierarchyState(saved)
        view.layout(0, 0, 400, 400)
        view.setImageBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))

        assertThat(view.state).isEqualTo(ZoomState())
    }

    /** State belonging to somebody else (an id collision) must not crash the view. */
    @Test
    fun foreignSavedStateIsIgnored() {
        val view = viewWith(200, 200).apply { id = SAVED_ID }
        view.zoomBy(2f, 0f, 0f)
        val foreign = SparseArray<Parcelable>().apply { put(SAVED_ID, AbsSavedState.EMPTY_STATE) }

        view.restoreHierarchyState(foreign)

        assertThat(view.state).isEqualTo(ZoomState(2f, 200f, 200f))
    }

    /**
     * A real save with one float overwritten. Going through the view's own save keeps every other
     * key honest; "scale" is [ZoomImageView]'s private key, named here because the point of the
     * test is precisely that the bytes on the other side of the boundary are not under our control.
     */
    private fun savedStateWithScale(scale: Float): SparseArray<Parcelable> =
        savedStateOf(viewWith(200, 200)).also { (it.get(SAVED_ID) as Bundle).putFloat("scale", scale) }

    /**
     * The other half of an id collision, and the common one: somebody else's *Bundle*. The
     * "is it a Bundle" test alone lets it through, and what follows is silent -- there is no KEY_SUPER
     * in it, so super is restored from null and the real super state is dropped, and then the
     * default zoom is adopted as if it had been saved. One of our own keys is the marker that tells
     * the two apart.
     *
     * What is left is the platform's own diagnostic for exactly this mistake, which is what any
     * other View in the hierarchy would raise too: loud, and about the real cause.
     */
    @Test
    fun aForeignBundleIsNotAdoptedAsOurOwnSavedState() {
        val view = viewWith(200, 200).apply { id = SAVED_ID }
        view.zoomBy(2f, 0f, 0f)
        val foreign = SparseArray<Parcelable>().apply {
            put(SAVED_ID, Bundle().apply { putString("somebody-elses-key", "x") })
        }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            view.restoreHierarchyState(foreign)
        }

        assertThat(thrown).hasMessageThat().contains("same id in the same hierarchy")
        assertThat(view.state).isEqualTo(ZoomState(2f, 200f, 200f))
    }

    /**
     * What survives a rotation, measured rather than asserted, because the honest answer is not
     * "the zoom exactly and the position approximately" in the way that reads.
     *
     * [ZoomState.scale] is kept exactly, and it is a ratio, so it keeps *meaning* the same thing:
     * "twice as close as the fit". It is not the number the matrix ends up with. The fit is decided
     * by the limiting axis and the limiting axis changes with the rotation, so the image genuinely
     * appears at a different size -- here the matrix scale goes from 2.0 to 2.67, a third *larger*,
     * for a state that did not change. The invariant is about the *limiting* axis: the visible
     * fraction along it stays 1 / scale, and which axis that is changes with the rotation.
     */
    @Test
    fun aRotationKeepsTheZoomFactorButNotTheSizeOnScreen() {
        val view = ZoomImageView(context)
        view.setImageBitmap(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        view.layout(0, 0, 400, 800) // fit decided by width: 400/400 = 1
        view.zoomBy(2f, 200f, 400f)
        assertThat(values(view)[Matrix.MSCALE_X]).isEqualTo(2f)

        view.layout(0, 0, 800, 400) // fit now decided by height: 400/300 = 1.333

        assertThat(view.state.scale).isEqualTo(2f)
        assertThat(values(view)[Matrix.MSCALE_X]).isWithin(0.001f).of(2.6667f)
    }

    /**
     * And the offset is kept in *pixels*, not in proportion, which is a weaker promise than the
     * word "approximately" suggests. Measured here: a pan sitting halfway to the edge comes out of
     * the rotation three quarters of the way there, because the pannable range shrank from 200 px
     * to 133 and the offset did not shrink with it.
     *
     * Kept as it is, deliberately. Storing `tx / slack` instead is not the three-line change it
     * looks like: a real rotation of the viewer goes through save/restore, not through
     * [onSizeChanged], so the *saved format* would have to carry the normalised offset -- and the
     * restore parks in `pendingRestore` before any geometry is known, so that parked value could no
     * longer be a [ZoomState]. That is a change of shape, and it belongs with the viewer in task 8,
     * which owns the restore conditions anyway. This test is here so the claim in the KDoc is a
     * measurement and so the day someone does normalise it, it goes red on purpose.
     */
    @Test
    fun aRotationKeepsTheOffsetInPixelsRatherThanInProportion() {
        val view = ZoomImageView(context)
        view.setImageBitmap(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        view.layout(0, 0, 400, 800)
        view.zoomBy(2f, 200f, 400f)

        view.panBy(-1000f, 0f)
        assertThat(view.state.tx).isEqualTo(-200f) // hard against the edge: the range is 200 px
        view.panBy(100f, 0f)
        assertThat(view.state.tx).isEqualTo(-100f) // halfway back: 50% of the range

        view.layout(0, 0, 800, 400)

        val rangeAfter = ZoomState(2f, -10_000f, 0f).clamped(800f, 400f, 400f, 300f).tx
        assertThat(rangeAfter).isWithin(0.01f).of(-133.33f)
        assertThat(view.state.tx).isEqualTo(-100f) // 75% of the range now, not 50%
    }

    private fun savedStateOf(view: ZoomImageView): SparseArray<Parcelable> {
        view.id = SAVED_ID
        return SparseArray<Parcelable>().also { view.saveHierarchyState(it) }
    }

    private companion object {
        const val SAVED_ID = 42
    }
}
