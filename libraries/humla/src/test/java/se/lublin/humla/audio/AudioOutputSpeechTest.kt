package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.VoicePacket
import java.nio.ByteBuffer

class AudioOutputSpeechTest {

    private class FakeOpusDecoder(
        private val nbFrames: Int = 1,
        private val samplesPerFrame: Int = AudioHandler.FRAME_SIZE,
        /** Written to every decoded sample, if set. */
        private val fill: Float? = null,
    ) : OpusDecoderApi {
        var destroys = 0
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 1L
        }
        override fun decodeFloat(
            state: Long,
            data: ByteArray?,
            offset: Int,
            len: Int,
            out: FloatArray,
            frameSize: Int,
            decodeFec: Int,
        ): Int {
            fill?.let { out.fill(it, 0, AudioHandler.FRAME_SIZE) }
            return AudioHandler.FRAME_SIZE
        }
        override fun destroy(state: Long) {
            destroys++
        }
        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = nbFrames
        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = samplesPerFrame
    }

    /** A packet as the jitter buffer holds it: opus frame, volume adjustment bits, terminator flag. */
    private fun jitterPacket(payload: ByteArray, volume: Float = 1f, terminator: Boolean = false): ByteArray =
        payload + ByteBuffer.allocate(4).putFloat(volume).array() + byteArrayOf(if (terminator) 1 else 0)

    private fun voicePacket(payload: ByteArray, frameNumber: Long, context: Int = 0, volume: Float = 1f) =
        VoicePacket().apply {
            data = byteArrayOf(0x7F) + payload
            opusOffset = 1
            opusLength = payload.size
            this.frameNumber = frameNumber
            this.context = context
            volumeAdjustment = volume
            isTerminator = true
        }

    @Test
    fun `an opus frame reaches the jitter buffer with its sample count as span and its trailer`() {
        val jitter = FakeJitter()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            AudioHandler.FRAME_SIZE,
            { _, _ -> },
            FakeOpusDecoder(nbFrames = 2, samplesPerFrame = 480),
            jitter,
        )

        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41, 0x42, 0x43), frameNumber = 7, context = 2, volume = 0.5f))

        // [length, timestamp, span, sequence, userData]: the frame and trailer, FRAME_SIZE * frame
        // number, frames * samplesPerFrame, a sequence of 0 and the context as user data.
        assertThat(jitter.puts).containsExactly(listOf(3 + 5, AudioHandler.FRAME_SIZE * 7, 2 * 480, 0, 2))
        assertThat(jitter.lastPutData!!.copyOf(8))
            .isEqualTo(jitterPacket(byteArrayOf(0x41, 0x42, 0x43), volume = 0.5f, terminator = true))
    }

    @Test
    fun `a packet the opus parser refuses never reaches the jitter buffer`() {
        val jitter = FakeJitter()
        val refusing = FakeOpusDecoder(nbFrames = -4)
        val speech = AudioOutputSpeech(User(42, "alice"), AudioHandler.FRAME_SIZE, { _, _ -> }, refusing, jitter)

        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41), frameNumber = 1))

        assertThat(jitter.puts).isEmpty()
    }

    private fun talkStateFor(context: Int): TalkState {
        val packet = jitterPacket(byteArrayOf(0x41, 0x42, 0x43))
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, context)
            ctlResult = 3
        }
        val states = mutableListOf<TalkState>()
        val listener = AudioOutputSpeech.TalkStateListener { _, state -> states += state }
        AudioOutputSpeech(User(42, "alice"), AudioHandler.FRAME_SIZE, listener, FakeOpusDecoder(), jitter).decode()
        return states.single()
    }

    @Test
    fun `the context maps to the talk state like on desktop Mumble`() {
        assertThat(talkStateFor(VoicePacket.CONTEXT_NORMAL)).isEqualTo(TalkState.TALKING)
        assertThat(talkStateFor(VoicePacket.CONTEXT_SHOUT)).isEqualTo(TalkState.SHOUTING)
        assertThat(talkStateFor(VoicePacket.CONTEXT_WHISPER)).isEqualTo(TalkState.WHISPERING)
        assertThat(talkStateFor(VoicePacket.CONTEXT_LISTEN)).isEqualTo(TalkState.TALKING)
        assertThat(talkStateFor(17)).isEqualTo(TalkState.TALKING)
    }

    @Test
    fun `a decoded packet reports the talk state carried in its user data`() {
        val packet = jitterPacket(byteArrayOf(0x41, 0x42, 0x43))
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, 1) // userData 1 = shouting
            ctlResult = 3                                    // three packets available
        }
        val states = mutableListOf<Pair<Int, TalkState>>()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            AudioHandler.FRAME_SIZE,
            { session, state -> states += session to state },
            FakeOpusDecoder(),
            jitter,
        )

        val alive = speech.decode()

        assertThat(states).containsExactly(42 to TalkState.SHOUTING)
        assertThat(alive).isTrue()
        assertThat(speech.numSamples).isEqualTo(AudioHandler.FRAME_SIZE)
        assertThat(jitter.ticks).isEqualTo(1)
    }

    private fun decodedSamples(localVolume: Float, volumeAdjustment: Float = 1f): FloatArray {
        val packet = jitterPacket(byteArrayOf(0x41, 0x42, 0x43), volumeAdjustment)
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, 0)
            ctlResult = 3
        }
        val user = User(42, "alice").apply { this.localVolume = localVolume }
        val speech = AudioOutputSpeech(user, AudioHandler.FRAME_SIZE, { _, _ -> }, FakeOpusDecoder(fill = 0.5f), jitter)
        speech.decode()
        return speech.samples.copyOf(speech.numSamples)
    }

    @Test
    fun `the user's local volume scales the decoded samples`() {
        val unity = decodedSamples(1f)
        val half = decodedSamples(0.5f)
        val double = decodedSamples(2f)

        assertThat(unity.any { it != 0f }).isTrue()
        for (i in unity.indices) {
            assertThat(half[i]).isWithin(1e-6f).of(unity[i] * 0.5f)
            assertThat(double[i]).isWithin(1e-6f).of(unity[i] * 2f)
        }
    }

    @Test
    fun `the server's volume adjustment scales the samples on top of the local volume`() {
        val unity = decodedSamples(1f)
        val adjusted = decodedSamples(2f, volumeAdjustment = 0.25f)

        assertThat(unity.any { it != 0f }).isTrue()
        for (i in unity.indices) assertThat(adjusted[i]).isWithin(1e-6f).of(unity[i] * 0.5f)
    }

    @Test
    fun `destroy releases the decoder and the jitter buffer only once`() {
        val jitter = FakeJitter()
        val opus = FakeOpusDecoder()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            AudioHandler.FRAME_SIZE,
            { _, _ -> },
            opus,
            jitter,
        )

        speech.destroy()
        speech.destroy()

        // AudioOutputSpeech has no guard of its own: it relies on the ones in OpusDecoder and
        // SpeexJitterBuffer, so it is the pair that has to stay idempotent.
        assertThat(opus.destroys).isEqualTo(1)
        assertThat(jitter.destroys).isEqualTo(1)
    }
}
