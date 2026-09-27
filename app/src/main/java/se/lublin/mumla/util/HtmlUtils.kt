/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.util

import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import java.io.ByteArrayOutputStream
import java.net.URI

/** A percent escape, `%XX`. */
private const val ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4

object HtmlUtils {
    /** Tries to get the link's hostname, returns null if not a valid URL. */
    fun getHostnameFromLink(link: String): String? {
        if (!link.contains("://")) return null
        return try {
            URI.create(link).host?.takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private val LINK_PATTERN = Regex("(https?://\\S+)")
    private val WHITESPACE = Regex("\\s+")

    /** The text of an HTML fragment, with images dropped and all whitespace collapsed to single spaces. */
    fun toPlainText(html: String): String = collapse(fromHtml(html))

    /**
     * Like [toPlainText], but each link whose text is its own URL is replaced by [shorten] of the
     * URL's host. Links with custom text, or without a host, are kept.
     */
    fun toPlainTextWithShortLinks(html: String, shorten: (host: String) -> String): String {
        val text = SpannableStringBuilder(fromHtml(html))
        text.getSpans(0, text.length, URLSpan::class.java)
            .sortedByDescending { text.getSpanStart(it) }
            .forEach { span ->
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                if (text.substring(start, end) != span.url) return@forEach
                val host = getHostnameFromLink(span.url) ?: return@forEach
                text.replace(start, end, shorten(host))
            }
        return collapse(text)
    }

    private fun fromHtml(html: String) = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)

    // U+FFFC is the placeholder Html.fromHtml leaves for each <img>.
    private fun collapse(text: CharSequence): String =
        text.toString().replace('\uFFFC', ' ').replace(WHITESPACE, " ").trim()

    /**
     * Decodes %XX escapes (UTF-8) and leaves everything else, including '+', untouched.
     * Mumble clients percent-encode base64 image data, which contains '+', so URLDecoder
     * (which turns '+' into a space) must not be used.
     */
    fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        val out = StringBuilder(input.length)
        val pending = ByteArrayOutputStream()
        fun flush() {
            if (pending.size() > 0) {
                out.append(pending.toString("UTF-8"))
                pending.reset()
            }
        }
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val byte = if (c == '%' && i + 2 < input.length) hexByte(input[i + 1], input[i + 2]) else null
            if (byte != null) {
                pending.write(byte)
                i += ESCAPE_LENGTH
            } else {
                flush()
                out.append(c)
                i += 1
            }
        }
        flush()
        return out.toString()
    }

    /** Adds HTML markup to an outgoing message: links become anchors, newlines become `<br>`. */
    fun markupOutgoingMessage(message: String): String =
        LINK_PATTERN.replace(message) { "<a href=\"${it.value}\">${it.value}</a>" }
            .replace("\n", "<br>")

    private fun hexByte(hi: Char, lo: Char): Int? {
        val h = Character.digit(hi, HEX_RADIX)
        val l = Character.digit(lo, HEX_RADIX)
        return if (h < 0 || l < 0) null else (h shl NIBBLE_BITS) or l
    }
}
