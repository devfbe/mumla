package se.lublin.mumla.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import se.lublin.mumla.util.BitmapUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native graphics for the whole class, deliberately, and it is not a convenience.
 *
 * Under Robolectric's legacy graphics `ShadowImageDecoder` reads only width, height and MIME type
 * out of the header, then decodes the whole image with `BitmapFactory` and calls
 * `Bitmap.createScaledBitmap`. That shadow therefore
 *  - never applies an EXIF orientation, so every assertion about orientation would pass whatever
 *    this class does, including applying the rotation a second time;
 *  - never samples, so no size here would mean what it says about memory;
 *  - dies with a `NullPointerException` (`bitmap.getWidth()` on a null decode) instead of the
 *    `DecodeException` the platform throws, which would drag a catch for it into production code.
 * `@GraphicsMode(NATIVE)` runs the real Skia decoder, where all three are the platform's behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutgoingImagePreparerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val preparer = OutgoingImagePreparer(
        context,
        ioDispatcher = Dispatchers.Unconfined,
        decodeDispatcher = Dispatchers.Unconfined,
    )
    private val pools = mutableListOf<ExecutorService>()

    @After
    fun shutDownPools() = pools.forEach { it.shutdownNow() }

    private fun uri(path: String) = Uri.parse("content://media/external/images/$path")

    private fun serve(uri: Uri, stream: () -> InputStream): AtomicInteger {
        val opens = AtomicInteger()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            opens.incrementAndGet()
            stream()
        }
        return opens
    }

    private fun countingDispatcher(name: String): Pair<CoroutineDispatcher, AtomicInteger> {
        val runs = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, name) }
        pools += pool
        val counting = java.util.concurrent.Executor { command ->
            pool.execute {
                runs.incrementAndGet()
                command.run()
            }
        }
        return counting.asCoroutineDispatcher() to runs
    }

    // ---- the bounds the send path is built around -------------------------------------------

    @Test
    fun largeImagesAreBoundedToTheOutgoingSize() {
        val bitmap = preparer.decode(TestImages.png(1200, 800))!!
        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(400)
    }

    @Test
    fun aPhotographIsBoundedTheSameWayAPngIs() {
        val bitmap = preparer.decode(TestImages.jpeg(1200, 800))!!
        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(400)
    }

    /** The limiting axis is the height here, which is the other arm of the fit. */
    @Test
    fun aTallImageIsBoundedByItsHeight() {
        val bitmap = preparer.decode(TestImages.jpeg(800, 1200))!!
        assertThat(bitmap.height).isEqualTo(400)
        assertThat(bitmap.width).isEqualTo(266)
    }

    @Test
    fun smallImagesAreNotUpscaled() {
        val bitmap = preparer.decode(TestImages.png(100, 50))!!
        assertThat(bitmap.width).isEqualTo(100)
        assertThat(bitmap.height).isEqualTo(50)
    }

    /**
     * An image on exactly the bound is already within it. 600x400 must come back untouched rather
     * than make a round trip through the decoder's scaler for a pixel-identical result.
     */
    @Test
    fun anImageExactlyOnTheBoundIsAlreadyWithinIt() {
        val bitmap = preparer.decode(TestImages.png(600, 400))!!
        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(400)
    }

    /**
     * 3000:1 fitted into 600x400 is 600x0.2 pixels. The floor is what keeps that from being a
     * zero-sized decode; the old send path crashed here, one bound later, with
     * "width and height must be > 0".
     */
    @Test
    fun anExtremeAspectRatioKeepsAtLeastOnePixelOnTheShortAxis() {
        val bitmap = preparer.decode(TestImages.png(3000, 1))!!
        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(1)

        val tall = preparer.decode(TestImages.png(1, 3000))!!
        assertThat(tall.width).isEqualTo(1)
        assertThat(tall.height).isEqualTo(400)
    }

    /**
     * The fit is the one [se.lublin.mumla.util.BitmapUtils.resizeKeepingAspect] computes — it is
     * moved into the decode rather than changed, so the bitmap is allocated at its final size
     * instead of being allocated once and copied down.
     */
    @Test
    fun theTargetSizeIsTheSameFitBitmapUtilsWouldHaveProduced() {
        val sizes = listOf(
            1200 to 800, 800 to 1200, 100 to 50, 600 to 400, 600 to 399, 599 to 400,
            600 to 31, 53 to 400, 3000 to 1, 1 to 3000, 4000 to 3000, 601 to 400, 600 to 401,
        )
        for ((width, height) in sizes) {
            val bounded = OutgoingImagePreparer.boundedSize(width, height, 600, 400)
            val resized = BitmapUtils.resizeKeepingAspect(
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                600,
                400,
            )
            assertThat("${width}x$height -> ${bounded.width}x${bounded.height}")
                .isEqualTo("${width}x$height -> ${resized.width}x${resized.height}")
        }
    }

    /**
     * An image touching exactly one bound is inside both of them and must come back untouched.
     * Falling through to the general fit instead looks harmless — the arithmetic aims at the same
     * size — but it runs the ratio through a float divide, and that is where Task 5 found the old
     * `<` bound handing back a 240x99 copy of a 240x100 image.
     *
     * The sizes here are not arbitrary and 600x399 is not enough. Enumerated for these bounds, a
     * strict `width <` costs a pixel for **16** of the 400 possible heights and a strict `height <`
     * for **30** of the 600 possible widths; the rest come back identical either way. 600x31 and
     * 53x400 are the smallest member of each of those two sets, so each of the two clauses has a
     * fixture that actually is its corner rather than one that merely looks like it.
     */
    @Test
    fun anImageTouchingOnlyOneBoundIsLeftAlone() {
        for ((width, height) in listOf(600 to 399, 599 to 400, 600 to 31, 53 to 400)) {
            val bitmap = preparer.decode(TestImages.png(width, height))!!
            assertThat("${width}x$height -> ${bitmap.width}x${bitmap.height}")
                .isEqualTo("${width}x$height -> ${width}x$height")
        }
    }

    /**
     * The other side of that bound: one pixel over on either axis is out, and gets fitted.
     */
    @Test
    fun onePixelOverEitherBoundIsFitted() {
        val wide = preparer.decode(TestImages.png(601, 400))!!
        assertThat("${wide.width}x${wide.height}").isEqualTo("600x399")

        val tall = preparer.decode(TestImages.png(600, 401))!!
        assertThat("${tall.width}x${tall.height}").isEqualTo("598x400")
    }

    /**
     * The whole point of the change, in one number. A 4000x3000 photo is 48 000 000 bytes decoded
     * whole, and the path this replaces decoded exactly that and then copied it again for the
     * rotation before fitting it. Here the decoder is asked for the fitted size up front, so the
     * bitmap that comes back is the only one this path allocates — 852 800 bytes, a factor of 56
     * against the first of the old path's two full-size copies.
     *
     * What this test can and cannot say: it reads the size of the bitmap that was produced. The
     * codec's own sampled intermediate lives inside Skia and no assertion here reaches it; from
     * `ImageDecoder::setTargetSize` it is `computeSampleSize`'s result, which is within a factor of
     * two of the target per axis. A heap delta would not help — §4.05 — and this class allocates no
     * Java bitmap besides the returned one, which is the stronger statement anyway.
     */
    @Test
    fun aTwelveMegapixelPhotoCostsOneBitmapTheSizeOfWhatIsKept() {
        val bitmap = preparer.decode(TestImages.jpeg(4000, 3000))!!
        assertThat("${bitmap.width}x${bitmap.height}").isEqualTo("533x400")
        assertThat(bitmap.byteCount).isEqualTo(533 * 400 * 4)
        assertThat(bitmap.byteCount.toLong() * 56).isLessThan(4000L * 3000L * 4L)
    }

    // ---- orientation: the platform decoder owns it ------------------------------------------

    /**
     * `ImageDecoder` applies the EXIF orientation itself and reports the **oriented** size from
     * `onHeaderDecoded`: `hwui/ImageDecoder.cpp` swaps width and height for an origin that swaps
     * them (ctor, and `width()`/`height()`, which are what `ImageInfo.getSize()` carries), and
     * `decode()` pre-concatenates `SkEncodedOriginToMatrix` into the output matrix. `BitmapFactory`
     * does not — which is why the old send path rotated by hand, and why doing that here as well
     * would turn a quarter turn into a half turn on a real device.
     *
     * So this class contributes no rotation at all, and this is the test that says so: the four
     * quarter-turn orientations come out with their axes swapped by the decoder, and adding any
     * rotation of our own would swap them back.
     */
    @Test
    fun theDecoderAppliesTheExifOrientationAndThisClassAddsNothingToIt() {
        val upright = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
        )
        val quarterTurned = listOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )

        for (orientation in upright + quarterTurned) {
            val bytes = TestImages.withExifOrientation(TestImages.jpeg(1200, 800), orientation)
            // The fixture is checked, not assumed: ExifInterface must read back a rotation for the
            // four that have one, or this test would pass over eight identical images.
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            assertThat(exif.rotationDegrees != 0 || exif.isFlipped).isEqualTo(orientation != ExifInterface.ORIENTATION_NORMAL)

            val bitmap = preparer.decode(bytes)!!
            val expected = if (orientation in quarterTurned) "266x400" else "600x400"
            assertThat("orientation $orientation -> ${bitmap.width}x${bitmap.height}")
                .isEqualTo("orientation $orientation -> $expected")
        }
    }

    @Test
    fun aPhotographWithoutExifIsLeftAsItIs() {
        val bytes = TestImages.jpeg(1200, 800)
        assertThat(ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees).isEqualTo(0)
        val bitmap = preparer.decode(bytes)!!
        assertThat("${bitmap.width}x${bitmap.height}").isEqualTo("600x400")
    }

    /**
     * Measured limitation, pinned so that a platform change shows up here rather than silently:
     * `ExifInterface` writes and reads an orientation in a PNG's `eXIf` chunk, but the decoder's
     * PNG codec does not act on it — the header comes back unswapped. Rotating it by hand would
     * need a second source of truth for orientation beside the decoder's, which is exactly the
     * split that has cost this stream a critical before; a PNG carrying an orientation is also not
     * something a camera produces.
     */
    @Test
    fun aPngsExifOrientationIsIgnoredByTheDecoderAndThereforeByUs() {
        val bytes = TestImages.withExifOrientation(TestImages.png(1200, 800), ExifInterface.ORIENTATION_ROTATE_90, ".png")
        assertThat(ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees).isEqualTo(90)

        val bitmap = preparer.decode(bytes)!!
        assertThat("${bitmap.width}x${bitmap.height}").isEqualTo("600x400")
    }

    // ---- things that are not an image --------------------------------------------------------

    @Test
    fun undecodableBytesGiveNull() {
        assertThat(preparer.decode("definitely not an image".toByteArray())).isNull()
    }

    @Test
    fun noBytesGiveNull() {
        assertThat(preparer.decode(ByteArray(0))).isNull()
    }

    @Test
    fun aPhotographCutShortGivesNull() {
        val whole = TestImages.jpeg(1200, 800)
        assertThat(preparer.decode(whole.copyOf(whole.size / 2))).isNull()
    }

    // ---- the bounds are a programming error when they are not positive -----------------------

    @Test
    fun aNonPositiveWidthBoundIsRejectedAtConstruction() {
        val thrown = runCatching { OutgoingImagePreparer(context, maxWidth = 0) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun aNonPositiveHeightBoundIsRejectedAtConstruction() {
        val thrown = runCatching { OutgoingImagePreparer(context, maxHeight = -1) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- reading the picked URI ---------------------------------------------------------------

    @Test
    fun prepareReadsThePickedUriOnce() = runTest {
        val uri = uri("1")
        val opens = serve(uri) { ByteArrayInputStream(TestImages.png(1200, 800)) }

        val bitmap = preparer.prepare(uri)!!

        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(400)
        // The path this replaces opened the URI twice: once for the EXIF, once for the decode.
        assertThat(opens.get()).isEqualTo(1)
    }

    /**
     * The stream is closed, not merely read to the end. Nothing else in this class reads that back,
     * which is exactly why it is written down: a file descriptor left open on a content provider is
     * invisible until there are enough of them.
     */
    @Test
    fun prepareClosesTheStreamItOpened() = runTest {
        val uri = uri("closed")
        var closed = false
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : ByteArrayInputStream(TestImages.png(100, 50)) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }

        assertThat(preparer.prepare(uri)).isNotNull()
        assertThat(closed).isTrue()
    }

    @Test
    fun aUriThatCannotBeOpenedGivesNull() = runTest {
        // Nothing registered: Robolectric's media provider answers with a FileNotFoundException.
        assertThat(preparer.prepare(uri("missing"))).isNull()
    }

    @Test
    fun aUriWhoseStreamFailsMidReadGivesNull() = runTest {
        val uri = uri("broken")
        serve(uri) {
            object : InputStream() {
                override fun read(): Int = throw IOException("disk on fire")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("disk on fire")
            }
        }
        assertThat(preparer.prepare(uri)).isNull()
    }

    /**
     * A picked URI is a grant, and a grant expires: after the process is restarted the same URI is
     * still in hand and `openInputStream` answers with a `SecurityException`. The old send path
     * caught `IOException` only, so that one crashed the app.
     */
    @Test
    fun aUriWhosePermissionWasRevokedGivesNull() = runTest {
        val uri = uri("revoked")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            throw SecurityException("Permission Denial: opening provider from ProcessRecord")
        }
        assertThat(preparer.prepare(uri)).isNull()
    }

    @Test
    fun aUriThatHoldsSomethingOtherThanAnImageGivesNull() = runTest {
        val uri = uri("text")
        serve(uri) { ByteArrayInputStream("definitely not an image".toByteArray()) }
        assertThat(preparer.prepare(uri)).isNull()
    }

    // ---- nothing here touches the caller's thread ---------------------------------------------

    @Test
    fun theReadRunsOnTheIoDispatcherAndTheDecodeOnTheDecodeDispatcher() = runTest {
        val (io, ioRuns) = countingDispatcher("mumla-test-io")
        val (decode, decodeRuns) = countingDispatcher("mumla-test-decode")
        val uri = uri("dispatchers")
        var readThread = "none"
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            readThread = Thread.currentThread().name.substringBefore(" @coroutine#")
            ByteArrayInputStream(TestImages.png(1200, 800))
        }

        val bitmap = OutgoingImagePreparer(context, ioDispatcher = io, decodeDispatcher = decode).prepare(uri)

        assertThat(bitmap).isNotNull()
        assertThat(readThread).isEqualTo("mumla-test-io")
        assertThat(ioRuns.get()).isAtLeast(1)
        assertThat(decodeRuns.get()).isAtLeast(1)
    }
}
