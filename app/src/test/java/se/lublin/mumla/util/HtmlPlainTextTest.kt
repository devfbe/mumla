package se.lublin.mumla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HtmlPlainTextTest {

    @Test
    fun tagsAreStrippedAndWhitespaceCollapsed() {
        assertThat(HtmlUtils.toPlainText("  <b>hi</b>\n\n <i>there</i>  "))
            .isEqualTo("hi there")
    }

    @Test
    fun lineAndParagraphBreaksBecomeSingleSpaces() {
        assertThat(HtmlUtils.toPlainText("<p>one</p><p>two<br>three</p>"))
            .isEqualTo("one two three")
    }

    @Test
    fun entitiesAreDecoded() {
        assertThat(HtmlUtils.toPlainText("fish &amp; chips &lt;3"))
            .isEqualTo("fish & chips <3")
    }

    @Test
    fun imagesLeaveNoPlaceholderBehind() {
        assertThat(HtmlUtils.toPlainText("look <img src=\"data:image/png;base64,AAAA\"/> here"))
            .isEqualTo("look here")
    }

    @Test
    fun plainTextIsReturnedAsIs() {
        assertThat(HtmlUtils.toPlainText("just text")).isEqualTo("just text")
    }

    @Test
    fun bareLinksAreShortenedToTheirHost() {
        val text = HtmlUtils.toPlainTextWithShortLinks(
            "see <a href=\"https://example.org/a/b\">https://example.org/a/b</a> and " +
                "<a href=\"https://other.org/x\">named</a>",
        ) { host -> "[link to $host]" }

        assertThat(text).isEqualTo("see [link to example.org] and named")
    }

    @Test
    fun severalBareLinksAreEachShortened() {
        val text = HtmlUtils.toPlainTextWithShortLinks(
            "<a href=\"https://a.org/1\">https://a.org/1</a> <a href=\"https://b.org/2\">https://b.org/2</a>",
        ) { host -> "<$host>" }

        assertThat(text).isEqualTo("<a.org> <b.org>")
    }

    @Test
    fun aBareLinkWithoutAHostIsKept() {
        val text = HtmlUtils.toPlainTextWithShortLinks(
            "<a href=\"mailto:someone\">mailto:someone</a>",
        ) { host -> "<$host>" }

        assertThat(text).isEqualTo("mailto:someone")
    }
}
