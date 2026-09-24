package se.lublin.mumla.chat

import se.lublin.mumla.util.HtmlUtils
import java.util.Base64

enum class ImageError { UNSUPPORTED, MALFORMED, TOO_LARGE, NETWORK, TIMEOUT, EXTERNAL_DISABLED }

class ImageFetchException(val error: ImageError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * Where the bytes of an `<img src>` come from.
 *
 * **Security contract, do not relax.** `ChatContentParser` passes the raw `src` through; [parse] must
 * classify `file:`, `javascript:`, `content:` and scheme-relative sources as [Unsupported], and
 * [HttpImageFetcher] refuses anything that is not http(s) a second time.
 */
sealed class ImageSource {
    /** Not a data class: array `equals` would be identity anyway. */
    class Data(val bytes: ByteArray) : ImageSource()
    data class Remote(val url: String) : ImageSource()
    object Unsupported : ImageSource()

    /** Longer than [MAX_SOURCE_LENGTH]; never decoded. */
    object TooLarge : ImageSource()

    companion object {
        private const val DATA_PREFIX = "data:image"
        private const val BASE64_MARKER = ";base64"

        /**
         * Murmur's default `imagemessagelength` (Meta.cpp `iMaxImageMessageLength`), which bounds the
         * whole message in UTF-16 units, the same unit as [String.length].
         */
        private const val MURMUR_DEFAULT_IMAGE_MESSAGE_LENGTH = 1_048_576

        /**
         * Longest source string this parser will look at: twice a default Murmur message, so a server
         * that doubled `imagemessagelength` still shows inline images (base64 payload <= 1.5 MiB).
         *
         * The cap lives here because this is where the allocations are: percent-decoding and base64
         * decoding cost ~4.75 bytes per character (~10 MB at the cap). [ChatImageLoader] may run four
         * such parses at once, so the cap is sized as a concurrency budget that cannot exhaust the heap.
         */
        const val MAX_SOURCE_LENGTH = 2 * MURMUR_DEFAULT_IMAGE_MESSAGE_LENGTH

        /**
         * Classifies [source] (trimmed, raw); longer than [MAX_SOURCE_LENGTH] is [TooLarge]. The
         * case-insensitive prefix match folds some exotic characters (`httpſ://` matches
         * `https://`); [HttpImageFetcher] checks the scheme again exactly.
         *
         * Only `data:` URIs are percent-decoded (Mumble clients percent-encode the base64 payload);
         * decoding a remote URL would destroy legitimate escapes like `%20` or `%2F`.
         */
        fun parse(source: String): ImageSource {
            // Before anything copies or decodes the string.
            if (source.length > MAX_SOURCE_LENGTH) return TooLarge
            val trimmed = source.trim()
            return when {
                trimmed.startsWith(DATA_PREFIX, ignoreCase = true) -> parseData(HtmlUtils.percentDecode(trimmed))
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> Remote(trimmed)
                else -> Unsupported
            }
        }

        private fun parseData(uri: String): ImageSource {
            val comma = uri.indexOf(',')
            if (comma < 0) return Unsupported
            if (!uri.substring(0, comma).endsWith(BASE64_MARKER, ignoreCase = true)) return Unsupported
            return try {
                Data(Base64.getMimeDecoder().decode(uri.substring(comma + 1)))
            } catch (e: IllegalArgumentException) {
                Unsupported
            }
        }
    }
}
