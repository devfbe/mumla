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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownTest {
    private fun html(markdown: String) = Markdown.toHtml(markdown)

    @Test
    fun plainTextStaysAsItIs() {
        assertThat(html("hello world")).isEqualTo("hello world")
        assertThat(html("")).isEqualTo("")
    }

    @Test
    fun boldItalicAndInlineCode() {
        assertThat(html("a **bold** b")).isEqualTo("a <b>bold</b> b")
        assertThat(html("an *italic* word")).isEqualTo("an <i>italic</i> word")
        assertThat(html("***both***")).isEqualTo("<b><i>both</i></b>")
        assertThat(html("run `ls -l` now")).isEqualTo("run <code>ls -l</code> now")
    }

    @Test
    fun unmatchedOrSpacedMarkersAreLiteral() {
        assertThat(html("2 * 3 * 4")).isEqualTo("2 * 3 * 4")
        assertThat(html("**not closed")).isEqualTo("**not closed")
        assertThat(html("snake_case_name")).isEqualTo("snake_case_name")
        assertThat(html("a ` lone backtick")).isEqualTo("a ` lone backtick")
    }

    @Test
    fun markupInsideCodeIsNotInterpreted() {
        assertThat(html("`**x** <b>`")).isEqualTo("<code>**x** &lt;b&gt;</code>")
    }

    @Test
    fun linksAndBareUrls() {
        assertThat(html("[Mumble](https://mumble.info/a?b=1&c=2)"))
            .isEqualTo("<a href=\"https://mumble.info/a?b=1&amp;c=2\">Mumble</a>")
        assertThat(html("see https://a.org/x."))
            .isEqualTo("see <a href=\"https://a.org/x\">https://a.org/x</a>.")
        assertThat(html("(https://a.org/x)")).isEqualTo("(<a href=\"https://a.org/x\">https://a.org/x</a>)")
    }

    @Test
    fun onlyWebLinksBecomeAnchors() {
        assertThat(html("[click](javascript:alert(1))")).isEqualTo("[click](javascript:alert(1))")
        assertThat(html("[x](data:text/html,<script>)")).isEqualTo("[x](data:text/html,&lt;script&gt;)")
    }

    @Test
    fun lineBreaksBecomeBr() {
        assertThat(html("one\ntwo")).isEqualTo("one<br>two")
    }

    @Test
    fun codeBlocksKeepTheirLinesAndDropTheLanguage() {
        assertThat(html("before\n```kotlin\nval a = 1 < 2\n  **b**\n```\nafter"))
            .isEqualTo("before<pre>val a = 1 &lt; 2\n  **b**</pre>after")
        assertThat(html("```one line```")).isEqualTo("<pre>one line</pre>")
        assertThat(html("``` not closed")).isEqualTo("``` not closed")
    }

    @Test
    fun everythingElseIsEscaped() {
        assertThat(html("<script>alert('x')</script>"))
            .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
        assertThat(html("a & b \"c\"")).isEqualTo("a &amp; b &quot;c&quot;")
        assertThat(html("**<img src=x onerror=alert(1)>**"))
            .isEqualTo("<b>&lt;img src=x onerror=alert(1)&gt;</b>")
        assertThat(html("[<b>t</b>](https://a.org/\"onmouseover=\"x)"))
            .isEqualTo("<a href=\"https://a.org/&quot;onmouseover=&quot;x\">&lt;b&gt;t&lt;/b&gt;</a>")
        assertThat(html("https://a.org/<script>"))
            .isEqualTo("<a href=\"https://a.org/\">https://a.org/</a>&lt;script&gt;")
    }
}
