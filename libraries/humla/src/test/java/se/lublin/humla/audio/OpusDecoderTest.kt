package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusDecoderApi

class OpusDecoderTest {

    private class FakeOpusDecoder : OpusDecoderApi {
        var destroys = 0
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 11L
        }
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int = frameSize
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int = frameSize
        override fun destroy(state: Long) {
            destroys++
        }
        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = 1
        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = 480
    }

    @Test
    fun `destroy releases the native decoder only once`() {
        val fake = FakeOpusDecoder()
        val decoder = OpusDecoder(48000, 1, fake)

        decoder.destroy()
        decoder.destroy()

        // A second opus_decoder_destroy on the same raw pointer is a native double free.
        assertThat(fake.destroys).isEqualTo(1)
    }
}
