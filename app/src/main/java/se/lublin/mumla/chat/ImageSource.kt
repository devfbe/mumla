package se.lublin.mumla.chat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import se.lublin.mumla.util.HtmlUtils
import java.io.IOException
import java.util.Base64

enum class ImageError { UNSUPPORTED, MALFORMED, TOO_LARGE, NETWORK, TIMEOUT, EXTERNAL_DISABLED }

class ImageFetchException(val error: ImageError, cause: Throwable? = null) : Exception(error.name, cause)

/** A request the image HTTP client will not make, or a response it will not read. */
class ImageRefusedException(val error: ImageError, cause: Throwable? = null) : IOException(error.name, cause)

/**
 * Where the bytes of an `<img src>` come from.
 *
 * **Security contract, do not relax.** `ChatContentParser` passes the raw `src` through; [parse] must
 * classify `file:`, `javascript:`, `content:` and scheme-relative sources as [Unsupported]. Only a
 * [Remote] URL ever reaches the network, and only through [chatImageHttpClient].
 */
sealed class ImageSource {
    /** Not a data class: array `equals` would be identity anyway. */
    class Data(val bytes: ByteArray) : ImageSource()
    /** Parsed by OkHttp, the parser of the client that will connect, so both agree on the host. */
    data class Remote(val url: HttpUrl) : ImageSource()
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
         * decoding cost ~4.75 bytes per character (~10 MB at the cap). [ChatImageLoader] may run three
         * such parses at once, so the cap is sized as a concurrency budget that cannot exhaust the heap.
         */
        const val MAX_SOURCE_LENGTH = 2 * MURMUR_DEFAULT_IMAGE_MESSAGE_LENGTH

        /**
         * Classifies [source] (trimmed, raw); longer than [MAX_SOURCE_LENGTH] is [TooLarge]. A remote
         * source must be an absolute http(s) URL with a host.
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
                else -> trimmed.toHttpUrlOrNull()?.let(::Remote) ?: Unsupported
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
