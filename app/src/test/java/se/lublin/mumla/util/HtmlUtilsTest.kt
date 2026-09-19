package se.lublin.mumla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlUtilsTest {

    @Test
    fun hostnameFromHttpsLink() {
        assertThat(HtmlUtils.getHostnameFromLink("https://example.org/path?q=1")).isEqualTo("example.org")
    }

    @Test
    fun hostnameIsNullForPlainText() {
        assertThat(HtmlUtils.getHostnameFromLink("not a link")).isNull()
    }

    @Test
    fun hostnameIsNullForMalformedUri() {
        assertThat(HtmlUtils.getHostnameFromLink("http://exa mple.org/")).isNull()
    }

    @Test
    fun percentDecodeDecodesEscapesButKeepsPlus() {
        assertThat(HtmlUtils.percentDecode("a%2Fb%3D+c")).isEqualTo("a/b=+c")
    }

    @Test
    fun percentDecodeDecodesUtf8Sequences() {
        assertThat(HtmlUtils.percentDecode("%C3%A4")).isEqualTo("ä")
    }

    @Test
    fun percentDecodeLeavesDanglingPercentAlone() {
        assertThat(HtmlUtils.percentDecode("100%")).isEqualTo("100%")
        assertThat(HtmlUtils.percentDecode("%zz")).isEqualTo("%zz")
    }

    @Test
    fun percentDecodeKeepsNonAsciiLiterals() {
        assertThat(HtmlUtils.percentDecode("😀%20x")).isEqualTo("😀 x")
    }

    @Test
    fun markupWrapsLinksAndConvertsNewlines() {
        assertThat(HtmlUtils.markupOutgoingMessage("see https://a.org/x\nnow"))
            .isEqualTo("see <a href=\"https://a.org/x\">https://a.org/x</a><br>now")
    }

    @Test
    fun markupLeavesPlainTextUntouched() {
        assertThat(HtmlUtils.markupOutgoingMessage("hello")).isEqualTo("hello")
    }
}
