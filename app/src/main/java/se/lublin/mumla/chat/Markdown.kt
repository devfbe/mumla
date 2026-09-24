/*
 * Copyright (C) 2026 The Mumla authors
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
package se.lublin.mumla.chat

import se.lublin.mumla.util.HtmlUtils

/**
 * Turns a small Markdown subset into the HTML Mumble clients render: `**bold**`, `*italic*`,
 * `` `code` ``, fenced code blocks, `[text](url)` and bare URLs (http and https only) and line
 * breaks. Every other character that means something in HTML is escaped, so the result never
 * contains markup the user did not ask for.
 */
object Markdown {
    private val CODE_BLOCK = Regex("```([\\s\\S]*?)```")
    private val LANGUAGE = Regex("[\\w+#.-]*")
    private val INLINE = Regex(
        "`(?<code>[^`\\n]+)`" +
            "|\\[(?<text>[^\\]\\n]+)]\\((?<href>https?://[^\\s)]+)\\)" +
            "|(?<url>https?://[^\\s<>\"]+)" +
            "|\\*\\*(?=\\S)(?<bold>.+?\\*?)(?<=\\S)\\*\\*(?!\\*)" +
            "|\\*(?=\\S)(?<italic>[^*\\n]+?)(?<=\\S)\\*"
    )
    private const val TRAILING_PUNCTUATION = ".,;:!?'"

    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        var last = 0
        for (block in CODE_BLOCK.findAll(markdown)) {
            inline(markdown.substring(last, block.range.first).removeSuffix("\n"), out)
            out.append("<pre>").append(escape(codeBlockContent(block.groupValues[1]))).append("</pre>")
            last = block.range.last + 1
            if (markdown.startsWith("\n", last)) last++
        }
        inline(markdown.substring(last), out)
        return out.toString()
    }

    /** The block's lines without a leading language name and the newlines around them. */
    private fun codeBlockContent(raw: String): String {
        val firstBreak = raw.indexOf('\n')
        val body = if (firstBreak >= 0 && LANGUAGE.matches(raw.substring(0, firstBreak))) {
            raw.substring(firstBreak + 1)
        } else {
            raw
        }
        return body.removeSuffix("\n")
    }

    private fun inline(text: String, out: StringBuilder) {
        var last = 0
        for (match in INLINE.findAll(text)) {
            literal(text.substring(last, match.range.first), out)
            last = match.range.last + 1
            val groups = match.groups
            when {
                groups["code"] != null -> out.append("<code>").append(escape(groups["code"]!!.value)).append("</code>")
                groups["href"] != null -> link(groups["href"]!!.value, escape(groups["text"]!!.value), out)
                groups["url"] != null -> {
                    val url = trimUrl(groups["url"]!!.value)
                    link(url, escape(url), out)
                    // What the URL gave back is ordinary text again.
                    last = match.range.first + url.length
                }
                groups["bold"] != null -> {
                    out.append("<b>")
                    inline(groups["bold"]!!.value, out)
                    out.append("</b>")
                }
                else -> {
                    out.append("<i>")
                    inline(groups["italic"]!!.value, out)
                    out.append("</i>")
                }
            }
        }
        literal(text.substring(last), out)
    }

    private fun link(href: String, html: String, out: StringBuilder) {
        out.append("<a href=\"").append(escape(href)).append("\">").append(html).append("</a>")
    }

    /** Drops sentence punctuation and an unbalanced closing parenthesis from a bare URL's end. */
    private fun trimUrl(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val unbalanced = c == ')' && url.take(end).count { it == ')' } > url.take(end).count { it == '(' }
            if (c in TRAILING_PUNCTUATION || unbalanced) end-- else break
        }
        return url.substring(0, end)
    }

    private fun literal(text: String, out: StringBuilder) {
        out.append(escape(text).replace("\n", "<br>"))
    }

    private fun escape(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

/** The HTML to send for [message] as the user typed it, formatted as Markdown when [markdown] is set. */
fun outgoingMessageHtml(message: String, markdown: Boolean): String =
    if (markdown) Markdown.toHtml(message) else HtmlUtils.markupOutgoingMessage(message)
