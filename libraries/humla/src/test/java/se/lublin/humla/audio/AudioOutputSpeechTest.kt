package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

class AudioOutputSpeechTest {

    private class FakeOpusDecoder(
        private val nbFrames: Int = 1,
        private val samplesPerFrame: Int = AudioHandler.FRAME_SIZE,
    ) : OpusDecoderApi {
        var destroys = 0
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 1L
        }
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int =
            AudioHandler.FRAME_SIZE
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int =
            AudioHandler.FRAME_SIZE
        override fun destroy(state: Long) {
            destroys++
        }
        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = nbFrames
        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = samplesPerFrame
    }

    /** One Mumble opus payload: the 13-bit size header, then `payload`. */
    private fun opusPacket(payload: ByteArray): ByteArray {
        val pb = PacketBuffer.allocate(payload.size + 4)
        pb.writeLong(payload.size.toLong())
        pb.append(payload, payload.size)
        val length = pb.size()
        pb.rewind()
        return pb.dataBlock(length)
    }

    @Test
    fun `an opus frame reaches the jitter buffer with its sample count as span`() {
        val jitter = FakeJitter()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            HumlaUDPMessageType.UDPVoiceOpus,
            AudioHandler.FRAME_SIZE,
            { _, _ -> },
            FakeOpusDecoder(nbFrames = 2, samplesPerFrame = 480),
            jitter,
        )
        val packet = opusPacket(byteArrayOf(0x41, 0x42, 0x43))

        speech.addFrameToBuffer(PacketBuffer(packet, packet.size), 0, 7)

        // [length, timestamp, span, sequence, userData]: the whole packet, FRAME_SIZE * seq,
        // frames * samplesPerFrame, a sequence of 0 and the message flags as user data.
        assertThat(jitter.puts).containsExactly(listOf(4, AudioHandler.FRAME_SIZE * 7, 2 * 480, 0, 0))
        assertThat(jitter.lastPutData).isEqualTo(packet)
    }

    @Test
    fun `a decoded packet reports the talk state carried in its user data`() {
        val packet = opusPacket(byteArrayOf(0x41, 0x42, 0x43))
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, 1) // userData 1 = shouting
            ctlResult = 3                                    // three packets available
        }
        val states = mutableListOf<Pair<Int, TalkState>>()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            HumlaUDPMessageType.UDPVoiceOpus,
            AudioHandler.FRAME_SIZE,
            { session, state -> states += session to state },
            FakeOpusDecoder(),
            jitter,
        )

        val result = speech.call()

        assertThat(states).containsExactly(42 to TalkState.SHOUTING)
        assertThat(result.isAlive()).isTrue()
        assertThat(result.getNumSamples()).isEqualTo(AudioHandler.FRAME_SIZE)
        assertThat(jitter.ticks).isEqualTo(1)
    }

    @Test
    fun `destroy releases the decoder and the jitter buffer only once`() {
        val jitter = FakeJitter()
        val opus = FakeOpusDecoder()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            HumlaUDPMessageType.UDPVoiceOpus,
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
