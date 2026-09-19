package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.Celt11Api

class CELT11DecoderTest {

    private class FakeCelt11 : Celt11Api {
        var decoderDestroys = 0
        override fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long = 7L
        override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int = maxBytes
        override fun encoderDestroy(state: Long) {}
        override fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 8L
        }
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int = frameSize
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int = frameSize
        override fun decoderDestroy(state: Long) {
            decoderDestroys++
        }
    }

    @Test
    fun `destroy releases the native decoder only once`() {
        val fake = FakeCelt11()
        val decoder = CELT11Decoder(48000, 1, fake)

        decoder.destroy()
        decoder.destroy()

        // A second celt_decoder_destroy on the same raw pointer is a native double free.
        assertThat(fake.decoderDestroys).isEqualTo(1)
    }
}
