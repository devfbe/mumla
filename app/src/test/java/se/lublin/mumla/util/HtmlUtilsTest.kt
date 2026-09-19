package se.lublin.mumla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlUtilsTest {

    @Test
    fun hostnameFromHttpsLink() {
        assertThat(HtmlUtils.getHostnameFromLink("https://example.org/path?q=1")).isEqualTo("example.org")
    }

    @Test
    fun hostnameIsNullForPlainText() {
        assertThat(HtmlUtils.getHostnameFromLink("not a link")).isNull()
    }

    @Test
    fun hostnameIsNullForMalformedUri() {
        assertThat(HtmlUtils.getHostnameFromLink("http://exa mple.org/")).isNull()
    }
}
