package se.lublin.mumla.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.SparseArray
import android.view.AbsSavedState
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.lublin.humla.testutil.idleMainLooperFor
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ZoomImageViewTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun values(view: ZoomImageView) = FloatArray(9).also { view.imageMatrix.getValues(it) }

    private fun viewWith(width: Int = 200, height: Int = 100, side: Int = 400): ZoomImageView =
        ZoomImageView(context).apply {
            setImageBitmap(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
            layout(0, 0, side, side)
        }

    private var downTime = 0L

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
        dispatchTouchEvent(MotionEvent.obtain(downTime, at, action, x, y, 0))
    }

    private fun ZoomImageView.touchAll(action: Int, at: Long, xs: FloatArray, ys: FloatArray) {
        if (action == MotionEvent.ACTION_DOWN) downTime = at
        dispatchTouchEvent(pointers(action, at, xs, ys))
    }

    /** Two taps at (100, 100) from [t0]; without [finish] the second finger stays down. */
    private fun ZoomImageView.doubleTap(t0: Long, finish: Boolean = true) {
        touch(MotionEvent.ACTION_DOWN, t0, 100f, 100f)
        touch(MotionEvent.ACTION_UP, t0 + 20, 100f, 100f)
        touch(MotionEvent.ACTION_DOWN, t0 + 80, 100f, 100f)
        if (finish) touch(MotionEvent.ACTION_UP, t0 + 100, 100f, 100f)
    }

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerUp(index: Int) =
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test
    fun fitsTheImageCenteredAndAppliesZoomAndPan() {
        val view = viewWith()

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

    /**
     * A new bitmap, drawable or resource resets the zoom: setImageBitmap and ImageView's resource
     * path both reach the reset through setImageDrawable.
     */
    @Test
    fun aNewImageResetsTheState() {
        val replacements = listOf<ZoomImageView.() -> Unit>(
            { setImageBitmap(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)) },
            { setImageDrawable(ColorDrawable(0xFF00FF00.toInt())) },
            { setImageResource(se.lublin.mumla.R.drawable.ic_stat_notify) },
        )
        for ((index, replace) in replacements.withIndex()) {
            val view = viewWith()
            view.zoomBy(3f, 0f, 0f)
            view.replace()
            assertWithMessage("replacement $index").that(view.state).isEqualTo(ZoomState())
        }
    }

    /**
     * An unmeasured view (width 0) must skip, not throw or divide, and must not remember the zoom:
     * it would land as a bogus offset around (0, 0) at the first layout.
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

        // Asserted before the image arrives: setImageBitmap would reset the state anyway.
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

    /**
     * A symmetric pinch around (100, 100), so the focus never moves. ScaleGestureDetector only
     * begins once the span changed by more than the span slop, and its first onScale reports a
     * factor of 1 and re-bases the span: 180 -> 260 arms it, 260 -> 360 zooms. The slop values are
     * Robolectric's density-1.0 fixtures, so these numbers pin the rule, not a phone's pixel counts.
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

        view.doubleTap(1000)

        // 2, not DOUBLE_TAP_SCALE: 200 px of source in a 400 px view caps this image's ceiling at 2.
        assertThat(view.state).isEqualTo(ZoomState(2f, 100f, 100f))

        view.doubleTap(2000)

        assertThat(view.state).isEqualTo(ZoomState()) // back to the fit, recentred
    }

    /**
     * A cancelled gesture leaves the image where it was, and the next gesture pans by its own
     * travel. The cancel forwarding itself is pinned by
     * [aCancelDuringADoubleTapDoesNotDeafenTheNextDrag] and its vertical twin.
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
     * Dragging on after a double-tap must not zoom further: `ScaleGestureDetector` enables
     * `isQuickScaleEnabled` itself from targetSdk M on, which this view turns off. The moves reach
     * `onDoubleTapEvent`, so the image does not pan either.
     */
    @Test
    fun draggingOnAfterADoubleTapDoesNotKeepZooming() {
        val view = viewWith(2000, 2000)

        view.doubleTap(1000, finish = false)
        view.touch(MotionEvent.ACTION_MOVE, 1100, 100f, 180f)
        view.touch(MotionEvent.ACTION_MOVE, 1120, 100f, 260f)
        view.touch(MotionEvent.ACTION_MOVE, 1140, 100f, 340f)
        view.touch(MotionEvent.ACTION_UP, 1160, 100f, 340f)

        assertThat(view.state).isEqualTo(ZoomState(2.5f, 150f, 150f))
    }

    /**
     * ACTION_CANCEL during a double-tap. `GestureDetector.mIsDoubleTapping` is cleared only by
     * `cancel()` or ACTION_UP, so a swallowed cancel would route every later ACTION_MOVE to
     * `onDoubleTapEvent` and no drag would move anything again.
     */
    @Test
    fun aCancelDuringADoubleTapDoesNotDeafenTheNextDrag() {
        val view = viewWith(2000, 2000)

        view.doubleTap(1000, finish = false) // onDoubleTap fires here
        assertThat(view.state).isEqualTo(ZoomState(2.5f, 150f, 150f))
        view.touch(MotionEvent.ACTION_CANCEL, 1100, 100f, 100f)

        view.touch(MotionEvent.ACTION_DOWN, 2000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 2020, 200f, 100f)
        view.touch(MotionEvent.ACTION_UP, 2040, 200f, 100f)

        assertThat(view.state).isEqualTo(ZoomState(2.5f, 250f, 150f))
    }

    /**
     * The same, dragged down: with quick scale on, `ScaleGestureDetector.mAnchoredScaleMode` (reset
     * only by UP or CANCEL) turns a vertical drag into a zoom, so the scale is asserted as well.
     */
    @Test
    fun aCancelDuringADoubleTapDoesNotTurnTheNextDragIntoAZoom() {
        val view = viewWith(2000, 2000)

        view.doubleTap(1000, finish = false)
        view.touch(MotionEvent.ACTION_CANCEL, 1100, 100f, 100f)

        view.touch(MotionEvent.ACTION_DOWN, 2000, 100f, 100f)
        view.touch(MotionEvent.ACTION_MOVE, 2020, 100f, 220f)
        view.touch(MotionEvent.ACTION_UP, 2040, 100f, 220f)

        assertThat(view.state).isEqualTo(ZoomState(2.5f, 150f, 270f))
    }

    /**
     * Lifting one of two fingers must not make the image jump. The re-basing is GestureDetector's,
     * so this is an integration assertion against the real detector.
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

    /** Inside a scrolling container a pan must win against the parent. */
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

    /** The flags accessibility services read are set in the constructor, before any listener. */
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
        idleMainLooperFor(Duration.ofMillis(1000))

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
        // onSingleTapConfirmed only fires once the double-tap window has passed.
        idleMainLooperFor(Duration.ofMillis(500))

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

    /** A resize keeps the (fit-relative) zoom factor and re-clamps the pan into the new bounds. */
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
     * The zoom survives a rotation of a dialog whose image loads asynchronously: it is restored
     * before the image exists and applied when it arrives. The real hierarchy save/restore also
     * enforces that super is called on both sides.
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
     * And a second rotation while the image is still loading: what is saved then is the pending
     * restore, not the (not yet applied) current state.
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
     * Saved state is input from another process or build: an out-of-range value lands inside the
     * range instead of throwing out of `restoreHierarchyState`.
     */
    @Test
    fun aSavedZoomOutsideWhatTheStateCanHoldIsCoercedRatherThanThrown() {
        val view = ZoomImageView(context).apply { id = SAVED_ID }

        view.restoreHierarchyState(savedStateWithScale(900f))
        view.layout(0, 0, 400, 400)
        view.setImageBitmap(Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888))

        assertThat(view.state.scale).isEqualTo(5f) // this image's ceiling, applied on the way in
    }

    /** The ceiling is per image and can drop between releases; a stored zoom is coerced to it. */
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

    /** A real save with the private "scale" key overwritten. */
    private fun savedStateWithScale(scale: Float): SparseArray<Parcelable> =
        savedStateOf(viewWith(200, 200)).also { (it.get(SAVED_ID) as Bundle).putFloat("scale", scale) }

    /**
     * Somebody else's Bundle under our id: it has no KEY_SUPER, so without the marker check super
     * would be restored from null. What remains is the platform's own diagnostic for the collision.
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
     * [ZoomState.scale] survives a rotation exactly as a ratio to the fit, but the fit's limiting
     * axis changes, so the matrix scale goes from 2.0 to 2.67. The visible fraction along the
     * limiting axis stays 1 / scale.
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
     * The offset is kept in pixels, not in proportion: a pan halfway to the edge ends up three
     * quarters of the way after the pannable range shrinks from 200 px to 133. Normalising it would
     * change the saved format, since restore parks the state before any geometry is known.
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
