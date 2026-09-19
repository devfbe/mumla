package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.Celt7Api
import se.lublin.humla.net.PacketBuffer

class CELT7EncoderTest {

    private class FakeCelt7 : Celt7Api {
        var encoderDestroys = 0
        var modeDestroys = 0
        override fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long {
            error?.set(0, 0)
            return 1L
        }
        override fun modeInfo(mode: Long, request: Int, value: IntArray): Int {
            value[0] = 0
            return 0
        }
        override fun modeDestroy(mode: Long) {
            modeDestroys++
        }
        override fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 2L
        }
        override fun encoderCtlInt(state: Long, request: Int, value: Int): Int = 0
        override fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int {
            for (i in 0 until 5) out[i] = (i + 1).toByte()
            return 5
        }
        override fun encoderDestroy(state: Long) {
            encoderDestroys++
        }
        override fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long = 3L
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int = 0
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int = 0
        override fun decoderDestroy(state: Long) {}
    }

    @Test
    fun `frames are chained with the continuation bit and their real lengths`() {
        val encoder = CELT7Encoder(48000, 480, 1, 2, 40000, 1024, FakeCelt7())
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isFalse()
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isTrue()

        val pb = PacketBuffer.allocate(32)
        encoder.getEncodedData(pb)
        pb.rewind()
        assertThat(pb.dataBlock(12)).isEqualTo(byteArrayOf(0x85.toByte(), 1, 2, 3, 4, 5, 0x05, 1, 2, 3, 4, 5))
        assertThat(encoder.isReady()).isFalse()
    }

    @Test
    fun `destroy releases the native encoder and mode only once`() {
        val fake = FakeCelt7()
        val encoder = CELT7Encoder(48000, 480, 1, 2, 40000, 1024, fake)

        encoder.destroy()
        encoder.destroy()

        // A second celt_encoder_destroy/celt_mode_destroy on the same raw pointer is a native
        // double free, not a Java-side exception.
        assertThat(fake.encoderDestroys).isEqualTo(1)
        assertThat(fake.modeDestroys).isEqualTo(1)
    }
}
