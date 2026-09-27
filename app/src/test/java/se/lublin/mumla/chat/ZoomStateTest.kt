package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure JVM; [ZoomState.toMatrix] needs android.graphics and is covered in [ZoomImageViewTest]. */
class ZoomStateTest {
    private fun assertRejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

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

    /** The mirror image of [clampLimitsPanToTheImageEdges]. */
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
     * Zooming an already panned state carries the offset along with the scale (`tx * k`), or the
     * image slides out from under the fingers during a pinch. Focus on the centre, so the offset is
     * the only thing the numbers can come from.
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
     * A panorama: the fit is decided by width, and there is nothing to pan vertically. Powers of
     * two, so the expected numbers are exact in binary32.
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
     * A zero-size view (before the first layout) is refused loudly instead of returning 0 or
     * Infinity, which makes the caller's "not measured yet" check observable.
     */
    @Test
    fun aViewWithoutAMeasuredSizeIsRejected() {
        assertRejected { ZoomState.fitScale(0f, 400f, 200f, 100f) }
        assertRejected { ZoomState.fitScale(400f, 0f, 200f, 100f) }
    }

    /** A drawable without an intrinsic size (a ColorDrawable reports -1) must not divide by zero. */
    @Test
    fun anImageWithoutASizeIsRejected() {
        assertRejected { ZoomState.fitScale(400f, 400f, 0f, 100f) }
        assertRejected { ZoomState.fitScale(400f, 400f, 200f, -1f) }
    }

    /**
     * The constructor's bound is a sanity bound (the ceiling is [ZoomState.maxScale]'s business):
     * zero, negative and NaN scales fail it, so they never poison the view's matrix.
     */
    @Test
    fun aScaleOutsideTheSanityBoundCannotBeConstructed() {
        assertRejected { ZoomState(scale = 0f) }
        assertRejected { ZoomState(scale = 101f) }
        assertRejected { ZoomState(scale = Float.NaN) }
        assertRejected { ZoomState().scaledBy(Float.NaN, 0f, 0f, 400f, 400f, 200f, 100f) }
    }

    /**
     * The budget: zoom until one source pixel covers one screen pixel (1600 px of source into a
     * 400 px view is a fit of 0.25, four zooms' worth of pixels). An image the fit already had to
     * enlarge (every screen-sized decode) has a budget below 1 but still zooms twice; a very large
     * source stops at the global ceiling, since the decoder dropped those pixels. The ceiling
     * follows the limiting axis, exactly as the fit does.
     */
    @Test
    fun theCeilingIsOneSourcePixelPerScreenPixelWithinBounds() {
        val cases = listOf(
            floatArrayOf(400f, 400f, 1600f, 1600f) to 4f,
            floatArrayOf(400f, 400f, 1200f, 1200f) to 3f,
            floatArrayOf(400f, 400f, 400f, 400f) to 2f, // budget exactly 1
            floatArrayOf(400f, 400f, 10f, 10f) to 2f, // budget 0.025
            floatArrayOf(400f, 400f, 8000f, 8000f) to 5f, // budget 20
            // Fit 0.125 by width, budget 8, capped at 5: the height is irrelevant.
            floatArrayOf(512f, 512f, 4096f, 1f) to 5f,
        )
        for ((size, ceiling) in cases) {
            assertWithMessage(size.contentToString())
                .that(ZoomState.maxScale(size[0], size[1], size[2], size[3])).isEqualTo(ceiling)
        }
    }

    /**
     * [ZoomState.clamped] pulls a zoom above the ceiling down too: a state can arrive without a
     * gesture (restored from a release with a higher ceiling, or carried into a different image).
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
        assertRejected { ZoomState(tx = Float.NaN) }
        assertRejected { ZoomState(ty = Float.POSITIVE_INFINITY) }
    }
}
