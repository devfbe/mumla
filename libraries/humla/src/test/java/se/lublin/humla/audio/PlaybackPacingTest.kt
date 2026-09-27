package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.VoicePacket
import kotlin.random.Random

/**
 * One talker played the way [AudioOutput] plays: a mix per blocking write into a track that is
 * full, so each write returns once the track has played one mix. The talker's packets carry 60 ms
 * each and arrive in real time with some jitter. The jitter buffer is [SpeexJitterModel], which
 * has libspeexdsp's timestamp rules but not its adaptive delay.
 */
class PlaybackPacingTest {
    /** Opus without the native library: every packet is one 60 ms frame and decodes whole. */
    private class SixtyMsOpus : OpusDecoderApi {
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long = 1L
        override fun destroy(state: Long) = Unit
        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = 1
        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = PACKET_SAMPLES

        override fun decodeFloat(
            state: Long,
            data: ByteArray?,
            offset: Int,
            len: Int,
            out: FloatArray,
            frameSize: Int,
            decodeFec: Int,
        ): Int = if (data == null) frameSize else PACKET_SAMPLES
    }

    private fun packet(index: Int) = VoicePacket().apply {
        data = byteArrayOf(0x41, 0x42, 0x43)
        opusOffset = 0
        opusLength = data.size
        frameNumber = FIRST_FRAME + index.toLong() * PACKET_SAMPLES / AudioHandler.FRAME_SIZE
    }

    @Test
    fun `a talker sending 60 ms packets with jitter keeps talking at the production mix size`() {
        val mixSamples = AudioOutput.playbackBuffer(DEVICE_MIN_BUFFER_BYTES).mixSamples
        val states = mutableListOf<TalkState>()
        // The talker's average from the device log: one packet.
        val speech = AudioOutputSpeech(
            SESSION,
            mixSamples,
            { _, state -> states += state },
            SixtyMsOpus(),
            SpeexJitterModel(),
            averageAvailable = floatArrayOf(1f),
        )
        val mix = PlaybackMix().apply { add(speech) }
        val ended = mutableListOf<AudioOutputSpeech>()
        val jitter = Random(SEED)
        // The first packet wakes the playback thread, so it arrives at time zero.
        val arrivals = DoubleArray(PACKETS) { k ->
            k * PACKET_MS + if (k == 0) 0.0 else jitter.nextDouble(0.0, MAX_JITTER_MS)
        }
        val pcm = ShortArray(mixSamples)
        val mixMs = mixSamples * 1000.0 / AudioHandler.SAMPLE_RATE

        var next = 0
        var nowMs = 0.0
        while (nowMs < arrivals.last()) {
            while (next < PACKETS && arrivals[next] <= nowMs) speech.addFrameToBuffer(packet(next++))
            mix.mixInto(pcm, 0, pcm.size) { ended += it }
            nowMs += mixMs
        }

        assertThat(ended).isEmpty()
        assertThat(states).containsExactly(TalkState.TALKING)
    }

    private companion object {
        const val SESSION = 7
        const val DEVICE_MIN_BUFFER_BYTES = 11520
        const val PACKET_SAMPLES = 2880
        const val PACKET_MS = 60.0
        const val MAX_JITTER_MS = 15.0
        const val PACKETS = 100
        const val FIRST_FRAME = 100L
        const val SEED = 20260927
    }
}
