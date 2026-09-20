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

import java.io.ByteArrayOutputStream
import java.net.URI

object HtmlUtils {
    /** Tries to get the link's hostname, returns null if not a valid URL. */
    @JvmStatic
    fun getHostnameFromLink(link: String): String? {
        if (!link.contains("://")) return null
        return try {
            URI.create(link).host?.takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private val LINK_PATTERN = Regex("(https?://\\S+)")

    /**
     * Decodes %XX escapes (UTF-8) and leaves everything else, including '+', untouched.
     * Mumble clients percent-encode base64 image data, which contains '+', so URLDecoder
     * (which turns '+' into a space) must not be used.
     */
    @JvmStatic
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
                i += 3
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
    @JvmStatic
    fun markupOutgoingMessage(message: String): String =
        LINK_PATTERN.replace(message) { "<a href=\"${it.value}\">${it.value}</a>" }
            .replace("\n", "<br>")

    private fun hexByte(hi: Char, lo: Char): Int? {
        val h = Character.digit(hi, 16)
        val l = Character.digit(lo, 16)
        return if (h < 0 || l < 0) null else (h shl 4) or l
    }
}
