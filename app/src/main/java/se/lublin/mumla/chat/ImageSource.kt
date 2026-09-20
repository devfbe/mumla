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
    /**
     * Deliberately **not** a data class: [bytes] is an array, so a generated `equals` would compare
     * by identity anyway and merely look as if it compared by value. Nothing may key a cache or a
     * deduplication on parsed chat content — the spans inside a parsed message compare by identity
     * too, so two parses of the same message are never equal.
     */
    class Data(val bytes: ByteArray) : ImageSource()
    data class Remote(val url: String) : ImageSource()
    object Unsupported : ImageSource()

    /**
     * Longer than [MAX_SOURCE_LENGTH], and therefore never looked at: the payload is neither
     * percent-decoded nor base64-decoded, which is the allocation the cap exists to prevent.
     */
    object TooLarge : ImageSource()

    companion object {
        private const val DATA_PREFIX = "data:image"
        private const val BASE64_MARKER = ";base64"

        /**
         * Murmur's own default for `imagemessagelength`, and the reason [MAX_SOURCE_LENGTH] is that
         * number rather than a round one. It bounds the **whole** chat message — markup, text and
         * every `<img src>` together — so on a default server no single `src` can be longer than
         * this, however it is spelled.
         *
         * `src/murmur/Meta.cpp`, `MetaParams::MetaParams`: `iMaxImageMessageLength = 1048576;`,
         * overridden from the ini by
         * `typeCheckedFromSettings("imagemessagelength", iMaxImageMessageLength)`. Enforced in
         * `src/murmur/Server.cpp`, `Server::isTextAllowed`, which compares it against
         * `text.length()` — Qt's UTF-16 count, the same unit [String.length] counts here.
         */
        private const val MURMUR_DEFAULT_IMAGE_MESSAGE_LENGTH = 1_048_576

        /**
         * Longest source string this parser will look at: twice what a default Murmur will carry in
         * one whole message, so one `src` may be the entire message *and* a server that doubled
         * `imagemessagelength` still has its inline images shown. Base64 costs four characters per
         * three bytes, so it bounds an inline payload at 1_572_846 B = 1.5 MiB — well under
         * [HttpImageFetcher]'s 5 MiB cap on a remote body, and deliberately so: a remote body costs
         * its own size, an inline one costs 4.75 times its length before anything can be done with
         * it.
         *
         * The cap lives **here**, not in the caller, because this is where the allocations are:
         * [HtmlUtils.percentDecode] builds a `StringBuilder` of the input's length and then a
         * `toString()` of it, and `Base64.getMimeDecoder().decode` materialises the whole payload.
         * A caller that forgot to ask would hand all three of those a megabytes-long string, and no
         * assertion on the returned value could tell the difference.
         *
         * **It is a concurrency budget, not a per-call one, and that is what set the number.**
         * Measured on one [parse] of a maximal percent-encoded `data:` source: 4.75 bytes allocated
         * per character, 10_137_896 B. [ChatImageLoader] lets
         * [ChatImageLoader.DEFAULT_MAX_CONCURRENT_LOADS] loads run at once and its `fetchBytes`
         * share path takes no permit at all, so the honest worst case is **four** of those at the
         * same instant — 40_551_584 B — on top of the thumbnail cache's `maxMemory() / 8`. The first
         * cap this stream shipped, 7_000_000, made the same four come to 133_001_792 B, more than
         * the whole heap of a small device: three concurrent parses at that cap needed an `-Xmx` of
         * 160 MiB before they completed at all, four at this one need 56 MiB. Nothing on the load
         * path catches an [OutOfMemoryError] — deliberately, so that an exhausted heap fails where
         * it can be diagnosed — which is exactly why the cap has to be one that cannot cause one.
         * Pinned by `fourMaximalSourcesParsedAtOnceFitTheMemoryBudget`.
         */
        const val MAX_SOURCE_LENGTH = 2 * MURMUR_DEFAULT_IMAGE_MESSAGE_LENGTH

        /**
         * Classifies [source] (trimmed, raw). The scheme prefixes are matched case-insensitively,
         * which folds a few exotic characters onto ASCII ones — `httpſ://x` (U+017F) matches
         * `https://` and is classified [Remote]. That is safe because [HttpImageFetcher] checks the
         * scheme again, exactly, before it opens anything; this classifier is not the last word.
         *
         * A source longer than [MAX_SOURCE_LENGTH] is [TooLarge] and is not classified at all.
         *
         * Percent decoding is applied **only** to `data:` URIs,
         * because Mumble clients percent-encode the base64 payload they send. A remote URL is passed
         * through untouched: decoding it would destroy legitimate escapes (`%20` would become a space,
         * which `URI(url)` then rejects, and `%2F` would silently change the path).
         */
        fun parse(source: String): ImageSource {
            // First, before anything copies or decodes the string. See MAX_SOURCE_LENGTH.
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
