package se.lublin.mumla.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Saves image bytes to the shared "Pictures/Mumla" gallery album via MediaStore (no permission on
 * API 29+). The row is inserted with IS_PENDING=1, written, then published with IS_PENDING=0; on any
 * failure the row is deleted again (a row orphaned by process death expires by MediaStore's own
 * pending-row cleanup). Type and extension come from the content ([ImageShareExporter.typeOf]),
 * never the URL. Borrows `bytes`: never writes to or keeps them. Blocking: call off the main thread.
 */
class ImageGallerySaver(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    data class Saved(val uri: Uri, val mimeType: String)

    /** Throws only [IOException]; two saves in the same second get distinct names from MediaStore. */
    @Throws(IOException::class)
    fun save(bytes: ByteArray): Saved {
        val (extension, mimeType) = ImageShareExporter.typeOf(bytes)
        if (!isImageType(mimeType)) throw IOException("not an image type MediaStore accepts")
        val uri = insertPending(fileName(nowMillis(), zone(), extension), mimeType)
        try {
            writeAndPublish(uri, bytes)
        } catch (e: IOException) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
        return Saved(uri, mimeType)
    }

    /** A row the gallery does not show yet: IS_PENDING hides it from other apps until published. */
    private fun insertPending(displayName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return platform { context.contentResolver.insert(COLLECTION, values) }
            ?: throw IOException("MediaStore refused the insert")
    }

    private fun writeAndPublish(uri: Uri, bytes: ByteArray) {
        platform {
            val resolver = context.contentResolver
            (resolver.openOutputStream(uri, "w") ?: throw IOException("no stream for $uri")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    /**
     * The resolver's own failures (a volume that went away, a provider refusing the values) arrive
     * as runtime exceptions; as [IOException] the callers handle one type.
     */
    private inline fun <T> platform(block: () -> T): T = try {
        block()
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        throw IOException(e)
    }

    companion object {
        val RELATIVE_PATH: String = Environment.DIRECTORY_PICTURES + "/Mumla"

        /** Whether [save] would take [bytes]; the viewer offers saving only then. */
        fun canSave(bytes: ByteArray): Boolean = isImageType(ImageShareExporter.typeOf(bytes).second)

        // The Images collection refuses anything else, and an unknown blob is no picture to keep.
        private fun isImageType(mimeType: String) = mimeType.startsWith("image/")

        @VisibleForTesting
        internal val COLLECTION: Uri
            get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

        @VisibleForTesting
        internal fun fileName(millis: Long, zone: ZoneId, extension: String): String =
            "Mumla_" + NAME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone)) + "." + extension
    }
}
