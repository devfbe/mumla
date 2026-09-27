package se.lublin.mumla.chat

import android.text.style.StyleSpan
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatContentParserTest {
    private val parser = ChatContentParser("[image]")

    @Test
    fun plainHtmlBecomesStyledText() {
        val content = parser.parse("hello <b>world</b>") as ChatContent.Text
        assertThat(content.spanned.toString()).isEqualTo("hello world")
        assertThat(content.spanned.getSpans(0, content.spanned.length, StyleSpan::class.java)).hasLength(1)
    }

    @Test
    fun imageWithSurroundingTextIsSplit() {
        val content = parser.parse("look: <img src=\"data:image/png;base64,AAAA\"/> nice") as ChatContent.Image
        assertThat(content.source).isEqualTo("data:image/png;base64,AAAA")
        assertThat(content.textBefore.toString()).isEqualTo("look:")
        assertThat(content.textAfter.toString()).isEqualTo("nice")
    }

    @Test
    fun bareImageHasNoText() {
        for (html in listOf("<img src=\"https://x.org/a.png\">", "<img src=\"a\">")) {
            val content = parser.parse(html) as ChatContent.Image
            assertWithMessage(html).that(content.textBefore).isNull()
            assertWithMessage(html).that(content.textAfter).isNull()
        }
    }

    /**
     * The source is kept exactly as sent, whatever its scheme, and odd markup does not throw: an
     * unquoted or single-quoted value is read, a missing or empty one is empty.
     */
    @Test
    fun theImageSourceIsTheRawAttribute() {
        mapOf(
            "<img src=\"https://x.org/a.png\">" to "https://x.org/a.png",
            "<img src=\"data:image/jpeg;base64,%2F9j%2F4AAQ%3D\"/>" to "data:image/jpeg;base64,%2F9j%2F4AAQ%3D",
            "<img src=unquoted.png>" to "unquoted.png",
            "<img src='single.png'>" to "single.png",
            "<img>text" to "",
            "<img src=\"\">text" to "",
            "<img src=\"javascript:alert(1)\">" to "javascript:alert(1)",
            "<img src=\"file:///etc/passwd\">" to "file:///etc/passwd",
        ).forEach { (html, source) ->
            assertWithMessage(html).that((parser.parse(html) as ChatContent.Image).source).isEqualTo(source)
        }
    }

    @Test
    fun additionalImagesBecomePlaceholders() {
        val content = parser.parse("<img src=\"a\"/>x<img src=\"b\"/>y") as ChatContent.Image
        assertThat(content.source).isEqualTo("a")
        assertThat(content.textBefore).isNull()
        assertThat(content.textAfter.toString()).isEqualTo("x[image]y")
    }

    @Test
    fun linksSurviveInText() {
        val content = parser.parse("<a href=\"https://a.org\">a</a>") as ChatContent.Text
        assertThat(content.spanned.getSpans(0, 1, android.text.style.URLSpan::class.java).single().url)
            .isEqualTo("https://a.org")
    }

    @Test
    fun unterminatedAndNestedTagsDoNotThrow() {
        val content = parser.parse("<b>bold <i>italic <u>under") as ChatContent.Text
        assertThat(content.spanned.toString()).isEqualTo("bold italic under")
    }

    @Test
    fun severalImgTagsOnlyFirstBecomesImageContent() {
        val content = parser.parse("<img src=\"one\"><img src=\"two\"><img src=\"three\">") as ChatContent.Image
        assertThat(content.source).isEqualTo("one")
        assertThat(content.textAfter.toString()).isEqualTo("[image][image]")
    }

    @Test
    fun imageWithTextOnBothSidesIsSplit() {
        val content = parser.parse("before<img src=\"a\">after") as ChatContent.Image
        assertThat(content.textBefore.toString()).isEqualTo("before")
        assertThat(content.textAfter.toString()).isEqualTo("after")
    }

    @Test
    fun veryLargeMessageDoesNotThrow() {
        val large = "word ".repeat(200_000)
        val content = parser.parse(large) as ChatContent.Text
        assertThat(content.spanned.length).isGreaterThan(500_000)
    }

    @Test
    fun deeplyNestedMarkupDoesNotThrow() {
        val open = "<b>".repeat(2000)
        val close = "</b>".repeat(2000)
        val content = parser.parse(open + "x" + close) as ChatContent.Text
        assertThat(content.spanned.toString()).isEqualTo("x")
    }

    @Test
    fun emptyStringYieldsEmptyText() {
        val content = parser.parse("") as ChatContent.Text
        assertThat(content.spanned.toString()).isEqualTo("")
    }

    @Test
    fun whitespaceOnlyStringDoesNotThrow() {
        val content = parser.parse("   \n\t  ") as ChatContent.Text
        assertThat(content.spanned.toString().isBlank()).isTrue()
    }
}
