package se.lublin.mumla.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes image bytes into the app cache and exposes them through the app's FileProvider.
 *
 * Two properties this class does not enforce and instead *inherits*, both worth naming because a
 * change elsewhere would break them silently:
 *
 *  - **The file name is [ChatImageLoader.cacheKey] of the source**, i.e. of a string a chat message
 *    chose. It is safe as a path component only because that key is forty hex characters; there is
 *    no sanitiser here to fall back on. `aHostileSourceCannotSteerTheExportOutOfTheSharedDirectory`
 *    pins it from this side.
 *  - **[export] borrows [bytes]**. `ChatImageLoader.fetchBytes` hands out its own remembered array
 *    rather than a copy, so the array may be megabytes long and is shared with the loader. It is
 *    read, never written and never kept.
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
     * This directory is a staging area for the share sheet, not a cache: nothing ever deletes the
     * files again, so drop everything older than [MAX_AGE_MS] whenever a new share is prepared.
     *
     * Runs *before* the write, so that a clock that has moved forward cannot delete the very share
     * it was asked to prepare. Nothing but [export] writes here, so there is no `isFile` test: in
     * every reachable state of this directory that clause is a **no-op**, not merely an unpinnable
     * one -- the entries are all files, and even if a directory appeared, `File.delete` leaves a
     * non-empty one alone by itself. A no-op reads exactly like a proven-unpinnable guard, so it is
     * recorded as the former.
     *
     * **This is the only caller.** Nothing prunes on a timer, on a dismissal or at startup, so a
     * user who shares exactly once keeps that file until Android clears the app's cache. That is the
     * deliberate trade: the receiving app opens the URI after this dialog is gone, so a deletion
     * tied to the viewer's lifetime would break the share it just made.
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
         * (file extension, MIME type) from magic bytes.
         *
         * The bytes decide, not the source URL: a URL's extension is whatever the sender typed, and
         * the receiving app opens what we say it is. Anything unrecognised stays
         * `application/octet-stream` rather than being guessed into an image type.
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
