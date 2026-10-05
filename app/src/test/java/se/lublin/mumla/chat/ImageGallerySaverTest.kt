package se.lublin.mumla.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.lublin.mumla.chat.FixedRowMediaProvider.Op
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The steps are checked against [FixedRowMediaProvider]; what a stored row ends up as, against
 * Robolectric's `FakeMediaProvider`, which checks MIME type and relative path per collection as
 * MediaStore does.
 */
@RunWith(RobolectricTestRunner::class)
class ImageGallerySaverTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun readBack(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    /** The columns of [uri]'s row in the fake MediaStore. */
    private fun row(uri: Uri): ContentValues {
        val columns = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
        )
        return context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            ContentValues().apply {
                for (column in columns) put(column, cursor.getString(cursor.getColumnIndexOrThrow(column)))
            }
        }
    }

    @Test
    fun aSaveIsInsertedPendingWrittenThenPublished() {
        val written = ByteArrayOutputStream()
        FixedRowMediaProvider.install(into = written)
        val png = TestImages.png(4, 4)

        val saved = ImageGallerySaver(context).save(png)

        val calls = FixedRowMediaProvider.calls
        assertThat(calls.map { it.op }).containsExactly(Op.INSERT, Op.WRITE, Op.UPDATE).inOrder()
        val (_, insertedInto, values) = calls[0]
        assertThat(insertedInto).isEqualTo(ImageGallerySaver.COLLECTION)
        assertThat(values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)).matches("""Mumla_[-_0-9]{19}\.png""")
        assertThat(values.getAsString(MediaStore.MediaColumns.MIME_TYPE)).isEqualTo("image/png")
        assertThat(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)).isEqualTo("Pictures/Mumla")
        assertThat(values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)).isEqualTo(1)
        assertThat(written.toByteArray()).isEqualTo(png)
        val (_, updated, published) = calls[2]
        assertThat(updated).isEqualTo(FixedRowMediaProvider.ROW)
        assertThat(published!!.valueSet().map { it.key to it.value })
            .containsExactly(MediaStore.MediaColumns.IS_PENDING to 0)
        assertThat(saved).isEqualTo(ImageGallerySaver.Saved(FixedRowMediaProvider.ROW, "image/png"))
    }

    /** What the gallery ends up holding: the bytes, published, in the Mumla album. */
    @Test
    fun aSavedRowIsPublishedInTheAlbumWithTheBytes() {
        val png = TestImages.png(4, 4)

        val saved = ImageGallerySaver(context).save(png)

        assertThat(saved.uri.toString()).startsWith(ImageGallerySaver.COLLECTION.toString() + "/")
        assertThat(readBack(saved.uri)).isEqualTo(png)
        val row = row(saved.uri)
        assertThat(row.getAsString(MediaStore.MediaColumns.MIME_TYPE)).isEqualTo("image/png")
        assertThat(row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)).startsWith("Pictures/Mumla")
        assertThat(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)).endsWith(".png")
        assertThat(row.getAsString(MediaStore.MediaColumns.IS_PENDING)).isEqualTo("0")
    }

    /** Type and extension come from the bytes, as for a share; the store accepts each. */
    @Test
    fun theTypeFollowsTheContent() {
        fun latin1(text: String) = text.toByteArray(Charsets.ISO_8859_1)
        val cases = listOf(
            latin1("GIF89a....") to ("image/gif" to "gif"),
            TestImages.jpeg(4, 4) to ("image/jpeg" to "jpg"),
            latin1("RIFF    WEBPVP8 ") to ("image/webp" to "webp"),
            latin1("BM......") to ("image/bmp" to "bmp"),
            latin1("\u0000\u0000\u0000\u0018ftypheic") to ("image/heic" to "heic"),
            latin1("\u0000\u0000\u0000\u0018ftypmif1") to ("image/heif" to "heif"),
            latin1("\u0000\u0000\u0000\u0018ftypavif") to ("image/avif" to "avif"),
            byteArrayOf(0, 0, 1, 0) to ("image/x-icon" to "ico"),
        )
        for ((bytes, expected) in cases) {
            val (mime, extension) = expected
            val saved = ImageGallerySaver(context).save(bytes)
            val row = row(saved.uri)
            assertWithMessage(mime).that(saved.mimeType).isEqualTo(mime)
            assertWithMessage(mime).that(row.getAsString(MediaStore.MediaColumns.MIME_TYPE)).isEqualTo(mime)
            assertWithMessage(mime).that(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)).endsWith(".$extension")
            assertWithMessage(mime).that(readBack(saved.uri)).isEqualTo(bytes)
        }
    }

    /** MediaStore's Images collection accepts only image/ types; nothing is inserted for the rest. */
    @Test
    fun bytesThatAreNoKnownImageAreRefusedBeforeAnInsert() {
        FixedRowMediaProvider.install(into = ByteArrayOutputStream())

        assertThrows(IOException::class.java) {
            ImageGallerySaver(context).save("not an image at all".toByteArray())
        }
        assertThat(FixedRowMediaProvider.calls).isEmpty()
    }

    /** A half-written row must not stay behind in the gallery. */
    @Test
    fun aWriteThatFailsDeletesThePendingRow() {
        FixedRowMediaProvider.install(into = FailingOutputStream())

        assertThrows(IOException::class.java) { ImageGallerySaver(context).save(TestImages.png(4, 4)) }

        assertThat(FixedRowMediaProvider.calls.map { it.op to it.uri }).containsExactly(
            Op.INSERT to ImageGallerySaver.COLLECTION,
            Op.WRITE to FixedRowMediaProvider.ROW,
            Op.DELETE to FixedRowMediaProvider.ROW,
        ).inOrder()
    }

    @Test
    fun aRefusedInsertFailsWithoutWriting() {
        val written = ByteArrayOutputStream()
        FixedRowMediaProvider.install(into = written)
        Robolectric.setupContentProvider(RefusingMediaProvider::class.java, MediaStore.AUTHORITY)

        assertThrows(IOException::class.java) { ImageGallerySaver(context).save(TestImages.png(4, 4)) }

        assertThat(written.size()).isEqualTo(0)
        assertThat(FixedRowMediaProvider.calls).isEmpty()
    }

    /** A provider that throws (a volume that went away) still ends as an [IOException]. */
    @Test
    fun aProviderThatThrowsEndsAsAnIOException() {
        Robolectric.setupContentProvider(ThrowingMediaProvider::class.java, MediaStore.AUTHORITY)

        val thrown = assertThrows(IOException::class.java) { ImageGallerySaver(context).save(TestImages.png(4, 4)) }

        assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun theFileNameIsTheLocalTimeOfTheSave() {
        val millis = ZonedDateTime.of(2026, 10, 5, 14, 3, 9, 0, berlin).toInstant().toEpochMilli()
        assertThat(ImageGallerySaver.fileName(millis, berlin, "png")).isEqualTo("Mumla_2026-10-05_14-03-09.png")

        // The same instant in another zone names another local time: the zone is honoured.
        assertThat(ImageGallerySaver.fileName(millis, ZoneId.of("UTC"), "png"))
            .isEqualTo("Mumla_2026-10-05_12-03-09.png")

        FixedRowMediaProvider.install(into = ByteArrayOutputStream())
        ImageGallerySaver(context, nowMillis = { millis }, zone = { berlin }).save(TestImages.png(4, 4))
        assertThat(FixedRowMediaProvider.calls[0].values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            .isEqualTo("Mumla_2026-10-05_14-03-09.png")
    }

    /** The array is shared with the loader's cache (`fetchBytes` hands out no copy): never write it. */
    @Test
    fun theBytesHandedInAreNotModified() {
        val png = TestImages.png(8, 8)
        val untouched = png.copyOf()
        ImageGallerySaver(context).save(png)
        assertThat(png).isEqualTo(untouched)
    }

    /** A MediaStore that refuses every insert, as the contract allows. */
    class RefusingMediaProvider : MediaProviderStub() {
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    }

    /** A MediaStore whose volume is gone. */
    class ThrowingMediaProvider : MediaProviderStub() {
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("volume unmounted")
    }

    abstract class MediaProviderStub : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
