package se.lublin.mumla.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Writes image bytes into the app cache and exposes them through the app's FileProvider.
 *
 * The file name is [ChatImageLoader.cacheKey] of the (hostile) source, safe as a path component only
 * because it is hex; there is no sanitiser here. [export] borrows `bytes`: read, never written or
 * kept.
 *
 * The write is atomic: the bytes go to a temporary file in the same directory, which is then
 * renamed over the share file. Two exports of one source (from the chat log and from the viewer)
 * can therefore overlap without mixing their bytes, and a receiver that already opened the earlier
 * file keeps reading it. A temporary file orphaned by a crash ages out through [prune].
 */
class ImageShareExporter(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Exported(val uri: Uri, val mimeType: String)

    @Throws(IOException::class)
    fun export(source: String, bytes: ByteArray): Exported {
        val (extension, mimeType) = typeOf(bytes)
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        prune(dir)
        val key = ChatImageLoader.cacheKey(source)
        val file = File(dir, "$key.$extension")
        val temp = File.createTempFile(key, TEMP_SUFFIX, dir)
        try {
            temp.outputStream().use { it.write(bytes) }
            if (!temp.renameTo(file)) throw IOException("could not move the share into place")
        } finally {
            temp.delete()
        }
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
        private const val TEMP_SUFFIX = ".tmp"

        /**
         * (file extension, MIME type) from magic bytes, never from the sender-chosen URL. Covers what
         * the viewer's `BitmapFactory` decodes, bar WBMP (no magic to tell it from noise). Anything
         * unrecognised is `application/octet-stream`.
         */
        fun typeOf(bytes: ByteArray): Pair<String, String> = when {
            bytes.hasAt(0, PNG_MAGIC) -> "png" to "image/png"
            bytes.hasAt(0, JPEG_MAGIC) -> "jpg" to "image/jpeg"
            bytes.hasAt(0, GIF_MAGIC) -> "gif" to "image/gif"
            bytes.hasAt(0, RIFF_MAGIC) && bytes.hasAt(WEBP_OFFSET, WEBP_MAGIC) -> "webp" to "image/webp"
            bytes.hasAt(0, BMP_MAGIC) -> "bmp" to "image/bmp"
            bytes.hasAt(0, ICO_MAGIC) -> "ico" to "image/x-icon"
            bytes.hasAt(FTYP_OFFSET, FTYP_MAGIC) -> isoImageType(bytes)
            else -> "bin" to "application/octet-stream"
        }

        /** HEIF and AVIF by the major brand of their `ftyp` box; any other brand (MP4, say) is no image. */
        private fun isoImageType(bytes: ByteArray): Pair<String, String> {
            val brand = if (bytes.size >= BRAND_OFFSET + BRAND_LENGTH) {
                String(bytes, BRAND_OFFSET, BRAND_LENGTH, Charsets.ISO_8859_1)
            } else {
                ""
            }
            return when (brand) {
                in HEIC_BRANDS -> "heic" to "image/heic"
                in HEIF_BRANDS -> "heif" to "image/heif"
                in AVIF_BRANDS -> "avif" to "image/avif"
                else -> "bin" to "application/octet-stream"
            }
        }

        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val GIF_MAGIC = "GIF".toByteArray(Charsets.US_ASCII)
        private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
        private val BMP_MAGIC = "BM".toByteArray(Charsets.US_ASCII)

        /** An ICONDIR: reserved 0, type 1 (2 would be a cursor). */
        private val ICO_MAGIC = byteArrayOf(0, 0, 1, 0)
        private val FTYP_MAGIC = "ftyp".toByteArray(Charsets.US_ASCII)
        private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx")
        private val HEIF_BRANDS = setOf("mif1", "msf1")
        private val AVIF_BRANDS = setOf("avif", "avis")

        /** A RIFF file's form type follows the "RIFF" tag and the chunk size. */
        private const val WEBP_OFFSET = 8

        /** An ISO base media file opens with a box size, then the `ftyp` tag and the major brand. */
        private const val FTYP_OFFSET = 4
        private const val BRAND_OFFSET = 8
        private const val BRAND_LENGTH = 4

        private fun ByteArray.hasAt(offset: Int, magic: ByteArray): Boolean =
            size >= offset + magic.size && magic.indices.all { this[offset + it] == magic[it] }
    }
}
