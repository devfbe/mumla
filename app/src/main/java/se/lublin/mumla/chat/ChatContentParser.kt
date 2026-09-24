package se.lublin.mumla.chat

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.core.text.HtmlCompat

/**
 * Parses Mumble HTML message bodies into [ChatContent], splitting on the [ImageSpan] that
 * `HtmlCompat.fromHtml` inserts per `<img>`. Pure CPU work, safe to call from any thread.
 *
 * An image getter is passed because without one AOSP loads and mutates a framework drawable for
 * every `<img>`. Bodies are untrusted network input; the lenient parser never throws on malformed
 * markup, and `src` is handed on undecoded for a later stage to validate.
 */
class ChatContentParser(private val imagePlaceholder: String) {

    fun parse(html: String): ChatContent {
        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY, PLACEHOLDER_IMAGES, null)
        val builder = SpannableStringBuilder(spanned)
        val images = builder.getSpans(0, builder.length, ImageSpan::class.java)
            .sortedBy { builder.getSpanStart(it) }
        if (images.isEmpty()) return ChatContent.Text(spanned)

        val first = images.first()
        val start = builder.getSpanStart(first)
        val end = builder.getSpanEnd(first)
        val before = trimmed(builder.subSequence(0, start))
        val after = trimmed(replacePlaceholders(SpannableStringBuilder(builder.subSequence(end, builder.length))))
        return ChatContent.Image(first.source ?: "", before, after)
    }

    private fun replacePlaceholders(text: SpannableStringBuilder): SpannableStringBuilder {
        for (span in text.getSpans(0, text.length, ImageSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            text.removeSpan(span)
            text.replace(s, e, imagePlaceholder)
        }
        return text
    }

    private fun trimmed(text: CharSequence): Spanned? {
        var s = 0
        var e = text.length
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (s == e) null else SpannableStringBuilder(text, s, e)
    }

    private companion object {
        /** A 1x1 transparent drawable, so no framework drawable is loaded per `<img>`. */
        val PLACEHOLDER_IMAGES = Html.ImageGetter {
            ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, 1, 1) }
        }
    }
}
