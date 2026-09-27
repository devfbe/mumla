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

package se.lublin.mumla.log

/**
 * Strips what a shared log must not carry: passwords, access tokens and other secrets, the
 * password in a URL, and certificate or key material. Errs towards removing too much.
 */
object LogRedaction {
    private const val REDACTED = "<redacted>"

    private val pem = Regex("-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?(-----END [A-Z0-9 ]+-----|\\z)")

    /** `mumble://user:secret@host`: the password between the colon and the at sign. */
    private val urlPassword = Regex("(\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s/:@]*):[^\\s/@]*@")

    /** `password=…`, `accessTokens: [a, b]`, `"secret": "…"`, in any case, also as part of a longer name. */
    private val keyValue = Regex(
        "(?i)([\\w-]*(?:password|passwd|passphrase|token|secret|credential)[\\w-]*\"?\\s*[=:]\\s*)" +
            "(\\[[^\\]]*]|\"[^\"]*\"|'[^']*'|[^\\s,;)}\\]]+)",
    )

    /** Certificate, key or other binary data encoded as base64 or hex. */
    private val blob = Regex("[A-Za-z0-9+/]{64,}={0,2}|\\b[0-9a-fA-F]{128,}\\b")

    fun redact(text: String): String = text
        .replace(pem, REDACTED)
        .replace(urlPassword, "$1:$REDACTED@")
        .replace(keyValue, "$1$REDACTED")
        .replace(blob, REDACTED)
}
