package se.lublin.mumla.chat

import se.lublin.mumla.util.HtmlUtils
import java.util.Base64

enum class ImageError { UNSUPPORTED, MALFORMED, TOO_LARGE, NETWORK, TIMEOUT, EXTERNAL_DISABLED }

class ImageFetchException(val error: ImageError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * Where the bytes of an `<img src>` come from.
 *
 * **Security contract, do not relax.** `ChatContentParser` deliberately does not look at the `src`
 * scheme: it hands the raw attribute through byte for byte, which is safe there because nothing at
 * that layer dereferences it. [parse] is the first of the two places where a hostile source has to
 * die — a `file:`, `javascript:`, `content:` or scheme-relative source must classify as
 * [Unsupported] and never reach a loader. [HttpImageFetcher] is the second: it refuses anything
 * that is not an http(s) URL outright. Both are pinned by tests.
 */
sealed class ImageSource {
    class Data(val bytes: ByteArray) : ImageSource()
    data class Remote(val url: String) : ImageSource()
    object Unsupported : ImageSource()

    companion object {
        private const val DATA_PREFIX = "data:image"
        private const val BASE64_MARKER = ";base64"

        /**
         * Classifies [source] (trimmed, raw). Percent decoding is applied **only** to `data:` URIs,
         * because Mumble clients percent-encode the base64 payload they send. A remote URL is passed
         * through untouched: decoding it would destroy legitimate escapes (`%20` would become a space,
         * which `URI(url)` then rejects, and `%2F` would silently change the path).
         */
        fun parse(source: String): ImageSource {
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
