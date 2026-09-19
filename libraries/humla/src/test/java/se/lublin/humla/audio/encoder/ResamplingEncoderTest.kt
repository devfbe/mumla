package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexResamplerApi

class ResamplingEncoderTest {

    /** Pretends to upsample by 3: out[i] = input[i / 3]. */
    private class FakeResampler : SpeexResamplerApi {
        var initArgs: List<Int>? = null
        override fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long {
            initArgs = listOf(channels, inRate, outRate, quality)
            return 9L
        }
        override fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int {
            val n = minOf(outLen[0], inLen[0] * 3)
            for (i in 0 until n) out[i] = input[i / 3]
            inLen[0] = n / 3
            outLen[0] = n
            return 0
        }
        override fun destroy(state: Long) {}
    }

    @Test
    fun `input frames are resampled into the target frame size before encoding`() {
        val inner = RecordingEncoder()
        val fake = FakeResampler()
        val encoder = ResamplingEncoder(inner, 1, 16000, 480, 48000, fake)

        encoder.encode(ShortArray(160) { it.toShort() }, 160)

        assertThat(fake.initArgs).containsExactly(1, 16000, 48000, 3).inOrder()
        val received = inner.received.single()
        assertThat(received.size).isEqualTo(480)
        assertThat(received[0]).isEqualTo(0.toShort())
        assertThat(received[479]).isEqualTo(159.toShort())
        assertThat(inner.receivedSizes).containsExactly(480)
    }
}
