package se.lublin.mumla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HtmlPlainTextTest {
    @Test
    fun htmlBecomesOneLineOfPlainText() {
        mapOf(
            "  <b>hi</b>\n\n <i>there</i>  " to "hi there",
            "<p>one</p><p>two<br>three</p>" to "one two three",
            "fish &amp; chips &lt;3" to "fish & chips <3",
            "look <img src=\"data:image/png;base64,AAAA\"/> here" to "look here",
            "just text" to "just text",
        ).forEach { (html, text) -> assertThat(HtmlUtils.toPlainText(html)).isEqualTo(text) }
    }

    /** A link whose text is its own URL shrinks to its host; named links and host-less URLs stay. */
    @Test
    fun bareLinksAreShortenedToTheirHost() {
        mapOf(
            "see <a href=\"https://example.org/a/b\">https://example.org/a/b</a> and " +
                "<a href=\"https://other.org/x\">named</a>" to "see <example.org> and named",
            "<a href=\"https://a.org/1\">https://a.org/1</a> <a href=\"https://b.org/2\">https://b.org/2</a>" to
                "<a.org> <b.org>",
            "<a href=\"mailto:someone\">mailto:someone</a>" to "mailto:someone",
        ).forEach { (html, text) ->
            assertThat(HtmlUtils.toPlainTextWithShortLinks(html) { host -> "<$host>" }).isEqualTo(text)
        }
    }
}
