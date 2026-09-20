package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
class BoundedBitmapDecoderTest {

    /**
     * By default Robolectric invents a 100x100 bitmap for data it cannot decode, which real
     * BitmapFactory never does. Every test in this class needs the realistic behaviour.
     */
    @Before
    fun realisticDecoding() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @Test
    fun sampleSizeKeepsTheSampledImageAtOrAboveTheAspectFittedTarget() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(1000, 500, 240, 240)).isEqualTo(4)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(2000, 1000, 600, 400)).isEqualTo(2)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(300, 300, 240, 240)).isEqualTo(1)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(4000, 4000, 240, 240)).isEqualTo(16)
    }

    /**
     * Exactly twice the bound must still be sampled: the sampled result lands exactly on the target,
     * so the decode costs a quarter of the memory and no rescale follows. Stopping one step earlier
     * here would decode the full image and throw three quarters of it away afterwards.
     */
    @Test
    fun exactlyTwiceTheBoundIsStillSampled() {
        assertThat(BoundedBitmapDecoder.sampleSizeFor(480, 480, 240, 240)).isEqualTo(2)
        assertThat(BoundedBitmapDecoder.sampleSizeFor(479, 479, 240, 240)).isEqualTo(1)
    }

    @Test
    fun extremeAspectRatiosAreSampledDown() {
        // Bounded by width alone: 1000/240 -> 4, 12000/240 -> 32. A `&&` over both axes would
        // return 1 here and decode the full image (12000 x 200 x 4 bytes ~ 9.6 MiB).
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
        // Also for input that would otherwise short-circuit to null: the bound is checked first,
        // so a bad bound can never hide behind "not an image".
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
     * The whole point of the class. Robolectric records the decode options on the bitmap and keeps
     * that description across createScaledBitmap, so this is evidence about what the DECODER was
     * asked to do, not merely about the size of the result.
     */
    @Test
    fun aLargeImageIsSampledAtDecodeTimeAndNeverMaterialisedInFull() {
        val sample = BoundedBitmapDecoder.sampleSizeFor(4000, 3000, 240, 240)
        assertThat(sample).isEqualTo(16)

        val bitmap = BoundedBitmapDecoder.decode(TestImages.png(4000, 3000), 240, 240)!!
        assertThat(shadowOf(bitmap).description).contains("inSampleSize=$sample")
        // 3000 / 16 floors to 187, not 187.5, so the fitted height is 240 / (250/187) = 179.5 -> 179.
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(179)

        // ARGB_8888, 4 bytes per pixel.
        val unsampled = 4000L * 3000L * 4L                          // 48_000_000 bytes
        val intermediate = (4000L / sample) * (3000L / sample) * 4L  //    187_000 bytes
        assertThat(intermediate * 200).isLessThan(unsampled)
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
     * A body cut short is a real case: HttpImageFetcher accepts a response that stops before the
     * announced Content-Length. Decoding must report "not an image", not throw.
     */
    @Test
    fun aTruncatedImageDecodesToNull() {
        val png = TestImages.png(1000, 500)
        for (prefix in listOf(8, 24, 33, 40, 100, png.size - 20, png.size - 12, png.size - 8)) {
            assertThat(BoundedBitmapDecoder.decode(png.copyOfRange(0, prefix), 240, 240)).isNull()
        }
    }

    /**
     * Measured boundary of the case above: cutting into the trailing IEND chunk leaves all pixel
     * data intact, so this decodes normally. Pinned so that a later change to the truncation
     * handling cannot quietly turn a complete image into a failure.
     */
    @Test
    fun anImageMissingOnlyItsEndMarkerStillDecodes() {
        val png = TestImages.png(1000, 500)
        val bitmap = BoundedBitmapDecoder.decode(png.copyOfRange(0, png.size - 1), 240, 240)!!
        assertThat(bitmap.width).isEqualTo(240)
        assertThat(bitmap.height).isEqualTo(120)
    }
}
