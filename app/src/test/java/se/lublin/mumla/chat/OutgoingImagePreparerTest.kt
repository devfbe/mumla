package se.lublin.mumla.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import se.lublin.mumla.util.BitmapUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A provider that finds the row and then hands back nothing, which is what makes
 * `ContentResolver.openInputStream` return the `null` its signature declares:
 * `openAssetFileDescriptor` propagates the provider's `null` and `openInputStream` returns it
 * unwrapped rather than throwing. Registering a Robolectric input-stream supplier that answers
 * `null` does **not** reproduce it — measured, that path throws `FileNotFoundException`.
 *
 * Real shapes behind it: a document provider whose backing file has been deleted or is on an
 * unmounted volume, and a cloud provider that has nothing cached and refuses to download.
 */
class NullOpeningProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(u: Uri, values: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = null
    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? = null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = null
}

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
 *
 * **What the fixture set does not contain**, named so the next round does not mistake a mutation
 * that survives here for a covered one. [TestImages] builds its bytes with `javax.imageio`, which
 * ships PNG and baseline JPEG and nothing else, so none of these reaches the decoder in any test:
 * **HEIC/HEIF — which is what a current phone camera actually hands the picker** — an alpha
 * channel (the one input whose JPEG encoding the encoder half refuses outright), a progressive
 * JPEG, and CMYK. None of them is a *dimension this class branches on*: every one of them enters
 * at `ImageDecoder.createSource` and leaves as a bitmap or as a `DecodeException`, both of which
 * are covered by container-independent fixtures. They are listed, not built.
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

    private companion object {
        const val CALLER = "mumla-test-caller"
        const val NOTHING_AUTHORITY = "se.lublin.mumla.test.opensnothing"
    }

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
     * **The substitution here was forced, not chosen.** The obligation this test answers to asks
     * for the allocation chain to be shown with `ShadowBitmap.getCreatedFromBitmap()` and
     * explicitly *not* with a final-size assertion. That instrument does not exist in the mode this
     * class must run in: under `@GraphicsMode(NATIVE)` the shadow is `ShadowNativeBitmap` and
     * `getCreatedFromBitmap()` throws `UnsupportedOperationException` for every bitmap. The mode is
     * not negotiable either — see the class comment; under LEGACY the decoder produces the full
     * image and scales afterwards, so a LEGACY reading of a memory claim says the opposite of the
     * truth, and all eight EXIF orientations come back the same size. The obligation now reads:
     * whoever needs NATIVE names that the counting instrument is absent there and says what the
     * replacement carries.
     *
     * So, what this replacement carries and what it does not. It **carries**: the bitmap this path
     * hands back is allocated at the fitted size, not at the source size, and — since this class
     * creates no other `Bitmap` at all — it is the only Java bitmap the path allocates, which is
     * the stronger of the two statements. It does **not** carry: anything about the codec's own
     * sampled intermediate, which lives inside Skia where no assertion here reaches it. From
     * `ImageDecoder::setTargetSize` that intermediate is `computeSampleSize`'s result, within a
     * factor of two of the target per axis, so the true peak is a small multiple of the number
     * below rather than the number itself. A heap delta cannot close that gap — §4.05 — it measures
     * the opposite, because Robolectric does not implement `inJustDecodeBounds`.
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
     * Measured limitation, accepted rather than patched, and pinned so that a platform change shows
     * up here rather than silently: `ExifInterface` writes and reads an orientation in a PNG's
     * `eXIf` chunk, but the decoder's PNG codec does not act on it — the header comes back
     * unswapped. So this one case, which the old hand-rotating send path did turn, stops being
     * turned.
     *
     * **Why patching it is worse, in the sharpened form — a half orientation correction is worse
     * than none.** Rotating by hand on top of a decoder that may already have rotated needs a
     * second source of truth and some way to tell which of the two has already acted. That
     * question is answerable for exactly half the orientations: for the four axis-swapping ones
     * (5–8) the decoder's action *is* detectable, by comparing `info.size` against the container's
     * own header dimensions. For 180° and for the mirrorings it is **not** detectable at all, and
     * neither is it for a square image. A fix that corrects four of eight cases and silently
     * double-applies or drops the rest is worse than the one documented gap, which is also the one
     * a camera never produces — phones emit JPEG and HEIF.
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

    /**
     * The `@Nullable` arm of `openInputStream`, which is a real device shape and not a hole in the
     * fixture: a provider that opens nothing makes the resolver hand back `null` rather than throw
     * (see [NullOpeningProvider]). Without the `?.` this is a `NullPointerException` caught by
     * neither `catch (IOException)` nor `catch (SecurityException)`, so it leaves `prepare` and
     * crashes the app — the same shape as the `SecurityException` defect this class exists to fix,
     * one exception type further along.
     */
    @Test
    fun aProviderThatOpensNothingGivesNull() = runTest {
        Robolectric.setupContentProvider(NullOpeningProvider::class.java, NOTHING_AUTHORITY)
        val uri = Uri.parse("content://$NOTHING_AUTHORITY/images/1")
        // The fixture is checked, not assumed: this is the one arm where the resolver answers with
        // null instead of an exception, and a fixture that threw instead would pass for the wrong
        // reason through the catches below.
        assertThat(context.contentResolver.openInputStream(uri)).isNull()

        assertThat(preparer.prepare(uri)).isNull()
    }

    @Test
    fun aUriThatHoldsSomethingOtherThanAnImageGivesNull() = runTest {
        val uri = uri("text")
        serve(uri) { ByteArrayInputStream("definitely not an image".toByteArray()) }
        assertThat(preparer.prepare(uri)).isNull()
    }

    // ---- nothing here touches the caller's thread ---------------------------------------------

    /**
     * The **defaults**, which are the constructor the app actually uses:
     * `ChannelChatFragment.onImagePicked` builds `OutgoingImagePreparer(requireContext())` and
     * awaits it on `lifecycleScope`, so on the main thread. Every other test here injects
     * dispatchers, so until this one existed the two default expressions were never constructed at
     * all, and `ioDispatcher = Dispatchers.IO` mutated to `Dispatchers.Main` left the whole suite
     * green — a content-provider open plus `readBytes()` of an arbitrarily large picked file on the
     * main thread, against this class's own promise that nothing here touches it.
     *
     * Two assertions, two different mutations, in this order because the second needs the first:
     *  - **completion** kills a default that dispatches to `Dispatchers.Main`. The main looper is
     *    deliberately never idled here, so a body posted to it is never run and the poll returns
     *    null instead of a result. Measured: with the defaults replaced by `Dispatchers.Main` the
     *    poll runs out with `readThread` still `"none"` — so the thread assertion below would
     *    **not** have caught it. This is a liveness bound and not a performance budget (well under
     *    a second in practice against a 30 s poll), so it is not the wall-clock flake of §4.05.
     *  - **the thread** kills a default that runs inline on whoever called, `Dispatchers.Unconfined`,
     *    which completion cannot see. In production that caller is the main thread, so "inline on
     *    the caller" is the same ANR by a second route.
     *
     * The caller is a background thread rather than this one: a `Dispatchers.Main` dispatch made
     * from the test thread blocks it against Robolectric's paused looper and would hang the suite
     * instead of failing it. It is a *daemon* thread for the same reason one level up — measured, a
     * live non-daemon thread stuck on `Dispatchers.Main` keeps the test JVM from exiting and Gradle
     * reports `java.io.EOFException` in place of any test result at all.
     *
     * What this does **not** cover, named rather than implied: `decodeDispatcher` mutated to
     * `Dispatchers.Unconfined` resumes inline on the caller after the read has already suspended,
     * and nothing in this class reads the decode thread back.
     */
    @Test
    fun theDefaultDispatchersKeepBothHalvesOffTheMainAndTheCallersThread() {
        val mainThread = Thread.currentThread().name
        val uri = uri("defaults")
        var readThread = "none"
        serve(uri) {
            readThread = Thread.currentThread().name.substringBefore(" @coroutine#")
            ByteArrayInputStream(TestImages.png(1200, 800))
        }
        val preparer = OutgoingImagePreparer(context)

        val outcome = ArrayBlockingQueue<Result<Bitmap?>>(1)
        Thread({ outcome.add(runCatching { runBlocking { preparer.prepare(uri) } }) }, CALLER)
            .apply { isDaemon = true }
            .start()

        val taken = outcome.poll(30, TimeUnit.SECONDS)
        assertThat(taken).isNotNull()
        val bitmap = taken!!.getOrThrow()!!
        assertThat("${bitmap.width}x${bitmap.height}").isEqualTo("600x400")
        assertThat(readThread).isNotEqualTo(mainThread)
        assertThat(readThread).isNotEqualTo(CALLER)
    }

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
