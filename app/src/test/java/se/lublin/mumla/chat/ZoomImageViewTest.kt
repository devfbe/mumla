package se.lublin.mumla.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.util.SparseArray
import android.view.AbsSavedState
import android.view.InputDevice
import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
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
     * Two properties of the real ScaleGestureDetector the numbers depend on, both measured here:
     * the gesture only begins once the span has changed by more than the span slop (2 x 16 px), and
     * the first onScale after onScaleBegin reports a factor of exactly 1 and re-bases the span.
     * So 180 -> 260 is the no-op that arms it and 260 -> 360 is the zoom.
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

        assertThat(view.state.scale).isEqualTo(2.5f)
        assertThat(view.state.tx).isGreaterThan(0f) // zoomed towards the upper left corner

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
     * Deliberately *not* named "the cancel is handled", because it is not: measured, swallowing
     * ACTION_CANCEL in onTouchEvent so that it never reaches either detector leaves this whole
     * class green. There is no observable that tells the two apart, because the view keeps no
     * gesture-scoped state to unwind and GestureDetector re-bases its focus on the next ACTION_DOWN
     * anyway. That absence is the design; this test pins its consequence, which is what can be
     * broken -- by anyone who later adds an anchor field and forgets to clear it.
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

    @Test
    fun theZoomIsRestoredImmediatelyWhenTheImageIsAlreadyThere() {
        val saved = savedStateOf(viewWith(200, 200).apply { zoomBy(2f, 0f, 0f) })

        val after = viewWith(200, 200).apply { id = SAVED_ID }
        after.restoreHierarchyState(saved)

        assertThat(after.state).isEqualTo(ZoomState(2f, 200f, 200f))
        assertThat(values(after)[Matrix.MSCALE_X]).isEqualTo(4f)
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

    private fun savedStateOf(view: ZoomImageView): SparseArray<Parcelable> {
        view.id = SAVED_ID
        return SparseArray<Parcelable>().also { view.saveHierarchyState(it) }
    }

    private companion object {
        const val SAVED_ID = 42
    }
}
