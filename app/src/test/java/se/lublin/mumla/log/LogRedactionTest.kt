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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogRedactionTest {
    private fun redacted(text: String) = LogRedaction.redact(text)

    @Test
    fun secretsInKeyValuePairsAreRemoved() {
        mapOf(
            "Server(host=a.org, password=hunter2, port=0)" to "Server(host=a.org, password=<redacted>, port=0)",
            "keystorePassword: s3cret" to "keystorePassword: <redacted>",
            "PASSWORD=\"with spaces\"" to "PASSWORD=<redacted>",
            "accessTokens=[alpha, beta] rest" to "accessTokens=<redacted> rest",
            "{\"secret\": \"x\"}" to "{\"secret\": <redacted>}",
            "token='abc';" to "token=<redacted>;",
        ).forEach { (input, expected) -> assertThat(redacted(input)).isEqualTo(expected) }
    }

    @Test
    fun thePasswordOfAUrlIsRemovedButNotItsUserOrHost() {
        assertThat(redacted("Could not parse mumble://alice:hunter2@example.org:64738/Lobby"))
            .isEqualTo("Could not parse mumble://alice:<redacted>@example.org:64738/Lobby")
    }

    @Test
    fun pemBlocksAreRemovedWhole() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\nabc\n-----END CERTIFICATE-----"

        assertThat(redacted("cert: $pem done")).isEqualTo("cert: <redacted> done")
        assertThat(redacted("-----BEGIN PRIVATE KEY-----\ntruncated")).isEqualTo("<redacted>")
    }

    @Test
    fun longBase64AndHexBlobsAreRemoved() {
        val base64 = "MIIDdzCCAl+gAwIBAgIEb3S9ZjANBgkqhkiG9w0BAQsFADBsMRAwDgYDVQQGEwdVbmtub3du=="
        val hex = "ab".repeat(64)

        assertThat(redacted("data $base64 end")).isEqualTo("data <redacted> end")
        assertThat(redacted("data $hex end")).isEqualTo("data <redacted> end")
    }

    @Test
    fun ordinaryLinesAreLeftAlone() {
        val lines = listOf(
            "Connected to example.org:64738 over UDP",
            "SHA-1 fingerprint 3f:2a:9c",
            "Tokens view opened",
            "capturing at 48000 Hz in frames of 480",
        )
        lines.forEach { assertThat(redacted(it)).isEqualTo(it) }
    }
}
