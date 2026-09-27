package se.lublin.mumla.util

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class HtmlUtilsTest {
    @Test
    fun theHostnameOfALinkAndNoneOfAnythingElse() {
        mapOf(
            "https://example.org/path?q=1" to "example.org",
            "not a link" to null,
            "http://exa mple.org/" to null,
        ).forEach { (link, host) -> assertWithMessage(link).that(HtmlUtils.getHostnameFromLink(link)).isEqualTo(host) }
    }

    /** Escapes and UTF-8 sequences are decoded; `+`, dangling percents and non-ASCII literals stay. */
    @Test
    fun percentDecoding() {
        mapOf(
            "a%2Fb%3D+c" to "a/b=+c",
            "%C3%A4" to "ä",
            "100%" to "100%",
            "%zz" to "%zz",
            "😀%20x" to "😀 x",
        ).forEach { (encoded, decoded) ->
            assertWithMessage(encoded).that(HtmlUtils.percentDecode(encoded)).isEqualTo(decoded)
        }
    }

    @Test
    fun outgoingMarkupWrapsLinksAndConvertsNewlines() {
        mapOf(
            "see https://a.org/x\nnow" to "see <a href=\"https://a.org/x\">https://a.org/x</a><br>now",
            "hello" to "hello",
        ).forEach { (text, html) ->
            assertWithMessage(text).that(HtmlUtils.markupOutgoingMessage(text)).isEqualTo(html)
        }
    }
}
