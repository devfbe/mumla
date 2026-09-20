package se.lublin.mumla.chat

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * Turns a prepared bitmap into the `<img src="data:image/jpeg;base64,PAYLOAD"/>` element Mumble
 * clients exchange, at the highest JPEG quality whose message still fits the server's limit.
 *
 * The payload is percent-encoded, which is what `HtmlUtils.percentDecode` and [ImageSource.parse]
 * undo on the receiving side; `OutgoingImageEncoderTest` pins the round trip through both.
 */
object OutgoingImageEncoder {
    const val START_QUALITY = 97
    const val QUALITY_STEP = 10

    private const val PREFIX = "<img src=\"data:image/jpeg;base64,"
    private const val SUFFIX = "\"/>"

    /**
     * JPEG-compresses [bitmap], lowering the quality until [imageHtml] of the result fits
     * [maxMessageLength]. Returns null when even the lowest rung does not fit, or when the JPEG
     * encoder refuses this bitmap at every rung.
     *
     * [maxMessageLength] is the server's `ImageMessageLength`; zero (and anything below it) means
     * the server declared no limit.
     */
    fun compressToFit(bitmap: Bitmap, maxMessageLength: Int): ByteArray? =
        fit(bitmap, maxMessageLength)?.first

    /**
     * The wire form. Note what is measured against the server's limit: **this string**, not the
     * JPEG and not its base64.
     *
     * Murmur enforces `imagemessagelength` in `Server::isTextAllowed` as
     * `length > iMaxImageMessageLength` over `QString::length()` of the whole message — markup,
     * text and every `src` together, in UTF-16 units, which for this all-ASCII element is its
     * character count. Percent-encoding turns each `+`, `/` and `=` of the base64 into three
     * characters; measured over one quality ladder that is 4.1 % to 7.7 % on top of the base64
     * length, before the 35 characters of markup here.
     */
    fun imageHtml(jpeg: ByteArray): String =
        PREFIX + URLEncoder.encode(Base64.encodeToString(jpeg, Base64.NO_WRAP), "UTF-8") + SUFFIX

    /** [compressToFit] + [imageHtml]; null when the image cannot be made to fit. */
    fun encode(bitmap: Bitmap, maxMessageLength: Int): String? =
        fit(bitmap, maxMessageLength)?.second

    /**
     * One ladder for both entry points. Splitting it in two would compress the image twice for a
     * caller that wants the message, and would let the two copies drift apart about what "fits"
     * means — the failure mode that cost this stream three rounds when a URL's authority had two
     * readers.
     *
     * The message is built at every rung rather than estimated from the byte count. An estimate is
     * a second implementation of [imageHtml] that no test compares against it; the string that is
     * measured here is the one that is sent.
     */
    private fun fit(bitmap: Bitmap, maxMessageLength: Int): Pair<ByteArray, String>? {
        var quality = START_QUALITY
        while (quality > 0) {
            val stream = ByteArrayOutputStream()
            // `false` means nothing was written: an ALPHA_8 bitmap has no JPEG. Using the stream
            // anyway would send an <img> with an empty payload, which fits every limit and shows
            // nothing.
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                val jpeg = stream.toByteArray()
                val html = imageHtml(jpeg)
                // Three of the four corners of this two-boolean condition are written as tests; the
                // fourth cannot exist. "No limit" means `maxMessageLength <= 0`, and `html` is never
                // shorter than the 35 characters of markup around the payload, so "no limit and
                // also fits" has no input. Measured: `||` mutated to `xor`, which differs on that
                // corner alone, leaves all 34 tests green, while `&&` fails 7.
                if (maxMessageLength <= 0 || html.length <= maxMessageLength) return jpeg to html
            }
            quality -= QUALITY_STEP
        }
        return null
    }
}
