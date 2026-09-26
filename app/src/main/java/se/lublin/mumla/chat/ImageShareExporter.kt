package se.lublin.mumla.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes image bytes into the app cache and exposes them through the app's FileProvider.
 *
 * The file name is [ChatImageLoader.cacheKey] of the (hostile) source, safe as a path component only
 * because it is hex; there is no sanitiser here. [export] borrows `bytes`: read, never written or
 * kept.
 */
class ImageShareExporter(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Exported(val uri: Uri, val mimeType: String)

    fun export(source: String, bytes: ByteArray): Exported {
        val (extension, mimeType) = typeOf(bytes)
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        prune(dir)
        val file = File(dir, ChatImageLoader.cacheKey(source) + "." + extension)
        file.outputStream().use { it.write(bytes) }
        return Exported(FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file), mimeType)
    }

    /**
     * Drops staged files older than [MAX_AGE_MS]. Runs before the write, so a clock jump cannot
     * delete the share being prepared. Only called from [export]: the receiving app opens the URI
     * after the viewer is gone, so deletion cannot be tied to the viewer's lifetime.
     */
    private fun prune(dir: File) {
        val cutoff = nowMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        const val DIRECTORY = "shared_images"
        const val AUTHORITY_SUFFIX = ".fileprovider"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /**
         * (file extension, MIME type) from magic bytes, never from the sender-chosen URL. Anything
         * unrecognised is `application/octet-stream`.
         */
        fun typeOf(bytes: ByteArray): Pair<String, String> = when {
            bytes.hasAt(0, PNG_MAGIC) -> "png" to "image/png"
            bytes.hasAt(0, JPEG_MAGIC) -> "jpg" to "image/jpeg"
            bytes.hasAt(0, GIF_MAGIC) -> "gif" to "image/gif"
            bytes.hasAt(0, RIFF_MAGIC) && bytes.hasAt(WEBP_OFFSET, WEBP_MAGIC) -> "webp" to "image/webp"
            else -> "bin" to "application/octet-stream"
        }

        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val GIF_MAGIC = "GIF".toByteArray(Charsets.US_ASCII)
        private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)

        /** A RIFF file's form type follows the "RIFF" tag and the chunk size. */
        private const val WEBP_OFFSET = 8

        private fun ByteArray.hasAt(offset: Int, magic: ByteArray): Boolean =
            size >= offset + magic.size && magic.indices.all { this[offset + it] == magic[it] }
    }
}
