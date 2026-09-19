package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.Celt11Api
import se.lublin.humla.net.PacketBuffer

class CELT11EncoderTest {

    private class FakeCelt11 : Celt11Api {
        var encoderDestroys = 0
        override fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 7L
        }
        override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int = maxBytes
        override fun encoderDestroy(state: Long) {
            encoderDestroys++
        }
        override fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long = 8L
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int = frameSize
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int = frameSize
        override fun decoderDestroy(state: Long) {}
    }

    @Test
    fun `packet is ready after framesPerPacket frames and each frame is emitted at the fixed size`() {
        val encoder = CELT11Encoder(48000, 1, 2, FakeCelt11()) // buffer size = 48000 / 800 = 60 bytes
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isFalse()
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isTrue()

        val pb = PacketBuffer.allocate(128)
        encoder.getEncodedData(pb)
        assertThat(pb.size()).isEqualTo(122)
        pb.rewind()
        val bytes = pb.dataBlock(122)
        assertThat(bytes[0]).isEqualTo(0xBC.toByte())  // 60 | 0x80: more frames follow
        assertThat(bytes[61]).isEqualTo(0x3C.toByte()) // 60: last frame
    }

    @Test
    fun `destroy releases the native encoder only once`() {
        val fake = FakeCelt11()
        val encoder = CELT11Encoder(48000, 1, 2, fake)

        encoder.destroy()
        encoder.destroy()

        // A second celt_encoder_destroy on the same raw pointer is a native double free.
        assertThat(fake.encoderDestroys).isEqualTo(1)
    }
}
