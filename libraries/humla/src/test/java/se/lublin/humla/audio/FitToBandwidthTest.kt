package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FitToBandwidthTest {
    @Test
    fun `the bandwidth includes the overhead of every packet`() {
        // overhead per packet = 20+8+4+1+2+12+framesPerPacket bytes, 800/framesPerPacket packets per second
        assertThat(audioBandwidth(40_000, 2)).isEqualTo(59_600)
        assertThat(audioBandwidth(40_000, 1)).isEqualTo(78_400)
        assertThat(audioBandwidth(40_000, 4)).isEqualTo(50_200)
    }

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
