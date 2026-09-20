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
        val zoomed = ZoomState().scaledBy(2f, 0f, 0f, 400f, 400f, 200f, 100f)
        assertThat(zoomed.scale).isEqualTo(2f)
        assertThat(zoomed.tx).isEqualTo(200f)
        assertThat(zoomed.ty).isEqualTo(200f)
    }

    @Test
    fun zoomingAroundTheCenterDoesNotPan() {
        val zoomed = ZoomState().scaledBy(3f, 200f, 200f, 400f, 400f, 200f, 100f)
        assertThat(zoomed.tx).isEqualTo(0f)
        assertThat(zoomed.ty).isEqualTo(0f)
    }

    /** An image with pixels to spare: 1600 wide into 400 is a budget of 4, and 4 is the cap. */
    @Test
    fun zoomIsCappedAtTheCeilingThisImageEarns() {
        val zoomed = ZoomState(scale = 3f).scaledBy(2f, 200f, 200f, 400f, 400f, 1600f, 1600f)
        assertThat(zoomed.scale).isEqualTo(4f)
    }

    @Test
    fun zoomingOutStopsAtTheFitScale() {
        val zoomed = ZoomState(scale = 1f).scaledBy(0.5f, 0f, 0f, 400f, 400f, 200f, 100f)
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
     * The mirror image of [clampLimitsPanToTheImageEdges].
     *
     * Not, as an earlier round claimed, because the mutation `offset.coerceAtMost(slack)` -- half of
     * the pan clamp deleted -- would otherwise survive: measured, two tests kill it, and one of them
     * is the plan's own `fitsTheImageCenteredAndAppliesZoomAndPan`. What is true is the weaker
     * statement, that the plan's **ZoomStateTest on its own** leaves the opposite direction unpinned,
     * which leaves the arithmetic depending on a view-level test to catch a pure-arithmetic bug.
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

    /**
     * The `tx * k` half of the focus arithmetic: zooming an *already panned* state has to carry the
     * existing offset along with the scale, or the image slides out from under the fingers -- a real
     * pinch is many small `onScale` calls and every one after the first starts from `tx != 0`.
     *
     * Nothing else in this file pins it. Every other zoom test starts from `ZoomState()` (`tx = 0`,
     * where `tx * k == tx`) or uses `factor = 1` (where `k == 1`, same thing), and the one view-level
     * test that would reach it asserts *after* [clamped] has pulled both the correct and the mutated
     * value to the same 0. Focus on the centre here, so the `(focusX - viewWidth / 2)` term is 0 and
     * the offset is the only thing the numbers can come from.
     */
    @Test
    fun zoomingAnAlreadyPannedStateScalesTheOffsetWithIt() {
        val zoomed =
            ZoomState(scale = 2f, tx = 100f, ty = -50f).scaledBy(2f, 200f, 200f, 400f, 400f, 1600f, 1600f)
        assertThat(zoomed.scale).isEqualTo(4f)
        assertThat(zoomed.tx).isEqualTo(200f)
        assertThat(zoomed.ty).isEqualTo(-100f)
    }

    /** Clamping is the view's business; [ZoomState.scaledBy] deliberately does not do it. */
    @Test
    fun scalingDoesNotClampTheOffsets() {
        val zoomed =
            ZoomState(scale = 2f, tx = 1000f, ty = -1000f).scaledBy(1f, 200f, 200f, 400f, 400f, 200f, 100f)
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
        val zoomed = ZoomState().scaledBy(2f, -100f, 500f, 400f, 400f, 200f, 100f)
        assertThat(zoomed.tx).isEqualTo(300f)
        assertThat(zoomed.ty).isEqualTo(-300f)
    }

    /** Zooming by 1 anywhere is a no-op, at both ends of the scale range. */
    @Test
    fun zoomingByOneChangesNothingAtEitherEndOfTheRange() {
        assertThat(ZoomState(scale = ZoomState.MIN_SCALE).scaledBy(1f, 17f, 23f, 400f, 400f, 8000f, 8000f))
            .isEqualTo(ZoomState(scale = ZoomState.MIN_SCALE))
        assertThat(ZoomState(scale = ZoomState.MAX_CEILING, tx = 7f).scaledBy(1f, 17f, 23f, 400f, 400f, 8000f, 8000f))
            .isEqualTo(ZoomState(scale = ZoomState.MAX_CEILING, tx = 7f))
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
     * The constructor's bound is a sanity bound, not the zoom ceiling -- the ceiling is per image
     * and is [ZoomState.maxScale]'s business. What is left here catches arithmetic that has gone
     * wrong: a zero or negative scale, and NaN, which fails the same check because no comparison
     * with NaN is true and so can never poison the view's matrix for the rest of its life.
     */
    @Test
    fun aScaleOutsideTheSanityBoundCannotBeConstructed() {
        assertThrows(IllegalArgumentException::class.java) { ZoomState(scale = 0f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState(scale = 101f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState(scale = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            ZoomState().scaledBy(Float.NaN, 0f, 0f, 400f, 400f, 200f, 100f)
        }
    }

    // --- the zoom ceiling, which is a property of the image and not of the state ---

    /**
     * The budget: zoom until one source pixel covers one screen pixel. 1600 px of source into a
     * 400 px view is a fit of 0.25, so there are four zooms' worth of pixels in the bitmap.
     */
    @Test
    fun theCeilingIsOneSourcePixelPerScreenPixel() {
        assertThat(ZoomState.maxScale(400f, 400f, 1600f, 1600f)).isEqualTo(4f)
        assertThat(ZoomState.maxScale(400f, 400f, 1200f, 1200f)).isEqualTo(3f)
    }

    /**
     * An image the fit already had to enlarge has a budget below 1 -- that is every image the
     * viewer decodes, since `resizeKeepingAspect` never enlarges and the decode is screen-sized.
     * Refusing to zoom those at all would make a small picture impossible to look at.
     */
    @Test
    fun anImageWithNoPixelsToSpareStillZoomsTwice() {
        assertThat(ZoomState.maxScale(400f, 400f, 400f, 400f)).isEqualTo(2f) // budget exactly 1
        assertThat(ZoomState.maxScale(400f, 400f, 10f, 10f)).isEqualTo(2f) // budget 0.025
    }

    /** And a very large source does not get its full budget: the decoder dropped those pixels. */
    @Test
    fun aHugeImageStopsAtTheGlobalCeiling() {
        assertThat(ZoomState.maxScale(400f, 400f, 8000f, 8000f)).isEqualTo(5f) // budget 20
    }

    /** The ceiling follows the *limiting* axis, exactly as the fit does. */
    @Test
    fun theCeilingIsDecidedByTheSameAxisAsTheFit() {
        // 4096x1 into 512x512: fit 0.125 by width, budget 8, capped at 5 -- the height is irrelevant.
        assertThat(ZoomState.maxScale(512f, 512f, 4096f, 1f)).isEqualTo(5f)
    }

    /**
     * A zoom above the ceiling is pulled down by [ZoomState.clamped] and not only by [scaledBy],
     * because a state can arrive at a view without having been through a gesture: restored from a
     * release whose ceiling was higher, or carried into a different image.
     */
    @Test
    fun clampPullsAZoomAboveTheCeilingBackDown() {
        val clamped = ZoomState(scale = 5f, tx = 0f, ty = 0f).clamped(400f, 400f, 400f, 400f)
        assertThat(clamped.scale).isEqualTo(2f)
    }

    /** And it leaves a zoom inside the ceiling alone, so the clamp cannot become a zoom-out. */
    @Test
    fun clampLeavesAZoomInsideTheCeilingAlone() {
        val clamped = ZoomState(scale = 3.5f, tx = 0f, ty = 0f).clamped(400f, 400f, 1600f, 1600f)
        assertThat(clamped.scale).isEqualTo(3.5f)
    }

    /** Same for the offsets: an infinite pan is a bug, not a position. */
    @Test
    fun aNonFiniteOffsetCannotBeConstructed() {
        assertThrows(IllegalArgumentException::class.java) { ZoomState(tx = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ZoomState(ty = Float.POSITIVE_INFINITY) }
    }
}
