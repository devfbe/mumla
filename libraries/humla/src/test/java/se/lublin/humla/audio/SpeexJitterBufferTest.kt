package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexJitterNative

class SpeexJitterBufferTest {

    @Test
    fun `get takes the packet length and user data out of the meta array`() {
        val fake = FakeJitter()
        fake.nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
        // [length, timestamp, span, sequence, userData] - length is slot 0, userData slot 4
        fake.nextMeta = intArrayOf(37, 1440, 960, 9, 1)

        val packet = SpeexJitterBuffer(480, fake).get(ByteArray(4096), 480)

        assertThat(packet.status).isEqualTo(SpeexJitterNative.JITTER_BUFFER_OK)
        assertThat(packet.length).isEqualTo(37)
        assertThat(packet.userData).isEqualTo(1)
    }

    @Test
    fun `control sends the value in and returns what the buffer wrote back`() {
        val fake = FakeJitter()
        fake.ctlResult = 3
        val buffer = SpeexJitterBuffer(480, fake)

        val available = buffer.control(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT, 0)

        assertThat(available).isEqualTo(3)
        assertThat(fake.ctlCalls).containsExactly(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT to 0)
    }
}
