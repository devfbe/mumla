package se.lublin.mumla.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
class BoundedBitmapDecoderTest {

    /** Robolectric otherwise invents a 100x100 bitmap for data it cannot decode. */
    @Before
    fun realisticDecoding() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        ArmedBitmapFactory.disarm()
    }

    @Test
    fun sampleSizeKeepsTheSampledImageAtOrAboveTheAspectFittedTarget() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(1000, 500, 240, 240)).isEqualTo(4)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(2000, 1000, 600, 400)).isEqualTo(2)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(300, 300, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(4000, 4000, 240, 240)).isEqualTo(16)
    }

    /** Exactly twice the bound is still sampled: the result lands on the target, no rescale. */
    @Test
    fun exactlyTwiceTheBoundIsStillSampled() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(480, 480, 240, 240)).isEqualTo(2)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(479, 479, 240, 240)).isEqualTo(1)
    }

    @Test
    fun extremeAspectRatiosAreSampledDown() {
        // Bounded by width alone: 1000/240 -> 4, 12000/240 -> 32. A `&&` over both axes would
        // return 1 here and decode the full image.
        assertThat(BoundedBitmapDecoder.sampleSizeFor(1000, 100, 240, 240)).isEqualTo(4)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(12000, 200, 240, 240)).isEqualTo(32)
    }

    @Test
    fun smallImagesAreNeverSampled() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(100, 40, 240, 240)).isEqualTo(1)
    }

    @Test
    fun degenerateDimensionsFromTheHeaderAreNotSampled() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(0, 500, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(500, 0, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(-1, -1, 240, 240)).isEqualTo(1)
    }

    /** With a negative bound the doubling loop would never terminate (`sample` overflows to 0). */
    @Test
    fun nonPositiveBoundsAreRejectedBySampleSizeFor() {
        assertThat(
            assertThrows(IllegalArgumentException::class.java) {
                BoundedBitmapDecoder.sampleSizeFor(1000, 500, 0, 240)
            }
        ).hasMessageThat().contains("maxWidth")
        assertThat(
            assertThrows(IllegalArgumentException::class.java) {
                BoundedBitmapDecoder.sampleSizeFor(1000, 500, 240, -3)
            }
        ).hasMessageThat().contains("maxHeight")
    }

    /** A bad bound is a programming error and must not be reported as "not an image". */
    @Test
    fun nonPositiveBoundsAreRejectedByDecode() {
        val png = TestImages.png(1000, 500)
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.decode(png, 0, 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.decode(png, 240, 0)
        }
        // Also for input that would otherwise short-circuit to null: the bound is checked first.
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.decode("definitely not an image".toByteArray(), -5, 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.decode(ByteArray(0), 240, -5)
        }
    }

    @Test
    fun decodesWithinBounds() {
        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(1000, 500), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(120)
    }

    @Test
    fun smallImagesAreNotUpscaled() {
        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(100, 40), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(100)
        assertThat(bitmap.height).isEqualTo(40)
    }

    @Test
    fun anImageExactlyOnTheBoundIsNeitherSampledNorCopied() {
        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(240, 240), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(240)
        assertThat(shadowOf(bitmap).description).doesNotContain("scaled to")
    }

    @Test
    fun extremeAspectRatioDecodesToAtLeastOnePixel() {
        // Sampled to 375 x 6, then fitted to the 240 px bound.
        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(12000, 200), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(3)
    }

    /** A single-pixel-high strip: neither the sampling nor the fit may collapse it to nothing. */
    @Test
    fun aOnePixelHighStripSurvivesBothStages() {
        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(10000, 1), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(1)
    }

    /**
     * Robolectric records the decode options on the bitmap and keeps them across
     * createScaledBitmap, so this is evidence about what the decoder was asked to do.
     */
    @Test
    fun aLargeImageIsSampledAtDecodeTimeAndNeverMaterialisedInFull() {
        val sample = BoundedBitmapDecoder.sampleSizeFor(4000, 3000, 240, 240)
        assertThat(sample).isEqualTo(16)

        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(4000, 3000), 240, 240)!!
        assertThat(shadowOf(bitmap).description).contains("inSampleSize=$sample")

        // The bitmap `decode` actually materialised (createScaledBitmap records its source), i.e.
        // the real peak of the decoding path.
        val intermediate = shadowOf(bitmap).createdFromBitmap!!
        assertThat(intermediate.width).isEqualTo(4000 / sample)
        assertThat(intermediate.height).isEqualTo(3000 / sample)
        val unsampledBytes = 4000L * 3000L * 4L  // 48_000_000 B at 4 bytes per ARGB_8888 pixel
        assertThat(intermediate.byteCount.toLong() * 200).isLessThan(unsampledBytes)

        // 3000/16 = 187.5 floors to 187 in Robolectric's decoder, so the fitted height is 179. A
        // device may return 188 and fit to 180; these numbers pin the JVM decoder only.
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(179)
        assertThat(bitmap.byteCount.toLong()).isEqualTo(240L * 179L * 4L)
    }

    @Test
    fun garbageDecodesToNull() {
        assertThat(BoundedBitmapDecoder.decode("definitely not an image".toByteArray(), 240, 240)).isNull()
    }

    @Test
    fun emptyBytesDecodeToNull() {
        assertThat(BoundedBitmapDecoder.decode(ByteArray(0), 240, 240)).isNull()
    }

    /**
     * A body cut short (HttpImageFetcher accepts a response shorter than Content-Length) decodes to
     * null rather than throwing. With ImageIO every such prefix already fails the bounds pass; the
     * sampled-pass branch is covered by [aThrowingSampledPassIsReportedAsNotAnImage].
     */
    @Test
    fun aTruncatedImageDecodesToNull() {
        val png = TestImages.png(1000, 500)
        for (prefix in listOf(8, 24, 33, 40, 100, png.size - 20, png.size - 12, png.size - 8)) {
            assertThat(BoundedBitmapDecoder.decode(png.copyOfRange(0, prefix), 240, 240)).isNull()
        }
    }

    /** The sampled pass throwing is only reachable with a decoder double; it is "not an image". */
    @Test
    @Config(shadows = [ArmedBitmapFactory::class])
    fun aThrowingSampledPassIsReportedAsNotAnImage() {
        ArmedBitmapFactory.armCall(2, IllegalStateException("decoder gave up on the pixel data"))
        assertThat(BoundedBitmapDecoder.decode(TestImages.png(1000, 500), 240, 240)).isNull()
        assertThat(ArmedBitmapFactory.calls).isEqualTo(2)
    }

    /**
     * An [Error] is not an undecodable image and must reach the caller. The error is constructed
     * rather than provoked; both catches branch on the type. A non-OOM [Error] is armed as well, so
     * the policy is "no Error is caught" rather than "OOM is special-cased".
     */
    @Test
    @Config(shadows = [ArmedBitmapFactory::class])
    fun anErrorFromTheBoundsPassReachesTheCaller() {
        val png = TestImages.png(1000, 500)
        ArmedBitmapFactory.armCall(1, OutOfMemoryError("failed to allocate a 48000012 byte allocation"))
        assertThat(
            assertThrows(OutOfMemoryError::class.java) { BoundedBitmapDecoder.decode(png, 240, 240) }
        ).hasMessageThat().contains("48000012")

        ArmedBitmapFactory.armCall(1, StackOverflowError())
        assertThrows(StackOverflowError::class.java) { BoundedBitmapDecoder.decode(png, 240, 240) }
    }

    /** The sampled pass is where the big allocation happens, so this is the realistic OOM site. */
    @Test
    @Config(shadows = [ArmedBitmapFactory::class])
    fun anErrorFromTheSampledPassReachesTheCaller() {
        val png = TestImages.png(1000, 500)
        ArmedBitmapFactory.armCall(2, OutOfMemoryError("failed to allocate a 12000012 byte allocation"))
        assertThat(
            assertThrows(OutOfMemoryError::class.java) { BoundedBitmapDecoder.decode(png, 240, 240) }
        ).hasMessageThat().contains("12000012")

        ArmedBitmapFactory.armCall(2, StackOverflowError())
        assertThrows(StackOverflowError::class.java) { BoundedBitmapDecoder.decode(png, 240, 240) }
    }

    /** Cutting into the trailing IEND chunk leaves the pixel data intact, so this still decodes. */
    @Test
    fun anImageMissingOnlyItsEndMarkerStillDecodes() {
        val png = TestImages.png(1000, 500)
        val bitmap = BoundedBitmapDecoder.decode(png.copyOfRange(0, png.size - 1), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(120)
    }

    @Test
    fun theAtMostSampleTakesTheHalvingTheExactFitDeclines() {
        // Just under twice the bound on both axes: the exact-fit sampler stops at 1.
        assertThat(BoundedBitmapDecoder.sampleSizeFor(479, 479, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(479, 479, 240, 240)).isEqualTo(2)
        // Exactly on twice: both agree, because one halving lands exactly on the bound.
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(480, 480, 240, 240)).isEqualTo(2)
        // Already inside the bound: nothing to sample either way.
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(100, 40, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(240, 240, 240, 240)).isEqualTo(1)
        // Bounded by one axis alone, same as sampleSizeFor: 12000/240 -> 32 there, 64 here.
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(12000, 200, 240, 240)).isEqualTo(64)
    }

    @Test
    fun theAtMostSampleKeepsTheDegenerateAndRejectingCornersOfTheOtherOne() {
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(0, 500, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(500, 0, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(-1, -1, 240, 240)).isEqualTo(1)
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.sampleSizeAtMost(1000, 500, 0, 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.sampleSizeAtMost(1000, 500, 240, -3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.decodeAtMost(TestImages.png(1000, 500), 0, 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedBitmapDecoder.decodeAtMost(ByteArray(0), 240, -5)
        }
    }

    @Test
    fun theAtMostDecodeReportsUndecodableDataTheSameWay() {
        assertThat(BoundedBitmapDecoder.decodeAtMost("definitely not an image".toByteArray(), 240, 240)).isNull()
        assertThat(BoundedBitmapDecoder.decodeAtMost(ByteArray(0), 240, 240)).isNull()
    }

    @Test
    fun theAtMostDecodeNeverExceedsTheBoundAndNeverUpscales() {
        val big = BoundedBitmapDecoder.decodeAtMost(TestImages.png(479, 479), 240, 240)!!
        assertThat(big.width).isAtMost(240)
        assertThat(big.height).isAtMost(240)
        val small = BoundedBitmapDecoder.decodeAtMost(TestImages.png(100, 40), 240, 240)!!
        assertThat(small.width).isEqualTo(100)
        assertThat(small.height).isEqualTo(40)
    }

    /**
     * Peak bitmaps held by each path, from the shadow's `createdFromBitmap` record (a heap delta is
     * useless because Robolectric does not implement `inJustDecodeBounds`). The exact-fit path must
     * show a chain of two and the at-most path one, which also validates the instrument. Sizes are
     * a tenth of the viewer's real 2160 x 4680 bound per axis.
     */
    @Test
    fun theFullscreenDecodeHoldsOneBitmapWhereTheExactFitHoldsTwo() {
        val png = TestImages.png(431, 935) // just under twice 216 x 468 on the limiting axis

        val exact = BoundedBitmapDecoder.decode(png, 216, 468)!!
        val intermediate = shadowOf(exact).createdFromBitmap
        assertThat(intermediate).isNotNull() // instrument check: the exact fit really does copy
        val exactPeak = exact.byteCount.toLong() + intermediate!!.byteCount.toLong()

        val atMost = BoundedBitmapDecoder.decodeAtMost(png, 216, 468)!!
        assertThat(shadowOf(atMost).createdFromBitmap).isNull() // one allocation, nothing to add
        val atMostPeak = atMost.byteCount.toLong()

        assertThat(intermediate.width).isEqualTo(431) // the full image, materialised
        assertThat(exactPeak).isEqualTo(431L * 935 * 4 + exact.width.toLong() * exact.height * 4)
        assertThat(atMostPeak).isEqualTo(215L * 467 * 4)
        assertThat(atMostPeak * 5).isLessThan(exactPeak)
    }

    /**
     * The same worst case at the viewer's real size, sample sizes only: the exact fit peaks at
     * 202.1 MB, one halving gives a single 40.4 MB bitmap.
     */
    @Test
    fun atTwiceA1080x2340ScreenTheHalvingIsWhatSeparates202MBFrom40MB() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(4319, 9359, 2160, 4680)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeAtMost(4319, 9359, 2160, 4680)).isEqualTo(2)
    }

}

/**
 * A [BitmapFactory] whose decode calls can be armed to throw, installed per test via `@Config`.
 * Calls that are not the armed one report a plausible header on the options and return nothing,
 * which is enough for `decode` to get from the bounds pass to the sampled pass.
 */
@Implements(BitmapFactory::class)
class ArmedBitmapFactory {
    companion object {
        /** 1-based index of the `decodeByteArray` call that throws; 0 arms nothing. */
        private var failingCall = 0
        private var failure: Throwable? = null

        /** How many times `decodeByteArray` has been entered since the last arming. */
        var calls = 0
            private set

        fun armCall(call: Int, failure: Throwable) {
            this.failingCall = call
            this.failure = failure
            calls = 0
        }

        fun disarm() = armCall(0, RuntimeException("never thrown"))

        @JvmStatic
        @Implementation
        fun decodeByteArray(
            data: ByteArray?,
            offset: Int,
            length: Int,
            opts: BitmapFactory.Options?,
        ): Bitmap? {
            calls++
            if (calls == failingCall) throw failure!!
            opts?.outWidth = 1000
            opts?.outHeight = 500
            return null
        }
    }
}
