package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure JVM test: not a Robolectric test on purpose, so the arithmetic of the zoom state is pinned
 * without any emulated graphics under it. [ZoomState.toMatrix] is the one method that needs
 * android.graphics and is therefore pinned in [ZoomImageViewTest].
 */
class ZoomStateTest {
    // View 400x400, image 200x100 -> fit scale 2, displayed 400x200.

    @Test
    fun fitScaleUsesTheLimitingAxis() {
        assertThat(ZoomState.fitScale(400f, 400f, 200f, 100f)).isEqualTo(2f)
        assertThat(ZoomState.fitScale(400f, 400f, 100f, 400f)).isEqualTo(1f)
    }

    @Test
    fun zoomingAroundTheTopLeftCornerKeepsThatCornerFixed() {
        val zoomed = ZoomState().scaledBy(2f, 0f, 0f, 400f, 400f)
        assertThat(zoomed.scale).isEqualTo(2f)
        assertThat(zoomed.tx).isEqualTo(200f)
        assertThat(zoomed.ty).isEqualTo(200f)
    }

    @Test
    fun zoomingAroundTheCenterDoesNotPan() {
        val zoomed = ZoomState().scaledBy(3f, 200f, 200f, 400f, 400f)
        assertThat(zoomed.tx).isEqualTo(0f)
        assertThat(zoomed.ty).isEqualTo(0f)
    }

    @Test
    fun zoomIsCappedAtMaxScale() {
        assertThat(ZoomState(scale = 4f).scaledBy(2f, 200f, 200f, 400f, 400f).scale).isEqualTo(5f)
    }

    @Test
    fun zoomingOutStopsAtTheFitScale() {
        val zoomed = ZoomState(scale = 1f).scaledBy(0.5f, 0f, 0f, 400f, 400f)
        assertThat(zoomed.scale).isEqualTo(1f)
        assertThat(zoomed.tx).isEqualTo(0f)
    }

    @Test
    fun panAccumulates() {
        val panned = ZoomState().pannedBy(10f, -5f).pannedBy(5f, 5f)
        assertThat(panned.tx).isEqualTo(15f)
        assertThat(panned.ty).isEqualTo(0f)
    }

    @Test
    fun clampCentersAxesSmallerThanTheView() {
        val clamped = ZoomState(scale = 1f, tx = 50f, ty = 50f).clamped(400f, 400f, 200f, 100f)
        assertThat(clamped.tx).isEqualTo(0f)
        assertThat(clamped.ty).isEqualTo(0f)
    }

    @Test
    fun clampLimitsPanToTheImageEdges() {
        // scale 2 -> displayed 800x400: horizontal slack +-200, vertical none.
        val clamped = ZoomState(scale = 2f, tx = 500f, ty = -30f).clamped(400f, 400f, 200f, 100f)
        assertThat(clamped.tx).isEqualTo(200f)
        assertThat(clamped.ty).isEqualTo(0f)
    }

    // --- the tests above are the plan's; the ones below close gaps it leaves ---

    /**
     * The mirror image of [clampLimitsPanToTheImageEdges]. Without it the mutation
     * `offset.coerceAtMost(slack)` -- half of the pan clamp deleted -- stays green.
     */
    @Test
    fun clampLimitsPanToTheOppositeEdgeToo() {
        val clamped = ZoomState(scale = 2f, tx = -500f, ty = 30f).clamped(400f, 400f, 200f, 100f)
        assertThat(clamped.tx).isEqualTo(-200f)
        assertThat(clamped.ty).isEqualTo(0f)
    }

    /** Pins the limit from the inside as well, so a mutation to `slack + 1` is caught. */
    @Test
    fun aPanExactlyOnTheLimitIsKept() {
        val onTheEdge = ZoomState(scale = 2f, tx = 200f, ty = 0f).clamped(400f, 400f, 200f, 100f)
        assertThat(onTheEdge.tx).isEqualTo(200f)
        val justOver = ZoomState(scale = 2f, tx = 200.5f, ty = 0f).clamped(400f, 400f, 200f, 100f)
        assertThat(justOver.tx).isEqualTo(200f)
    }

