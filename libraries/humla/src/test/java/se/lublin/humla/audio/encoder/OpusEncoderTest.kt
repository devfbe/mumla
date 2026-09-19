package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusEncoderApi
import se.lublin.humla.net.PacketBuffer

class OpusEncoderTest {

    private class FakeOpus : OpusEncoderApi {
        val encodedFrameSizes = mutableListOf<Int>()
        override fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long {
            error[0] = 0
            return 42L
        }
        override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int {
            encodedFrameSizes += frameSize
            out[0] = 0x11; out[1] = 0x22; out[2] = 0x33
            return 3
        }
        override fun ctlSetInt(state: Long, request: Int, value: Int): Int = 0
        override fun ctlGetInt(state: Long, request: Int, value: IntArray): Int {
            value[0] = 40000
            return 0
        }
        override fun destroy(state: Long) {}
    }

    @Test
    fun `a full packet is written as varint length followed by the opus payload`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)

        assertThat(encoder.encode(ShortArray(480), 480)).isEqualTo(0)
        assertThat(encoder.isReady()).isFalse()
        assertThat(encoder.encode(ShortArray(480), 480)).isEqualTo(3)
        assertThat(encoder.isReady()).isTrue()
        assertThat(fake.encodedFrameSizes).containsExactly(960)

        val pb = PacketBuffer.allocate(16)
        encoder.getEncodedData(pb)
        assertThat(pb.size()).isEqualTo(4)
        pb.rewind()
        assertThat(pb.dataBlock(4)).isEqualTo(byteArrayOf(0x03, 0x11, 0x22, 0x33))
        assertThat(encoder.isReady()).isFalse()
    }

    @Test
    fun `terminate flushes a partial packet and sets the terminator bit in the header`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)
        encoder.encode(ShortArray(480), 480)

        encoder.terminate()

        assertThat(fake.encodedFrameSizes).containsExactly(960) // zero-padded to a whole packet
        val pb = PacketBuffer.allocate(16)
        encoder.getEncodedData(pb)
        pb.rewind()
        assertThat(pb.readLong()).isEqualTo(3L or (1L shl 13)) // 8195: two-byte varint 0xA0 0x03
        assertThat(pb.dataBlock(3)).isEqualTo(byteArrayOf(0x11, 0x22, 0x33))
    }
}
