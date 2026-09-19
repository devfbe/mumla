package se.lublin.mumla.chat

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.core.text.HtmlCompat

/**
 * Parses Mumble HTML message bodies into [ChatContent]. Pure CPU work, safe to call from any thread.
 * `HtmlCompat.fromHtml` inserts an [ImageSpan] per `<img>`, which is what this parser splits on.
 *
 * The four-argument overload is used on purpose: without an image getter AOSP's
 * `HtmlToSpannedConverter.startImg` falls back to `Resources.getSystem().getDrawable(unknown_image)`
 * and calls `setBounds` on it for every `<img>`, i.e. it loads and mutates a framework drawable that
 * is thrown away immediately — on a background thread. The trivial getter below avoids that and
 * keeps the `ImageSpan`'s `source` intact, which is all this parser reads.
 *
 * Messages come from other Mumble clients over the network and are not guaranteed to be well
 * formed or benign: unterminated/nested tags, unquoted or mismatched-quote attributes, missing or
 * empty `src`, non-image `src` schemes (`javascript:`, `file:`, ...), huge bodies and deeply nested
 * markup are all expected input, not error cases. `HtmlCompat.fromHtml` uses a lenient (TagSoup-based)
 * parser that never throws on malformed markup, and this parser never inspects or decodes `src` —
 * it is handed on exactly as sent so a later stage can validate/decode it (see [ChatContent.Image]).
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
