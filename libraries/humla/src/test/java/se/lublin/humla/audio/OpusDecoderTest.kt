package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.testutil.FakeOpusDecoder

class OpusDecoderTest {
    @Test
    fun `destroy releases the native decoder only once`() {
        val fake = FakeOpusDecoder()
        val decoder = OpusDecoder(48000, 1, fake)

        decoder.close()
        decoder.close()

        // A second opus_decoder_destroy on the same raw pointer is a native double free.
        assertThat(fake.destroys).isEqualTo(1)
    }
}