    /** Clamping is the view's business; [ZoomState.scaledBy] deliberately does not do it. */
    @Test
    fun scalingDoesNotClampTheOffsets() {
        val zoomed = ZoomState(scale = 2f, tx = 1000f, ty = -1000f).scaledBy(1f, 200f, 200f, 400f, 400f)
        assertThat(zoomed.tx).isEqualTo(1000f)
        assertThat(zoomed.ty).isEqualTo(-1000f)
    }

    /** An image smaller than the view is blown up to fit, exactly as ScaleType.FIT_CENTER does. */
    @Test
    fun anImageSmallerThanTheViewIsScaledUpToFit() {
        assertThat(ZoomState.fitScale(400f, 400f, 10f, 10f)).isEqualTo(40f)
        val clamped = ZoomState(scale = 1f, tx = 25f, ty = 25f).clamped(400f, 400f, 10f, 10f)
        assertThat(clamped).isEqualTo(ZoomState())
    }

    /**
     * A panorama: the fit is decided by width, and there is nothing to pan vertically, ever.
     * Powers of two throughout, so the expected numbers are exact in binary32 and the test pins
     * the rule rather than a rounding mode.
     */
    @Test
    fun anExtremelyWideImageIsFittedByWidthAndNeverPansVertically() {
        assertThat(ZoomState.fitScale(512f, 512f, 4096f, 1f)).isEqualTo(0.125f)
        val zoomedIn = ZoomState(scale = 4f, tx = 10_000f, ty = 10_000f).clamped(512f, 512f, 4096f, 1f)
        assertThat(zoomedIn.tx).isEqualTo(768f) // displayed 2048 wide, slack (2048-512)/2
        assertThat(zoomedIn.ty).isEqualTo(0f) // displayed 0.5 px tall: no slack at any scale
    }

    /** A focus point outside the view is not special-cased; the same linear rule holds. */
    @Test
    fun aFocusPointOutsideTheViewIsNotSpecialCased() {
        val zoomed = ZoomState().scaledBy(2f, -100f, 500f, 400f, 400f)
        assertThat(zoomed.tx).isEqualTo(300f)
        assertThat(zoomed.ty).isEqualTo(-300f)
    }

    /** Zooming by 1 anywhere is a no-op, at both ends of the scale range. */
    @Test
    fun zoomingByOneChangesNothingAtEitherEndOfTheRange() {
        assertThat(ZoomState(scale = ZoomState.MIN_SCALE).scaledBy(1f, 17f, 23f, 400f, 400f))
            .isEqualTo(ZoomState(scale = ZoomState.MIN_SCALE))
        assertThat(ZoomState(scale = ZoomState.MAX_SCALE, tx = 7f).scaledBy(1f, 17f, 23f, 400f, 400f))
            .isEqualTo(ZoomState(scale = ZoomState.MAX_SCALE, tx = 7f))
    }

    /**
     * A view of zero size is real: Task 5 already had to deal with one, and this class is asked for
     * a fit before the first layout pass. It refuses loudly instead of returning 0 or Infinity,
     * which is what makes the caller's "not measured yet" check in [ZoomImageView] observable.
     */
    @Test
    fun aViewWithoutAMeasuredSizeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ZoomState.fitScale(0f, 400f, 200f, 100f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState.fitScale(400f, 0f, 200f, 100f) }
    }

    /** A drawable without an intrinsic size (a ColorDrawable reports -1) must not divide by zero. */
    @Test
    fun anImageWithoutASizeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ZoomState.fitScale(400f, 400f, 0f, 100f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState.fitScale(400f, 400f, 200f, -1f) }
    }

    /**
     * There is no such thing as an out-of-range zoom state: the range is enforced where the value
     * is born, not at every place that reads it. NaN fails the same check (no comparison with NaN
     * is true), so a non-finite scale factor can never poison the view for the rest of its life.
     */
    @Test
    fun aScaleOutsideTheRangeCannotBeConstructed() {
        assertThrows(IllegalArgumentException::class.java) { ZoomState(scale = 0f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState(scale = 5.5f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState(scale = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState().scaledBy(Float.NaN, 0f, 0f, 400f, 400f) }
    }

    /** Same for the offsets: an infinite pan is a bug, not a position. */
    @Test
    fun aNonFiniteOffsetCannotBeConstructed() {
        assertThrows(IllegalArgumentException::class.java) { ZoomState(tx = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState(ty = Float.POSITIVE_INFINITY) }
    }
}
