package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.audio.native.SpeexDecoderApi
import se.lublin.humla.exception.NativeAudioException

class SpeexDecoderTest {

    private class FakeSpeex(
        private val samples: FloatArray = FloatArray(0),
        private val createResult: Long = 1L,
    ) : SpeexDecoderApi {
        var destroys = 0
        override fun create(modeId: Int): Long = createResult
        override fun ctlInt(handle: Long, request: Int, value: Int): Int = 0
        override fun decodeFloat(handle: Long, data: ByteArray?, len: Int, out: FloatArray): Int {
            samples.copyInto(out, 0, 0, minOf(samples.size, out.size))
            return 0
        }
        override fun destroy(handle: Long) {
            destroys++
        }
    }

    @Test
    fun `decoded samples are scaled from the 16 bit range into minus one to one`() {
        val decoder = SpeexDecoder(FakeSpeex(floatArrayOf(32767f, -32767f, 0f, 3276.7f)))
        val out = FloatArray(4)

        assertThat(decoder.decodeFloat(null, 0, out, 4)).isEqualTo(4)

        assertThat(out[0]).isWithin(1e-5f).of(1f)
        assertThat(out[1]).isWithin(1e-5f).of(-1f)
        assertThat(out[2]).isEqualTo(0f)
        assertThat(out[3]).isWithin(1e-5f).of(0.1f)
    }

    @Test
    fun `a decoder whose native create fails throws instead of holding a null handle`() {
        assertThrows(NativeAudioException::class.java) {
            SpeexDecoder(FakeSpeex(createResult = 0L))
        }
    }

    @Test
    fun `destroy releases the native handle only once`() {
        val fake = FakeSpeex()
        val decoder = SpeexDecoder(fake)

        decoder.destroy()
        decoder.destroy()

        assertThat(fake.destroys).isEqualTo(1)
    }
}
