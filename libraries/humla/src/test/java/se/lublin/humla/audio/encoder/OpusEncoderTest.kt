package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusEncoderApi
import se.lublin.humla.audio.native.OpusEncoderNative
import se.lublin.humla.net.PacketBuffer

class OpusEncoderTest {

    private class FakeOpus : OpusEncoderApi {
        val encodedFrameSizes = mutableListOf<Int>()
        val settings = linkedMapOf<Int, Int>()
        var destroys = 0
        override fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long {
            error[0] = 0
            return 42L
        }
        override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int {
            encodedFrameSizes += frameSize
            out[0] = 0x11; out[1] = 0x22; out[2] = 0x33
            return 3
        }
        override fun ctlSetInt(state: Long, request: Int, value: Int): Int {
            settings[request] = value
            return 0
        }
        override fun destroy(state: Long) {
            destroys++
        }
    }

    @Test
    fun `a full packet is handed over as the bare opus payload`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)

        assertThat(encoder.encode(ShortArray(480), 480)).isEqualTo(0)
        assertThat(encoder.isReady).isFalse()
        assertThat(encoder.encode(ShortArray(480), 480)).isEqualTo(3)
        assertThat(encoder.isReady).isTrue()
        assertThat(fake.encodedFrameSizes).containsExactly(960)

        assertThat(encoder.encodedLength).isEqualTo(3)
        assertThat(encoder.isTerminator).isFalse()
        val pb = PacketBuffer.allocate(16)
        encoder.getEncodedData(pb)
        assertThat(pb.size()).isEqualTo(3)
        pb.rewind()
        assertThat(pb.dataBlock(3)).isEqualTo(byteArrayOf(0x11, 0x22, 0x33))
        assertThat(encoder.isReady).isFalse()
    }

    @Test
    fun `terminate flushes a partial packet and marks it as the terminator`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)
        encoder.encode(ShortArray(480), 480)

        encoder.terminate()

        assertThat(fake.encodedFrameSizes).containsExactly(960) // zero-padded to a whole packet
        assertThat(encoder.isTerminator).isTrue()
        val pb = PacketBuffer.allocate(16)
        encoder.getEncodedData(pb)
        pb.rewind()
        assertThat(pb.dataBlock(3)).isEqualTo(byteArrayOf(0x11, 0x22, 0x33))
        assertThat(encoder.isTerminator).isFalse()
    }

    @Test
    fun `the encoder is configured for constant bitrate with in-band FEC and no DTX`() {
        val fake = FakeOpus()
        OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)

        assertThat(fake.settings).containsExactly(
            OpusEncoderNative.OPUS_SET_VBR_REQUEST, 0,
            OpusEncoderNative.OPUS_SET_BITRATE_REQUEST, 40000,
            OpusEncoderNative.OPUS_SET_INBAND_FEC_REQUEST, 1,
            OpusEncoderNative.OPUS_SET_PACKET_LOSS_PERC_REQUEST, OpusEncoder.EXPECTED_PACKET_LOSS_PERCENT,
            OpusEncoderNative.OPUS_SET_DTX_REQUEST, 0,
        )
        assertThat(OpusEncoder.EXPECTED_PACKET_LOSS_PERCENT).isEqualTo(10)
    }

    @Test
    fun `destroy releases the native encoder only once`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)

        encoder.close()
        encoder.close()

        // A second opus_encoder_destroy on the same raw pointer is a native double free.
        assertThat(fake.destroys).isEqualTo(1)
    }
}
