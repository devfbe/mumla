package se.lublin.mumla.chat

import android.content.Context
import androidx.core.content.FileProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.lublin.mumla.testing.FileProviderCache
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImageShareExporterTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    // printf 'https://x.org/a.png' | sha1sum
    private val keyOfA = "c03da97f398e3f951d29689263e7fa31bf3c163d"
    private val sourceA = "https://x.org/a.png"

    private val dir: File get() = File(context.cacheDir, ImageShareExporter.DIRECTORY)

    /** Robolectric shares `cacheDir` between the tests of a class, so start from an empty one. */
    @Before
    fun emptyTheShareDirectory() {
        FileProviderCache.clear()
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun exportsPngUnderTheFileProviderAuthority() {
        val png = TestImages.png(4, 4)
        val exported = ImageShareExporter(context).export(sourceA, png)
        assertThat(exported.mimeType).isEqualTo("image/png")
        assertThat(exported.uri.toString())
            .isEqualTo("content://${context.packageName}.fileprovider/shared_images/$keyOfA.png")
        assertThat(File(context.cacheDir, "shared_images/$keyOfA.png").readBytes()).isEqualTo(png)
    }

    @Test
    fun sharesOlderThanADayAreDeletedOnTheNextExport() {
        dir.mkdirs()
        val stale = File(dir, "stale.png").apply { writeBytes(TestImages.png(2, 2)) }
        assertThat(stale.setLastModified(System.currentTimeMillis() - 2 * ImageShareExporter.MAX_AGE_MS)).isTrue()

        ImageShareExporter(context).export(sourceA, TestImages.png(4, 4))

        assertThat(stale.exists()).isFalse()
        assertThat(File(dir, "$keyOfA.png").exists()).isTrue()
    }

    /**
     * The magic-byte set clause by clause. Each clause of the WEBP check (`RIFF` prefix, at least
     * 12 bytes, `WEBP` at offset 8) on its own: without the tag a `.wav` would be shared as an
     * image. A prefix longer than the whole input is a miss, not an index out of bounds, and an
     * input exactly as long as its magic is recognised.
     */
    @Test
    fun imageTypesAreDetectedByMagicBytes() {
        val jpg = "jpg" to "image/jpeg"
        val gif = "gif" to "image/gif"
        val png = "png" to "image/png"
        val webp = "webp" to "image/webp"
        val bin = "bin" to "application/octet-stream"
        fun latin1(text: String) = text.toByteArray(Charsets.ISO_8859_1)
        val cases = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to jpg,
            latin1("GIF89a") to gif,
            latin1("RIFF    WEBPVP8 ") to webp,
            latin1("hello") to bin,
            latin1("RIFF    WAVEfmt ") to bin,
            latin1("RIFF") to bin,
            latin1("RIFF    WEBP") to webp,
            latin1("xIFF    WEBP") to bin,
            ByteArray(0) to bin,
            byteArrayOf(0xFF.toByte()) to bin,
            byteArrayOf(0x89.toByte(), 0x50, 0x4E) to bin,
            latin1("GI") to bin,
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) to jpg,
            latin1("GIF") to gif,
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) to png,
            latin1("BM") to ("bmp" to "image/bmp"),
            latin1("B") to bin,
            byteArrayOf(0, 0, 1, 0) to ("ico" to "image/x-icon"),
            byteArrayOf(0, 0, 2, 0) to bin,
            latin1("\u0000\u0000\u0000\u0018ftypheic") to ("heic" to "image/heic"),
            latin1("\u0000\u0000\u0000\u0018ftypheix") to ("heic" to "image/heic"),
            latin1("\u0000\u0000\u0000\u0018ftypmif1") to ("heif" to "image/heif"),
            latin1("\u0000\u0000\u0000\u0018ftypavif") to ("avif" to "image/avif"),
            // An MP4 is an ISO box file too; only the image brands count.
            latin1("\u0000\u0000\u0000\u0018ftypisom") to bin,
            latin1("\u0000\u0000\u0000\u0018ftyphei") to bin,
        )
        for ((bytes, expected) in cases) {
            assertWithMessage(bytes.contentToString()).that(ImageShareExporter.typeOf(bytes)).isEqualTo(expected)
        }
    }

    /**
     * A chat message picks the share file's name via [ChatImageLoader.cacheKey], so the name must
     * stay inside the published directory; the key is forty hex characters and nothing else.
     */
    @Test
    fun aHostileSourceCannotSteerTheExportOutOfTheSharedDirectory() {
        val hostile = listOf(
            "../../../../data/data/se.lublin.mumla/databases/mumla.db",
            "/etc/passwd",
            "a/../../b.png",
            "..",
            "\u0000evil",
        )
        val exporter = ImageShareExporter(context)
        for (source in hostile) {
            val exported = exporter.export(source, TestImages.png(2, 2))
            assertThat(exported.uri.lastPathSegment).matches("[0-9a-f]{40}\\.png")
        }
    }

    /**
     * The provider publishes only its share subtrees; the app's HTTP cache and WebView data live
     * one level up.
     */
    @Test
    fun theProviderPublishesNothingButTheShareDirectory() {
        val authority = context.packageName + ImageShareExporter.AUTHORITY_SUFFIX
        for (outside in listOf(File(context.cacheDir, "secret.txt"), File(context.filesDir, "secret.txt"))) {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, outside)
            }
        }
        // ...and the one inside still resolves, so the assertion above is not vacuously true.
        assertThat(FileProvider.getUriForFile(context, authority, File(dir, "x.png")).toString())
            .isEqualTo("content://$authority/shared_images/x.png")
    }

    /**
     * `exported="false"` keeps other apps from querying the provider directly;
     * `grantUriPermissions="true"` makes the share intent's grant work at all.
     */
    @Test
    fun theProviderIsPrivateAndGrantsPerUri() {
        val info = context.packageManager
            .resolveContentProvider(context.packageName + ImageShareExporter.AUTHORITY_SUFFIX, 0)!!
        assertThat(info.exported).isFalse()
        assertThat(info.grantUriPermissions).isTrue()
    }

    // --- the bytes are borrowed, and the pruning window ------------------------------------------

    /** The array is shared with the loader's cache (`fetchBytes` hands out no copy): never write it. */
    @Test
    fun theBytesHandedInAreNotModified() {
        val png = TestImages.png(8, 8)
        val untouched = png.copyOf()
        ImageShareExporter(context).export(sourceA, png)
        assertThat(png).isEqualTo(untouched)
    }

    /** Younger than a day is kept, and so is exactly [ImageShareExporter.MAX_AGE_MS] old: the cutoff. */
    @Test
    fun aShareUpToADayOldIsKept() {
        dir.mkdirs()
        val now = 10 * ImageShareExporter.MAX_AGE_MS
        for (margin in listOf(1L, 0L)) {
            val kept = File(dir, "kept$margin.png").apply { writeBytes(TestImages.png(2, 2)) }
            assertThat(kept.setLastModified(now - ImageShareExporter.MAX_AGE_MS + margin)).isTrue()

            ImageShareExporter(context, nowMillis = { now }).export(sourceA, TestImages.png(4, 4))

            assertWithMessage("$margin ms younger than a day").that(kept.exists()).isTrue()
        }
    }

    /**
     * Pruning happens before the write: with the clock past every file's age the whole directory
     * goes, and the export still produces its file.
     */
    @Test
    fun theWholeDirectoryIsPrunedWhenTheClockJumpsAndTheNewShareSurvives() {
        dir.mkdirs()
        val old = (1..3).map { File(dir, "old$it.png").apply { writeBytes(TestImages.png(2, 2)) } }

        val exported = ImageShareExporter(context, nowMillis = { Long.MAX_VALUE / 2 })
            .export(sourceA, TestImages.png(4, 4))

        assertThat(old.filter { it.exists() }).isEmpty()
        assertThat(File(dir, "$keyOfA.png").exists()).isTrue()
        assertThat(exported.uri.lastPathSegment).isEqualTo("$keyOfA.png")
    }

    @Test
    fun theMaximumAgeIsOneDay() {
        assertThat(ImageShareExporter.MAX_AGE_MS).isEqualTo(24L * 60 * 60 * 1000)
    }

    // --- the write is atomic -------------------------------------------------------------------

    /** The bytes go to a temporary file that is renamed into place; none may be left over. */
    @Test
    fun anExportLeavesNoTemporaryFileBehind() {
        ImageShareExporter(context).export(sourceA, TestImages.png(4, 4))

        assertThat(dir.list()!!.toList()).containsExactly("$keyOfA.png")
    }

    /**
     * A share from the chat log and one from the viewer can export the same source; the later one
     * replaces the file under the same URI rather than writing into it.
     */
    @Test
    fun aSecondExportOfTheSameSourceReplacesTheFile() {
        val exporter = ImageShareExporter(context)
        val first = exporter.export(sourceA, TestImages.png(4, 4))
        val newer = TestImages.png(8, 8)

        val second = exporter.export(sourceA, newer)

        assertThat(second.uri).isEqualTo(first.uri)
        assertThat(File(dir, "$keyOfA.png").readBytes()).isEqualTo(newer)
        assertThat(dir.list()!!.toList()).containsExactly("$keyOfA.png")
    }

    /**
     * An app that opened the earlier share is still reading it when the next export of the source
     * lands: it keeps the bytes it opened, not a file truncated and rewritten under it.
     */
    @Test
    fun aReceiverStillReadingTheEarlierShareKeepsItsBytes() {
        val exporter = ImageShareExporter(context)
        val older = TestImages.png(4, 4)
        exporter.export(sourceA, older)

        File(dir, "$keyOfA.png").inputStream().use { reading ->
            exporter.export(sourceA, TestImages.png(8, 8))

            assertThat(reading.readBytes()).isEqualTo(older)
        }
    }
}
