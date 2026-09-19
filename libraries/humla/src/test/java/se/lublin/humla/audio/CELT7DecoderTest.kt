package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.Celt7Api

class CELT7DecoderTest {

    private class FakeCelt7 : Celt7Api {
        var decoderDestroys = 0
        var modeDestroys = 0
        override fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long {
            error?.set(0, 0)
            return 1L
        }
        override fun modeInfo(mode: Long, request: Int, value: IntArray): Int = 0
        override fun modeDestroy(mode: Long) {
            modeDestroys++
        }
        override fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long = 2L
        override fun encoderCtlInt(state: Long, request: Int, value: Int): Int = 0
        override fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int = 0
        override fun encoderDestroy(state: Long) {}
        override fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 3L
        }
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int = 0
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int = 0
        override fun decoderDestroy(state: Long) {
            decoderDestroys++
        }
    }

    @Test
    fun `destroy releases the native decoder and mode only once`() {
        val fake = FakeCelt7()
        val decoder = CELT7Decoder(48000, 480, 1, fake)

        decoder.destroy()
        decoder.destroy()

        // A second celt_decoder_destroy/celt_mode_destroy on the same raw pointer is a native
        // double free, not a Java-side exception.
        assertThat(fake.decoderDestroys).isEqualTo(1)
        assertThat(fake.modeDestroys).isEqualTo(1)
    }
}
