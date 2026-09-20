package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.management.ThreadMXBean
import org.junit.Test
import java.lang.management.ManagementFactory

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

    @Test
    fun caseInsensitivePrefixMatchingFoldsSomeUnicodeOntoAscii() {
        // '\u017F' (long s) uppercases to 'S', so "httpſ://" matches the "https://" prefix and is
        // classified Remote. Documented, not a hole: HttpImageFetcher checks the scheme exactly and
        // refuses it (see HttpImageFetcherTest.unicodeCaseFoldingOfTheSchemeIsCaughtHere).
        assertThat(ImageSource.parse("http\u017F://evil.example/a.png"))
            .isEqualTo(ImageSource.Remote("http\u017F://evil.example/a.png"))
    }

    @Test
    fun lookalikeCharactersThatDoNotCaseFoldAreUnsupported() {
        // Fullwidth latin, Cyrillic and Greek lookalikes are simply different characters.
        assertThat(ImageSource.parse("\uFF48\uFF54\uFF54\uFF50://evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("http\u0455://evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("\u0440ttp://evil.example/a.png")).isEqualTo(ImageSource.Unsupported)
        assertThat(ImageSource.parse("d\u0430ta:image/png;base64,YQ==")).isEqualTo(ImageSource.Unsupported)
    }

    @Test
    fun authoritiesWithoutAHostStayRemoteAndAreTheFetchersProblem() {
        assertThat(ImageSource.parse("http://@:8080/a.png")).isEqualTo(ImageSource.Remote("http://@:8080/a.png"))
        assertThat(ImageSource.parse("http://user:pass@/a.png")).isEqualTo(ImageSource.Remote("http://user:pass@/a.png"))
    }

    // --- The length cap. It exists to stop an allocation, not to name an error, so what it has to
    // --- prevent lives inside this parser and nowhere else.

    /**
     * `HtmlUtils.percentDecode` builds a `StringBuilder` of the input's length and then a
     * `toString()` of it — two more full-size copies — and `Base64.getMimeDecoder().decode`
     * materialises the whole payload as a byte array. Those three allocations are the reason the cap
     * exists. A test that only reads the returned value cannot tell a refusal that skipped all of
     * them from one that did them all and threw the result away: both say `TooLarge`. So this
     * measures what the JVM really allocated on this thread. Moving the cap below the decode leaves
     * the return value untouched and this assertion red, which is the whole point of writing it this
     * way.
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
        // A tenth of a mebibyte, not "less than the source": the refusing path allocates nothing but
        // the measurement's own boxing and whatever the first call on this thread loads, measured at
        // 6_792 B. Anything that copies, decodes or even trims the source is orders of magnitude
        // above this, and a bound of one source length would have let the whole decode through.
        assertWithMessage("bytes allocated by parse() for a %s character source", source.length)
            .that(allocated).isLessThan(100L * 1024)
    }

    /**
     * What one [ImageSource.parse] of a maximal source really costs, and what that multiplies out to.
     *
     * The cap is not a number about one call: [ChatImageLoader] lets
     * [ChatImageLoader.DEFAULT_MAX_CONCURRENT_LOADS] loads run at once and its `fetchBytes` share
     * path takes no permit at all, so the worst case is **four** maximal sources being parsed at the
     * same instant, each by a different row of the chat log. Three of them holding a permit and the
     * fourth sharing is a state an ordinary chat log reaches, not a contrived one.
     *
     * 48 MiB is the written-down budget for that worst case. On the smallest heap an Android 12
     * device realistically hands out — a 128 MiB `dalvik.vm.heapgrowthlimit` — the thumbnail cache
     * has already taken `maxMemory() / 8` = 16 MiB, so 48 MiB leaves 64 MiB for the rest of the app.
     * At the cap this stream shipped first (7_000_000 characters) the same four parses came to
     * 133 MB, i.e. more than the whole heap, and three of them at once needed an `-Xmx` of 160 MiB
     * before they completed at all.
     *
     * The percent-encoded form is the expensive one and therefore the one measured: `percentDecode`
     * returns its input unchanged when there is no `%` in it, so a plain `data:` URI costs 2.75 bytes
     * per character and this one costs 4.75.
     */
    @Test
    fun fourMaximalSourcesParsedAtOnceFitTheMemoryBudget() {
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
        // Measured: 10_137_896 B for the first parse on a thread, 9_961_760 B once warm.
        assertWithMessage("bytes allocated by one parse() of a %s character source", atCap.length)
            .that(perParse).isLessThan(11L * 1024 * 1024)
        val concurrent = ChatImageLoader.DEFAULT_MAX_CONCURRENT_LOADS + 1 // + the ungated share path
        assertWithMessage("bytes allocated by %s concurrent parses at the cap", concurrent)
            .that(perParse * concurrent).isLessThan(48L * 1024 * 1024)
    }

    /**
     * The cap is a fact about Mumble servers, not a round number.
     *
     * Murmur's own default for `imagemessagelength` is 1_048_576, set in `src/murmur/Meta.cpp`
     * (`MetaParams::MetaParams`: `iMaxImageMessageLength = 1048576;`) and overridden from the ini by
     * `typeCheckedFromSettings("imagemessagelength", iMaxImageMessageLength)`. It bounds the **whole
     * message**: `Server::isTextAllowed` in `src/murmur/Server.cpp` compares it against
     * `text.length()`, i.e. the UTF-16 length of the entire HTML, markup and every `<img src>`
     * together. One `src` can therefore never be longer than that on a default server, whatever it
     * is spelled like. The factor two is the headroom for a server that raised the setting.
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
