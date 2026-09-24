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
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png" to "image/png"
            bytes.startsWith(0xFF, 0xD8) -> "jpg" to "image/jpeg"
            bytes.startsWith('G'.code, 'I'.code, 'F'.code) -> "gif" to "image/gif"
            bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.size >= 12 &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "webp" to "image/webp"
            else -> "bin" to "application/octet-stream"
        }

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
