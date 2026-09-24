package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BasicClippingShortMixerTest {
    private class Source(override val samples: FloatArray) : IAudioMixerSource<FloatArray> {
        override val numSamples: Int get() = samples.size
    }

    private val mixer = BasicClippingShortMixer()

    private fun mix(vararg sources: FloatArray, offset: Int = 0): ShortArray {
        val out = ShortArray(sources[0].size + offset)
        mixer.mix(sources.map { Source(it) }, out, offset, sources[0].size)
        return out
    }

    @Test
    fun `mixing is commutative`() {
        val a = floatArrayOf(0.2f, 0.5f, 0.7f)
        val b = floatArrayOf(0.3f, 0.5f, 0.5f)
        val c = floatArrayOf(0.0f, 0.0f, -0.5f)
        assertThat(mix(a, b, c)).isEqualTo(mix(c, b, a))
    }

    @Test
    fun `sums, scales and clips to the short range`() {
        val out = mix(floatArrayOf(0.25f, 0.8f, -0.8f, 0f), floatArrayOf(0.25f, 0.8f, -0.8f, 0f))
        assertThat(out).isEqualTo(shortArrayOf(16383, Short.MAX_VALUE, (-Short.MAX_VALUE).toShort(), 0))
    }

    @Test
    fun `writes at the offset`() {
        assertThat(mix(floatArrayOf(1f), offset = 2)).isEqualTo(shortArrayOf(0, 0, Short.MAX_VALUE))
    }
}
