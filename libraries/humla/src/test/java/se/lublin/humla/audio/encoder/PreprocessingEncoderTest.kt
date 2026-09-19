package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexPreprocessApi

class PreprocessingEncoderTest {

    private class FakePreprocess : SpeexPreprocessApi {
        var runs = 0
        override fun init(frameSize: Int, sampleRate: Int): Long = 5L
        override fun run(state: Long, frame: ShortArray): Int {
            runs++
            for (i in frame.indices) frame[i] = (frame[i] / 2).toShort()
            return 1
        }
        override fun ctlInt(state: Long, request: Int, value: IntArray): Int = 0
        var destroys = 0
        override fun destroy(state: Long) {
            destroys++
        }
    }

    @Test
    fun `frames are preprocessed in place before they reach the wrapped encoder`() {
        val inner = RecordingEncoder()
        val fake = FakePreprocess()
        val encoder = PreprocessingEncoder(inner, 480, 48000, fake)

        encoder.encode(ShortArray(480) { 1000 }, 480)

        assertThat(fake.runs).isEqualTo(1)
        assertThat(inner.received.single().toList().distinct()).containsExactly(500.toShort())
        assertThat(inner.receivedSizes).containsExactly(480)
    }

    @Test
    fun `destroy releases the native state only once`() {
        val inner = RecordingEncoder()
        val fake = FakePreprocess()
        val encoder = PreprocessingEncoder(inner, 480, 48000, fake)

        encoder.destroy()
        encoder.destroy()

        // A second speex_preprocess_state_destroy on the same raw pointer is a native double free.
        assertThat(fake.destroys).isEqualTo(1)
        assertThat(inner.destroyed).isTrue()
    }
}
