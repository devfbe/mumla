package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FitToBandwidthTest {
    @Test
    fun `a stream within the limit is left alone`() {
        assertThat(fitToBandwidth(40_000, 2, 72_000)).isEqualTo(40_000 to 2)
    }

    @Test
    fun `below 32 kbps packets carry four frames and the bitrate drops until it fits`() {
        assertThat(fitToBandwidth(40_000, 1, 32_000)).isEqualTo(21_000 to 4)
    }

    @Test
    fun `single frames become two below 64 kbps`() {
        assertThat(fitToBandwidth(40_000, 1, 64_000)).isEqualTo(40_000 to 2)
    }

    @Test
    fun `the bitrate never drops below 8 kbps`() {
        assertThat(fitToBandwidth(40_000, 1, 1_000)).isEqualTo(8_000 to 4)
    }
}
