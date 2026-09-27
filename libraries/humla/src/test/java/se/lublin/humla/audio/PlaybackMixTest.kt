package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.model.TalkState
import se.lublin.humla.testutil.FakeOpusDecoder

class PlaybackMixTest {
    private val states = mutableListOf<TalkState>()

    /** A talker whose jitter buffer never delivers: concealment until the stream times out. */
    private fun silentTalker() = AudioOutputSpeech(
        1, AudioHandler.FRAME_SIZE, { _, state -> states += state }, FakeOpusDecoder(fill = 0.25f), FakeJitter(),
    )

    @Test
    fun `a talker whose stream ended is removed and handed back to be destroyed`() {
        val mix = PlaybackMix()
        val speech = silentTalker()
        mix.add(speech)
        val ended = mutableListOf<AudioOutputSpeech>()
        val out = ShortArray(AudioHandler.FRAME_SIZE)

        var mixes = 0
        while (ended.isEmpty() && mixes < 50) {
            mix.mixInto(out, 0, out.size) { ended += it }
            mixes++
        }

        assertThat(ended).containsExactly(speech)
        assertThat(mix.size).isEqualTo(0)
        assertThat(mix.mixInto(out, 0, out.size) { ended += it }).isFalse()
    }

    @Test
    fun `the talk state is reported when it changes, not on every mix`() {
        val mix = PlaybackMix()
        mix.add(silentTalker())
        val out = ShortArray(AudioHandler.FRAME_SIZE)

        repeat(50) { mix.mixInto(out, 0, out.size) {} }

        assertThat(states).containsExactly(TalkState.TALKING, TalkState.PASSIVE).inOrder()
    }

    @Test
    fun `live talkers are summed into the buffer`() {
        val mix = PlaybackMix()
        repeat(2) { mix.add(silentTalker()) }
        val out = ShortArray(AudioHandler.FRAME_SIZE)

        assertThat(mix.mixInto(out, 0, out.size) {}).isTrue()

        // Concealment decodes 0.25 per talker; the first frame fades in from 0.
        assertThat(out.last()).isEqualTo((0.5f * Short.MAX_VALUE).toInt().toShort())
    }
}
