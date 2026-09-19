package se.lublin.mumla.chat

import android.text.style.StyleSpan
import com.google.common.truth.Truth.assertThat
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
        val content = parser.parse("<img src=\"https://x.org/a.png\">") as ChatContent.Image
        assertThat(content.source).isEqualTo("https://x.org/a.png")
        assertThat(content.textBefore).isNull()
        assertThat(content.textAfter).isNull()
    }

    @Test
    fun imageSourceIsKeptAsSent() {
        val content = parser.parse("<img src=\"data:image/jpeg;base64,%2F9j%2F4AAQ%3D\"/>") as ChatContent.Image
        assertThat(content.source).isEqualTo("data:image/jpeg;base64,%2F9j%2F4AAQ%3D")
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

    // --- Hostile / malformed input coverage ---

    @Test
    fun unterminatedAndNestedTagsDoNotThrow() {
        val content = parser.parse("<b>bold <i>italic <u>under") as ChatContent.Text
        assertThat(content.spanned.toString()).isEqualTo("bold italic under")
    }

    @Test
    fun unquotedAndMismatchedQuoteAttributesDoNotThrow() {
        val content1 = parser.parse("<img src=unquoted.png>") as ChatContent.Image
        assertThat(content1.source).isEqualTo("unquoted.png")

        // Mismatched quote style: single-quoted attribute value. Must not throw.
        val content2 = parser.parse("<img src='single.png'>") as ChatContent.Image
        assertThat(content2.source).isEqualTo("single.png")
    }

    @Test
    fun imgWithNoSrcAttributeDoesNotThrowAndYieldsEmptySource() {
        val content = parser.parse("<img>text") as ChatContent.Image
        assertThat(content.source).isEqualTo("")
    }

    @Test
    fun imgWithEmptySrcYieldsEmptySource() {
        val content = parser.parse("<img src=\"\">text") as ChatContent.Image
        assertThat(content.source).isEqualTo("")
    }

    @Test
    fun severalImgTagsOnlyFirstBecomesImageContent() {
        val content = parser.parse("<img src=\"one\"><img src=\"two\"><img src=\"three\">") as ChatContent.Image
        assertThat(content.source).isEqualTo("one")
        assertThat(content.textAfter.toString()).isEqualTo("[image][image]")
    }

    @Test
    fun nonImageSrcSchemeIsKeptRawNotInterpreted() {
        val javascript = parser.parse("<img src=\"javascript:alert(1)\">") as ChatContent.Image
        assertThat(javascript.source).isEqualTo("javascript:alert(1)")

        val file = parser.parse("<img src=\"file:///etc/passwd\">") as ChatContent.Image
        assertThat(file.source).isEqualTo("file:///etc/passwd")
    }

    @Test
    fun imageOnlyMessageHasNoText() {
        val content = parser.parse("<img src=\"a\">") as ChatContent.Image
        assertThat(content.textBefore).isNull()
        assertThat(content.textAfter).isNull()
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
