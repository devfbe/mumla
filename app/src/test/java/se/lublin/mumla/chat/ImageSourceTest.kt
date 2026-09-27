package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.management.ThreadMXBean
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.lang.management.ManagementFactory

class ImageSourceTest {

    private fun remote(url: String) = ImageSource.Remote(url.toHttpUrl())

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
        assertThat(ImageSource.parse("https://Example.org/a.png")).isEqualTo(remote("https://Example.org/a.png"))
        assertThat(ImageSource.parse(" http://x/a%20b.png ")).isEqualTo(remote("http://x/a%20b.png"))
        assertThat(ImageSource.parse("https://x/a%2Fb.png")).isEqualTo(remote("https://x/a%2Fb.png"))
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

    // Hostile input: the parser hands the raw src attribute through unchecked, so every dangerous
    // source has to die here.

    @Test
    fun schemeMatchingIsCaseInsensitiveForTheSupportedSchemes() {
        assertThat(ImageSource.parse("HTTPS://Example.org/a.png"))
            .isEqualTo(remote("HTTPS://Example.org/a.png"))
        assertThat(ImageSource.parse("HtTp://x/a.png")).isEqualTo(remote("HtTp://x/a.png"))
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
    fun controlCharactersInsideARemoteUrlAreEscapedAndCannotReachARequestLine() {
        val nul = ImageSource.parse("http://x/a\u0000b.png") as ImageSource.Remote
        assertThat(nul.url.encodedPath).isEqualTo("/a%00b.png")
        val crlf = ImageSource.parse("http://x/a\r\nHost:evil/b.png") as ImageSource.Remote
        assertThat(crlf.url.host).isEqualTo("x")
        assertThat(crlf.url.toString()).doesNotContain("\r")
        assertThat(crlf.url.toString()).doesNotContain("\n")
    }

    @Test
    fun schemeRelativeUrlIsUnsupported() {
        // There is no base URL for a chat message, so "//host/a.png" cannot be resolved safely.
        assertThat(ImageSource.parse("//evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("a.png")).isEqualTo(ImageSource.Unsupported)
        // A missing slash is repaired as browsers do; the host is still explicit.
        assertThat(ImageSource.parse("http:/x/a.png")).isEqualTo(remote("http://x/a.png"))
        assertThat(ImageSource.parse("httpx://x/a.png")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun nonImageDataUriIsUnsupported() {
        assertThat(ImageSource.parse("data:text/html;base64,PGI+aGk8L2I+")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("data:application/javascript;base64,YWxlcnQoMSk="))
            .isEqualTo(ImageSource.Unsupported)
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
        // Truncation forming whole base64 units decodes to short non-image bytes; the bitmap
        // decoder rejects those (MALFORMED).
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
    fun credentialsAndNonStandardPortsStayRemoteButPortZeroIsNoUrl() {
        assertThat(ImageSource.parse("http://user:pass@example.org/a.png"))
            .isEqualTo(remote("http://user:pass@example.org/a.png"))
        assertThat(ImageSource.parse("https://example.org:8443/a.png"))
            .isEqualTo(remote("https://example.org:8443/a.png"))
        assertThat(ImageSource.parse("http://example.org:0/a.png")).isEqualTo(ImageSource.Unsupported)
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
        assertThat(ImageSource.parse("https://x/%2E%2E/a.png")).isEqualTo(remote("https://x/%2E%2E/a.png"))
    }

    @Test
    fun unicodeThatCaseFoldsOntoAsciiIsNoScheme() {
        // 'ſ' (long s) uppercases to 'S'; "httpſ://" is still not https.
        assertThat(ImageSource.parse("http\u017F://evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun lookalikeCharactersThatDoNotCaseFoldAreUnsupported() {
        // Fullwidth latin, Cyrillic and Greek lookalikes are simply different characters.
        assertThat(ImageSource.parse("\uFF48\uFF54\uFF54\uFF50://evil.example/a.png"))
            .isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("http\u0455://evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("\u0440ttp://evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("d\u0430ta:image/png;base64,YQ==")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun authoritiesWithoutAHostAreUnsupported() {
        assertThat(ImageSource.parse("http://@:8080/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("http://user:pass@/a.png")).isEqualTo(ImageSource.Unsupported)
    }

    // The length cap exists to stop allocations inside this parser.

    /**
     * `percentDecode` and the MIME decoder make three full-size copies of the payload; a refusal
     * must skip all of them. The return value cannot tell, so the thread's allocations are measured.
     */
    @Test
    fun anOversizedSourceIsRefusedWithoutAllocatingACopyOfIt() {
        // The '%' is what makes percentDecode do its work instead of returning the input unchanged.
        val source = "data:image/png;base64,%41" + "A".repeat(ImageSource.MAX_SOURCE_LENGTH)
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertWithMessage("this JVM must account per-thread allocation for the measurement below")
            .that(threads.isThreadAllocatedMemoryEnabled).isTrue()

        val before = threads.currentThreadAllocatedBytes
        val parsed = ImageSource.parse(source)
        val allocated = threads.currentThreadAllocatedBytes - before

        assertThat(parsed).isEqualTo(ImageSource.TooLarge)
        // A tenth of a mebibyte: the refusing path allocates almost nothing (about 7 KB), while any
        // copy, decode or trim of the source is orders of magnitude above this.
        assertWithMessage("bytes allocated by parse() for a %s character source", source.length)
            .that(allocated).isLessThan(100L * 1024)
    }

    /**
     * One parse of a maximal source, times the worst-case concurrency: [ChatImageLoader] runs
     * [ChatImageLoader.MAX_CONCURRENT_LOADS] loads at once plus the ungated `fetchBytes`
     * share path. The budget is 48 MiB, which leaves 64 MiB of a 128 MiB `heapgrowthlimit` after
     * the 16 MiB thumbnail cache. The percent-encoded form is the expensive one (4.75 bytes per
     * character instead of 2.75).
     */
    @Test
    fun theMaximalSourcesParsedAtOnceFitTheMemoryBudget() {
        val head = "data:image/png;base64,%41"
        val atCap = head + "A".repeat(ImageSource.MAX_SOURCE_LENGTH - head.length)
        assertThat(atCap.length).isEqualTo(ImageSource.MAX_SOURCE_LENGTH)
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertWithMessage("this JVM must account per-thread allocation for the measurement below")
            .that(threads.isThreadAllocatedMemoryEnabled).isTrue()

        val before = threads.currentThreadAllocatedBytes
        val parsed = ImageSource.parse(atCap)
        val perParse = threads.currentThreadAllocatedBytes - before

        assertThat(parsed).isInstanceOf(ImageSource.Data::class.java)
        // About 10 MB per parse.
        assertWithMessage("bytes allocated by one parse() of a %s character source", atCap.length)
            .that(perParse).isLessThan(11L * 1024 * 1024)
        val concurrent = ChatImageLoader.MAX_CONCURRENT_LOADS + 1 // + the ungated share path
        assertWithMessage("bytes allocated by %s concurrent parses at the cap", concurrent)
            .that(perParse * concurrent).isLessThan(48L * 1024 * 1024)
    }

    /**
     * Murmur's default `imagemessagelength` is 1_048_576 and bounds the UTF-16 length of the whole
     * message (`Server::isTextAllowed`), so one `src` can never be longer on a default server. The
     * factor two is headroom for a server that raised the setting.
     */
    @Test
    fun theCapIsTwiceWhatADefaultMurmurWillCarryInOneWholeMessage() {
        val murmurDefaultImageMessageLength = 1_048_576
        assertThat(ImageSource.MAX_SOURCE_LENGTH).isEqualTo(2 * murmurDefaultImageMessageLength)
    }

    /** The cap itself, from both sides, so it cannot drift by one in either direction. */
    @Test
    fun aSourceExactlyAtTheLengthLimitIsStillParsed() {
        val head = "data:image/png;base64,"
        val atLimit = head + "A".repeat(ImageSource.MAX_SOURCE_LENGTH - head.length)
        assertThat(atLimit.length).isEqualTo(ImageSource.MAX_SOURCE_LENGTH)
        assertThat(ImageSource.parse(atLimit)).isInstanceOf(ImageSource.Data::class.java)
        assertThat(ImageSource.parse(atLimit + "A")).isEqualTo(ImageSource.TooLarge)
    }

    /** A remote URL is not decoded at all, but it is still a string the cap has an opinion about. */
    @Test
    fun anOversizedRemoteUrlIsTooLargeRatherThanRemote() {
        assertThat(ImageSource.parse("https://x.invalid/" + "a".repeat(ImageSource.MAX_SOURCE_LENGTH)))
            .isEqualTo(ImageSource.TooLarge)
    }
}
