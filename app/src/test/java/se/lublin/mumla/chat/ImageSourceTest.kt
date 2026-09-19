package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageSourceTest {

    @Test
    fun parsesPercentEncodedDataUri() {
        val source = ImageSource.parse("data:image/jpeg;base64,%2F9g%3D") as ImageSource.Data
        assertThat(source.bytes).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    }

    @Test
    fun parsesPlainDataUri() {
        val source = ImageSource.parse("data:image/jpeg;base64,/9g=") as ImageSource.Data
        assertThat(source.bytes).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    }

    @Test
    fun httpAndHttpsAreRemote() {
        assertThat(ImageSource.parse("https://Example.org/a.png")).isEqualTo(ImageSource.Remote("https://Example.org/a.png"))
        assertThat(ImageSource.parse(" http://x/a%20b.png ")).isEqualTo(ImageSource.Remote("http://x/a%20b.png"))
        assertThat(ImageSource.parse("https://x/a%2Fb.png")).isEqualTo(ImageSource.Remote("https://x/a%2Fb.png"))
    }

    @Test
    fun dataUriWithoutBase64IsUnsupported() {
        assertThat(ImageSource.parse("data:image/png,abc")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("data:image/png;base64")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun otherSchemesAreUnsupported() {
        assertThat(ImageSource.parse("file:///etc/passwd")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("javascript:alert(1)")).isEqualTo(ImageSource.Unsupported)
    }

    // --- Hostile input. The parser (Task 2) hands the raw src attribute through byte for byte and
    // --- deliberately does not check the scheme, so every dangerous source has to die here.

    @Test
    fun schemeMatchingIsCaseInsensitiveForTheSupportedSchemes() {
        assertThat(ImageSource.parse("HTTPS://Example.org/a.png"))
            .isEqualTo(ImageSource.Remote("HTTPS://Example.org/a.png"))
        assertThat(ImageSource.parse("HtTp://x/a.png")).isEqualTo(ImageSource.Remote("HtTp://x/a.png"))
        val data = ImageSource.parse("DaTa:ImAgE/jpeg;BASE64,/9g=") as ImageSource.Data
        assertThat(data.bytes).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    }

    @Test
    fun caseVariantsOfDangerousSchemesAreUnsupported() {
        assertThat(ImageSource.parse("FILE:///etc/passwd")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("JavaScript:alert(1)")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("JAVASCRIPT:alert(1)")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("Content://settings/secure")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("FTP://x/a.png")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun surroundingWhitespaceDoesNotSmuggleAScheme() {
        assertThat(ImageSource.parse("  \t\n file:///etc/passwd \r\n ")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("\n javascript:alert(1)")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("\u000B\u000Cjavascript:alert(1)")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun controlCharactersDoNotSmuggleAScheme() {
        // NUL and friends are not whitespace, so they are not trimmed and the prefix never matches.
        assertThat(ImageSource.parse("\u0000javascript:alert(1)")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("java\u0000script:alert(1)")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("ht\ttp://x/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("ht\u0000tp://x/a.png")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun controlCharactersInsideARemoteUrlSurviveToTheFetcherWhichRejectsThem() {
        // parse() is deliberately not a URL validator: it classifies. Anything that is syntactically
        // not a URL (embedded CR/LF, NUL, spaces) is rejected by HttpImageFetcher, see its test.
        assertThat(ImageSource.parse("http://x/a\u0000b.png")).isEqualTo(ImageSource.Remote("http://x/a\u0000b.png"))
        assertThat(ImageSource.parse("http://x/a\r\nHost:evil/b.png"))
            .isEqualTo(ImageSource.Remote("http://x/a\r\nHost:evil/b.png"))
    }

    @Test
    fun schemeRelativeUrlIsUnsupported() {
        // There is no base URL for a chat message, so "//host/a.png" cannot be resolved safely.
        assertThat(ImageSource.parse("//evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("http:/x/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("httpx://x/a.png")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun nonImageDataUriIsUnsupported() {
        assertThat(ImageSource.parse("data:text/html;base64,PGI+aGk8L2I+")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("data:application/javascript;base64,YWxlcnQoMSk=")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("data:;base64,YQ==")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("data:,hello")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun invalidBase64PayloadIsUnsupported() {
        // A dangling unit cannot be decoded at all.
        assertThat(ImageSource.parse("data:image/png;base64,Q")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("data:image/png;base64,/9g=Q")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun truncatedButDecodableBase64YieldsThoseBytes() {
        // Truncation that still forms whole base64 units decodes to a short, non-image byte array.
        // Rejecting it is the bitmap decoder's job (MALFORMED), not this classifier's.
        val source = ImageSource.parse("data:image/jpeg;base64,/9") as ImageSource.Data
        assertThat(source.bytes).isEqualTo(byteArrayOf(0xFF.toByte()))
    }

    @Test
    fun garbageOrEmptyBase64PayloadYieldsNoBytes() {
        // The MIME decoder skips characters outside the base64 alphabet; nothing is left.
        assertThat((ImageSource.parse("data:image/png;base64,!!!!") as ImageSource.Data).bytes).isEmpty()
        assertThat((ImageSource.parse("data:image/png;base64,") as ImageSource.Data).bytes).isEmpty()
    }

    @Test
    fun credentialsAndNonStandardPortStayRemoteVerbatim() {
        assertThat(ImageSource.parse("http://user:pass@example.org/a.png"))
            .isEqualTo(ImageSource.Remote("http://user:pass@example.org/a.png"))
        assertThat(ImageSource.parse("https://example.org:8443/a.png"))
            .isEqualTo(ImageSource.Remote("https://example.org:8443/a.png"))
        assertThat(ImageSource.parse("http://example.org:0/a.png"))
            .isEqualTo(ImageSource.Remote("http://example.org:0/a.png"))
    }

    @Test
    fun emptyAndWhitespaceOnlySourcesAreUnsupported() {
        assertThat(ImageSource.parse("")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("   \t\n ")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun percentEncodingCannotTurnAForeignSchemeIntoASupportedOne() {
        // Percent decoding runs only after the data:image prefix matched, so it can never
        // promote another scheme, and a remote URL is never decoded at all.
        assertThat(ImageSource.parse("%64ata:image/png;base64,YQ==")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("%66ile:///etc/passwd")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("https://x/%2E%2E/a.png")).isEqualTo(ImageSource.Remote("https://x/%2E%2E/a.png"))
    }
}
