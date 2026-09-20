package se.lublin.mumla.util

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitmapUtilsTest {
    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun wideImageIsLimitedByWidth() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(1000, 500), 240, 240)
        assertThat(result.width).isEqualTo(240)
        assertThat(result.height).isEqualTo(120)
    }

    @Test
    fun tallImageIsLimitedByHeight() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(500, 1000), 240, 240)
        assertThat(result.width).isEqualTo(120)
        assertThat(result.height).isEqualTo(240)
    }

    @Test
    fun smallImageIsReturnedUnchanged() {
        val small = bitmap(100, 50)
        assertThat(BitmapUtils.resizeKeepingAspect(small, 240, 240)).isSameInstanceAs(small)
    }

    @Test
    fun imageExactlyAtBoundsIsReturnedUnchanged() {
        val exact = bitmap(240, 240)
        assertThat(BitmapUtils.resizeKeepingAspect(exact, 240, 240)).isSameInstanceAs(exact)
    }

    @Test
    fun extremeAspectRatioKeepsAtLeastOnePixelOnTheShortAxis() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(1000, 1), 240, 240)
        assertThat(result.width).isEqualTo(240)
        assertThat(result.height).isEqualTo(1)
    }

    /** The mirrored branch of the 1 px floor: the narrow axis is the width. */
    @Test
    fun extremeAspectRatioKeepsAtLeastOnePixelOnTheNarrowWidth() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(1, 1000), 240, 240)
        assertThat(result.width).isEqualTo(1)
        assertThat(result.height).isEqualTo(240)
    }

    /**
     * Touching one bound is still "within bounds": no copy, no rescale. The Java version's `<`
     * did not merely copy this image, it handed back a rescaled 240x99 — one row short of the
     * original for an image that was already inside both bounds.
     */
    @Test
    fun imageTouchingOnlyOneBoundIsReturnedUnchanged() {
        val edge = bitmap(240, 100)
        assertThat(BitmapUtils.resizeKeepingAspect(edge, 240, 240)).isSameInstanceAs(edge)
    }

    /**
     * The other side of that boundary: one pixel over it must be scaled. Without this, moving the
     * comparison one step further out (`<= maxWidth + 1`) goes unnoticed, and images would be let
     * through over the bound the caller asked for.
     */
    @Test
    fun anImageOnePixelOverTheBoundIsScaled() {
        val tooWide = bitmap(241, 240)
        val scaledWide = BitmapUtils.resizeKeepingAspect(tooWide, 240, 240)
        assertThat(scaledWide).isNotSameInstanceAs(tooWide)
        assertThat(scaledWide.width).isEqualTo(240)
        assertThat(scaledWide.height).isEqualTo(239)

        val tooTall = bitmap(240, 241)
        val scaledTall = BitmapUtils.resizeKeepingAspect(tooTall, 240, 240)
        assertThat(scaledTall).isNotSameInstanceAs(tooTall)
        assertThat(scaledTall.width).isEqualTo(239)
        assertThat(scaledTall.height).isEqualTo(240)
    }

    /**
     * The 1 px floor at the real call site, ChannelChatFragment's outgoing image preview, which
     * passes 600 x 400. The Java version crashed in createScaledBitmap for *every* image wider
     * than 600 times its height — not just the pathological 1000x1 — because the fitted short side
     * truncated to zero. 601x1 is the first width that does it at a height of one; 600x1 is still
     * inside the bounds and is handed back untouched.
     */
    @Test
    fun theSendPathBoundsKeepEveryFlatImageAtOnePixel() {
        for ((w, h) in listOf(1000 to 1, 4000 to 6, 601 to 1)) {
            val result = BitmapUtils.resizeKeepingAspect(bitmap(w, h), 600, 400)
            assertThat(result.width).isEqualTo(600)
            assertThat(result.height).isEqualTo(1)
        }
        val inside = bitmap(600, 1)
        assertThat(BitmapUtils.resizeKeepingAspect(inside, 600, 400)).isSameInstanceAs(inside)
    }

    /** The mirrored branch at the same call site: taller than 400 times its width. */
    @Test
    fun theSendPathBoundsKeepEveryNarrowImageAtOnePixel() {
        for ((w, h) in listOf(1 to 1000, 6 to 4000, 1 to 401)) {
            val result = BitmapUtils.resizeKeepingAspect(bitmap(w, h), 600, 400)
            assertThat(result.width).isEqualTo(1)
            assertThat(result.height).isEqualTo(400)
        }
    }

    @Test
    fun wideShortImageIsLimitedByWidth() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(1000, 100), 240, 240)
        assertThat(result.width).isEqualTo(240)
        assertThat(result.height).isEqualTo(24)
    }

    @Test
    fun tallNarrowImageIsLimitedByHeight() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(100, 1000), 240, 240)
        assertThat(result.width).isEqualTo(24)
        assertThat(result.height).isEqualTo(240)
    }

    @Test
    fun nonSquareBoundsAreBothRespected() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(2000, 1000), 600, 400)
        assertThat(result.width).isEqualTo(600)
        assertThat(result.height).isEqualTo(300)
    }

    /**
     * The computed side truncates, it does not round up: the result is guaranteed to sit strictly
     * inside the bound. 240 / (1000/333) = 79.92 px on both branches.
     */
    @Test
    fun fractionalScaleTruncatesRatherThanRoundsUp() {
        assertThat(BitmapUtils.resizeKeepingAspect(bitmap(1000, 333), 240, 240).height).isEqualTo(79)
        assertThat(BitmapUtils.resizeKeepingAspect(bitmap(333, 1000), 240, 240).width).isEqualTo(79)
    }

    /** Asymmetric bounds, height-limited branch: pins that the height bound drives this branch. */
    @Test
    fun nonSquareBoundsAreBothRespectedForTallImages() {
        val result = BitmapUtils.resizeKeepingAspect(bitmap(1000, 2000), 600, 400)
        assertThat(result.width).isEqualTo(200)
        assertThat(result.height).isEqualTo(400)
    }

    @Test
    fun zeroMaxWidthIsRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BitmapUtils.resizeKeepingAspect(bitmap(1000, 500), 0, 240)
        }
        assertThat(e).hasMessageThat().contains("maxWidth")
    }

    @Test
    fun negativeMaxHeightIsRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BitmapUtils.resizeKeepingAspect(bitmap(1000, 500), 240, -1)
        }
        assertThat(e).hasMessageThat().contains("maxHeight")
    }
}
