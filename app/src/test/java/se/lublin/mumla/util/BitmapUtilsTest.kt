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

    /** Touching one bound is still "within bounds": no copy, no rescale. */
    @Test
    fun imageTouchingOnlyOneBoundIsReturnedUnchanged() {
        val edge = bitmap(240, 100)
        assertThat(BitmapUtils.resizeKeepingAspect(edge, 240, 240)).isSameInstanceAs(edge)
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
