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

    /**
     * By default Robolectric invents a 100x100 bitmap for data it cannot decode, which real
     * BitmapFactory never does. Every test in this class needs the realistic behaviour.
     */
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

        // The bitmap `decode` actually materialised, recovered from the result instead of
        // recomputed from constants here: createScaledBitmap records the instance it scaled from,
        // so this is the real peak of the decoding path. It is 48 MB the moment the sampling stops
        // working, which is the entire reason this class exists.
        val intermediate = shadowOf(bitmap).createdFromBitmap!!
        assertThat(intermediate.width).isEqualTo(4000 / sample)
        assertThat(intermediate.height).isEqualTo(3000 / sample)
        val unsampledBytes = 4000L * 3000L * 4L  // 48_000_000 B at 4 bytes per ARGB_8888 pixel
        assertThat(intermediate.byteCount.toLong() * 200).isLessThan(unsampledBytes)

        // 3000/16 is 187.5 and this decoder floors it to 187, which puts the fitted height at
        // 240 / (250/187) = 179.5 -> 179. The flooring is Robolectric's arithmetic
        // (`point.y /= inSampleSize`), not a platform promise: a device may hand back 188 for the
        // same request and then fit to 180. These two numbers pin what the JVM decoder does here;
        // they are not a claim about what Android returns. A power-of-two sampled image only
        // approximates its aspect ratio either way.
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
     * A body cut short is a real case: HttpImageFetcher accepts a response that stops before the
     * announced Content-Length. Decoding must report "not an image", not throw.
     *
     * Measured: for every prefix below the *bounds* pass already fails, so what this pins is the
     * first of the two decode calls, never the second — with the catch around the second decode
     * removed entirely, this test still passed. That holds for the prefixes that keep the header
     * intact and cut into the pixel data too, because this test's decoder (ImageIO) reads the
     * metadata across the whole stream. A cut that survives the bounds pass and then fails on the
     * pixel pass is therefore not producible with real bytes here; that branch is covered by
     * [aThrowingSampledPassIsReportedAsNotAnImage] instead.
     */
    @Test
    fun aTruncatedImageDecodesToNull() {
        val png = TestImages.png(1000, 500)
        for (prefix in listOf(8, 24, 33, 40, 100, png.size - 20, png.size - 12, png.size - 8)) {
            assertThat(BoundedBitmapDecoder.decode(png.copyOfRange(0, prefix), 240, 240)).isNull()
        }
    }

    /**
     * The sampled pass throwing is defence in depth rather than an observed case, so it takes a
     * decoder double to reach: no byte sequence gets past the bounds pass and then throws (see
     * [aTruncatedImageDecodesToNull]). A throwing decoder is still "not an image", not a crash.
     */
    @Test
    @Config(shadows = [ArmedBitmapFactory::class])
    fun aThrowingSampledPassIsReportedAsNotAnImage() {
        ArmedBitmapFactory.armCall(2, IllegalStateException("decoder gave up on the pixel data"))
        assertThat(BoundedBitmapDecoder.decode(TestImages.png(1000, 500), 240, 240)).isNull()
        assertThat(ArmedBitmapFactory.calls).isEqualTo(2)
    }

    /**
     * The OOM policy, which until now existed only as prose in the KDoc: an [Error] is not an
     * "undecodable image", it is the heap being gone, and it must reach the caller. Swallowing it
     * would report a memory exhaustion as "not an image" and let the app run on a wrecked heap.
     *
     * The error is constructed rather than provoked on purpose. Really exhausting the heap in a
     * unit test is neither reproducible nor survivable for the rest of the suite, and it would not
     * test anything extra: both catches branch on the *type* of what was thrown, so a constructed
     * instance takes the exact path an allocator-thrown one takes. A second, non-OOM [Error] is
     * armed as well so the pinned policy is "no Error is caught" rather than "OOM is special-cased".
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

/**
 * A [BitmapFactory] whose decode calls can be armed to throw, installed per test via `@Config`.
 *
 * Robolectric's own shadow cannot be driven into throwing and real bytes cannot reach the second
 * decode call in a throwing state, so this is the only way to cover what `decode` promises about
 * throwing decoders. Calls that are not the armed one report a plausible header on the options and
 * return nothing: `decode` reads the size off the options and discards the bitmap of the bounds
 * pass, so that is enough to get from the first call to the second.
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
