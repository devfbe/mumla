package se.lublin.mumla.chat

import android.content.Context
import androidx.core.content.FileProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImageShareExporterTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    // Hand-computed, not derived from the code under test:
    //   $ printf 'https://x.org/a.png' | sha1sum
    //   c03da97f398e3f951d29689263e7fa31bf3c163d
    private val keyOfA = "c03da97f398e3f951d29689263e7fa31bf3c163d"
    private val sourceA = "https://x.org/a.png"

    private val dir: File get() = File(context.cacheDir, ImageShareExporter.DIRECTORY)

    /**
     * Robolectric hands every test in a class the same `cacheDir`, so a file one test wrote is a
     * file the next test's prune sees. Without this, `theWholeDirectoryIsPrunedWhenTheClockJumps`
     * would delete another test's fixture and `prune` would look load-bearing for the wrong reason.
     */
    @Before
    fun emptyTheShareDirectory() {
        FileProviderCache.clear()
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // --- the brief's three tests ----------------------------------------------------------------

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

    @Test
    fun detectsCommonImageTypesByMagicBytes() {
        assertThat(ImageShareExporter.typeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))).isEqualTo("jpg" to "image/jpeg")
        assertThat(ImageShareExporter.typeOf("GIF89a".toByteArray())).isEqualTo("gif" to "image/gif")
        assertThat(ImageShareExporter.typeOf("RIFF    WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))).isEqualTo("webp" to "image/webp")
        assertThat(ImageShareExporter.typeOf("hello".toByteArray())).isEqualTo("bin" to "application/octet-stream")
    }

    // --- the magic-byte set, clause by clause ---------------------------------------------------

    /**
     * The WEBP arm is three clauses in one bracket (`RIFF` prefix, at least 12 bytes, `WEBP` at
     * offset 8) and each is mutated on its own: drop the prefix and `....    WEBP` would pass, drop
     * the length and a 4-byte `RIFF` would index out of bounds, drop the tag and a `.wav` would be
     * shared as an image.
     */
    @Test
    fun aRiffContainerThatIsNotWebpIsNotAnImage() {
        assertThat(ImageShareExporter.typeOf("RIFF    WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
            .isEqualTo("bin" to "application/octet-stream")
        assertThat(ImageShareExporter.typeOf("RIFF".toByteArray()))
            .isEqualTo("bin" to "application/octet-stream")
        assertThat(ImageShareExporter.typeOf("RIFF    WEBP".toByteArray(Charsets.ISO_8859_1)))
            .isEqualTo("webp" to "image/webp")
        assertThat(ImageShareExporter.typeOf("xIFF    WEBP".toByteArray(Charsets.ISO_8859_1)))
            .isEqualTo("bin" to "application/octet-stream")
    }

    /** A prefix longer than the whole input must be a miss, not an index out of bounds. */
    @Test
    fun typesShorterThanTheirMagicAreNotImages() {
        assertThat(ImageShareExporter.typeOf(ByteArray(0))).isEqualTo("bin" to "application/octet-stream")
        assertThat(ImageShareExporter.typeOf(byteArrayOf(0xFF.toByte()))).isEqualTo("bin" to "application/octet-stream")
        assertThat(ImageShareExporter.typeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E))).isEqualTo("bin" to "application/octet-stream")
        assertThat(ImageShareExporter.typeOf("GI".toByteArray())).isEqualTo("bin" to "application/octet-stream")
    }

    /** Exactly as long as its magic, i.e. the `size >= prefix.size` boundary from below. */
    @Test
    fun typesExactlyAsLongAsTheirMagicAreRecognised() {
        assertThat(ImageShareExporter.typeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))).isEqualTo("jpg" to "image/jpeg")
        assertThat(ImageShareExporter.typeOf("GIF".toByteArray())).isEqualTo("gif" to "image/gif")
        assertThat(ImageShareExporter.typeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
            .isEqualTo("png" to "image/png")
    }

    // --- the file name ---------------------------------------------------------------------------

    /**
     * The share file is named after [ChatImageLoader.cacheKey], i.e. a chat message picks the name
     * of a file this app hands to another app. The name must therefore stay inside the one
     * directory the provider publishes whatever the message said, and it does so because the key is
     * forty hex characters and nothing else -- there is no sanitiser here to carry the weight.
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
        val canonicalDir = dir.canonicalFile
        for (source in hostile) {
            val exported = exporter.export(source, TestImages.png(2, 2))
            val name = exported.uri.lastPathSegment!!
            assertThat(name).matches("[0-9a-f]{40}\\.png")
            assertThat(File(dir, name).canonicalFile.parentFile).isEqualTo(canonicalDir)
        }
    }

    /**
     * The provider publishes exactly one subtree. `getUriForFile` refusing everything else is what
     * keeps `shared_image_paths.xml` from being widened to the whole cache without anyone noticing:
     * the app's own HTTP cache and WebView data live one level up.
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
     * The manifest half of the same promise. `exported="false"` is what keeps another app from
     * querying the provider directly instead of waiting to be handed a grant, and
     * `grantUriPermissions="true"` is what makes the grant on the share intent mean anything at all
     * -- without it the chooser's flag is silently inert and the receiver sees a SecurityException.
     */
    @Test
    fun theProviderIsPrivateAndGrantsPerUri() {
        val info = context.packageManager
            .resolveContentProvider(context.packageName + ImageShareExporter.AUTHORITY_SUFFIX, 0)!!
        assertThat(info.exported).isFalse()
        assertThat(info.grantUriPermissions).isTrue()
    }

    // --- the bytes are borrowed, and the pruning window ------------------------------------------

    /**
     * `ChatImageLoader.fetchBytes` hands out its own remembered array rather than a copy, so the
     * array this method is given is shared with the loader's cache. Writing to it would corrupt
     * every later use of that source.
     */
    @Test
    fun theBytesHandedInAreNotModified() {
        val png = TestImages.png(8, 8)
        val untouched = png.copyOf()
        ImageShareExporter(context).export(sourceA, png)
        assertThat(png).isEqualTo(untouched)
    }

    @Test
    fun aShareYoungerThanADayIsKept() {
        dir.mkdirs()
        val now = 10 * ImageShareExporter.MAX_AGE_MS
        val fresh = File(dir, "fresh.png").apply { writeBytes(TestImages.png(2, 2)) }
        assertThat(fresh.setLastModified(now - ImageShareExporter.MAX_AGE_MS + 1)).isTrue()

        ImageShareExporter(context, nowMillis = { now }).export(sourceA, TestImages.png(4, 4))

        assertThat(fresh.exists()).isTrue()
    }

    /** The cutoff itself: exactly [ImageShareExporter.MAX_AGE_MS] old is still young enough. */
    @Test
    fun aShareExactlyAtTheCutoffIsKept() {
        dir.mkdirs()
        val now = 10 * ImageShareExporter.MAX_AGE_MS
        val edge = File(dir, "edge.png").apply { writeBytes(TestImages.png(2, 2)) }
        assertThat(edge.setLastModified(now - ImageShareExporter.MAX_AGE_MS)).isTrue()

        ImageShareExporter(context, nowMillis = { now }).export(sourceA, TestImages.png(4, 4))

        assertThat(edge.exists()).isTrue()
    }

    /**
     * Pruning happens before the write, never after. With the clock past every existing file's age
     * the whole directory goes, and the export still has to produce the file it was asked for --
     * which is the assertion that tells the two orders apart.
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
}
