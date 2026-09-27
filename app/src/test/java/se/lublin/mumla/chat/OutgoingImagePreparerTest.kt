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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A provider that finds the row and hands back nothing, which makes
 * `ContentResolver.openInputStream` return `null` (a Robolectric input-stream supplier answering
 * `null` throws `FileNotFoundException` instead). Real shapes: a deleted or unmounted document, a
 * cloud provider that refuses to download.
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
 * Native graphics: Robolectric's legacy `ShadowImageDecoder` never applies EXIF orientation, never
 * samples, and throws NPE instead of `DecodeException` on a bad image.
 *
 * Not covered by the fixtures (javax.imageio writes only PNG and baseline JPEG): HEIC/HEIF, alpha,
 * progressive JPEG, CMYK. All of them enter at `ImageDecoder.createSource` and leave as a bitmap
 * or a `DecodeException`, like the covered containers.
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

    /** An image exactly on the bound comes back untouched, without a round trip through the scaler. */
    @Test
    fun anImageExactlyOnTheBoundIsAlreadyWithinIt() {
        val bitmap = preparer.decode(TestImages.png(600, 400))!!
        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(400)
    }

    /** 3000:1 fitted into 600x400 would be 0.2 px tall; the floor prevents a zero-sized decode. */
    @Test
    fun anExtremeAspectRatioKeepsAtLeastOnePixelOnTheShortAxis() {
        val bitmap = preparer.decode(TestImages.png(3000, 1))!!
        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(1)

        val tall = preparer.decode(TestImages.png(1, 3000))!!
        assertThat(tall.width).isEqualTo(1)
        assertThat(tall.height).isEqualTo(400)
    }

    /** Aspect-ratio fit into 600 x 400, never enlarged, never below one pixel. */
    @Test
    fun theTargetSizeFitsTheBoundsKeepingTheAspectRatio() {
        val expected = listOf(
            (1200 to 800) to (600 to 400),
            (800 to 1200) to (266 to 400),
            (100 to 50) to (100 to 50),
            (600 to 400) to (600 to 400),
            (600 to 399) to (600 to 399),
            (599 to 400) to (599 to 400),
            (600 to 31) to (600 to 31),
            (53 to 400) to (53 to 400),
            (3000 to 1) to (600 to 1),
            (1 to 3000) to (1 to 400),
            (4000 to 3000) to (533 to 400),
            (601 to 400) to (600 to 399),
            (600 to 401) to (598 to 400),
        )
        for ((source, target) in expected) {
            val bounded = OutgoingImagePreparer.boundedSize(source.first, source.second, 600, 400)
            assertThat("$source -> ${bounded.width to bounded.height}").isEqualTo("$source -> $target")
        }
    }

    /**
     * An image touching exactly one bound comes back untouched; the general fit runs the ratio
     * through a float divide and can lose a pixel. 600x31 and 53x400 are the smallest sizes for
     * which a strict `<` on each axis would actually lose one.
     */
    @Test
    fun anImageTouchingOnlyOneBoundIsLeftAlone() {
        for ((width, height) in listOf(600 to 399, 599 to 400, 600 to 31, 53 to 400)) {
            val bitmap = preparer.decode(TestImages.png(width, height))!!
            assertThat("${width}x$height -> ${bitmap.width}x${bitmap.height}")
                .isEqualTo("${width}x$height -> ${width}x$height")
        }
    }

    @Test
    fun onePixelOverEitherBoundIsFitted() {
        val wide = preparer.decode(TestImages.png(601, 400))!!
        assertThat("${wide.width}x${wide.height}").isEqualTo("600x399")

        val tall = preparer.decode(TestImages.png(600, 401))!!
        assertThat("${tall.width}x${tall.height}").isEqualTo("598x400")
    }

    /**
     * A 4000x3000 photo is decoded at the fitted size up front (852 800 bytes instead of 48 MB).
     * `ShadowNativeBitmap.getCreatedFromBitmap()` is unsupported under NATIVE graphics, so this
     * asserts the final size; the class creates no other `Bitmap`. Skia's sampled intermediate
     * (within 2x of the target per axis) is out of reach here.
     */
    @Test
    fun aTwelveMegapixelPhotoCostsOneBitmapTheSizeOfWhatIsKept() {
        val bitmap = preparer.decode(TestImages.jpeg(4000, 3000))!!
        assertThat("${bitmap.width}x${bitmap.height}").isEqualTo("533x400")
        assertThat(bitmap.byteCount).isEqualTo(533 * 400 * 4)
        assertThat(bitmap.byteCount.toLong() * 56).isLessThan(4000L * 3000L * 4L)
    }

    /**
     * `ImageDecoder` applies the EXIF orientation itself and reports the oriented size from
     * `onHeaderDecoded` (`BitmapFactory` does not). This class adds no rotation of its own: the four
     * quarter-turn orientations come out with swapped axes, and any extra rotation would swap them
     * back.
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
            // ExifInterface must read back a rotation for the four that have one.
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            assertThat(exif.rotationDegrees != 0 || exif.isFlipped)
                .isEqualTo(orientation != ExifInterface.ORIENTATION_NORMAL)

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
     * Known limitation, pinned so a platform change shows up: the decoder's PNG codec ignores an
     * `eXIf` orientation. Rotating by hand is not safe, because whether the decoder already rotated
     * is only detectable for the axis-swapping orientations; cameras emit JPEG and HEIF anyway.
     */
    @Test
    fun aPngsExifOrientationIsIgnoredByTheDecoderAndThereforeByUs() {
        val bytes = TestImages.withExifOrientation(
            TestImages.png(1200, 800),
            ExifInterface.ORIENTATION_ROTATE_90,
            ".png",
        )
        assertThat(ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees).isEqualTo(90)

        val bitmap = preparer.decode(bytes)!!
        assertThat("${bitmap.width}x${bitmap.height}").isEqualTo("600x400")
    }

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

    @Test
    fun prepareReadsThePickedUriOnce() = runTest {
        val uri = uri("1")
        val opens = serve(uri) { ByteArrayInputStream(TestImages.png(1200, 800)) }

        val bitmap = preparer.prepare(uri)!!

        assertThat(bitmap.width).isEqualTo(600)
        assertThat(bitmap.height).isEqualTo(400)
        // The URI is opened once, for header and decode.
        assertThat(opens.get()).isEqualTo(1)
    }

    /** The stream is closed, not merely read to the end: leaked provider fds are invisible. */
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

    /** An expired grant (e.g. after a process restart) makes `openInputStream` throw `SecurityException`. */
    @Test
    fun aUriWhosePermissionWasRevokedGivesNull() = runTest {
        val uri = uri("revoked")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            throw SecurityException("Permission Denial: opening provider from ProcessRecord")
        }
        assertThat(preparer.prepare(uri)).isNull()
    }

    /** The `null` arm of `openInputStream` (see [NullOpeningProvider]) must not become an NPE. */
    @Test
    fun aProviderThatOpensNothingGivesNull() = runTest {
        Robolectric.setupContentProvider(NullOpeningProvider::class.java, NOTHING_AUTHORITY)
        val uri = Uri.parse("content://$NOTHING_AUTHORITY/images/1")
        // The resolver really answers with null here, not with an exception.
        assertThat(context.contentResolver.openInputStream(uri)).isNull()

        assertThat(preparer.prepare(uri)).isNull()
    }

    @Test
    fun aUriThatHoldsSomethingOtherThanAnImageGivesNull() = runTest {
        val uri = uri("text")
        serve(uri) { ByteArrayInputStream("definitely not an image".toByteArray()) }
        assertThat(preparer.prepare(uri)).isNull()
    }

    /**
     * The default dispatchers, which the app uses from `lifecycleScope` on the main thread.
     * Completion catches a default of `Dispatchers.Main` (the main looper is never idled here);
     * the thread check catches `Dispatchers.Unconfined`. The caller is a daemon background thread:
     * a `Dispatchers.Main` dispatch from the test thread would hang against Robolectric's paused
     * looper, and a non-daemon thread stuck there keeps the test JVM from exiting.
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
