package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

/**
 * Pins the pure helpers of [HumlaConnection] so the Java-to-Kotlin conversion cannot change them.
 * These pass before and after the conversion; they are not the TDD cycle for the threading change.
 */
class HumlaConnectionStaticsTest {
    @Test
    fun audioBandwidthIncludesPerPacketOverhead() {
        // overhead per packet = 20+8+4+1+2+12+framesPerPacket bytes, 800/framesPerPacket packets per second
        assertThat(HumlaConnection.calculateAudioBandwidth(40_000, 2)).isEqualTo(59_600)
        assertThat(HumlaConnection.calculateAudioBandwidth(40_000, 1)).isEqualTo(78_400)
        assertThat(HumlaConnection.calculateAudioBandwidth(40_000, 4)).isEqualTo(50_200)
    }

    @Test
    fun parsesAKnownMessageType() {
        val bytes = Mumble.ChannelState.newBuilder().setChannelId(5).setName("five").build().toByteArray()

        val parsed = HumlaConnection.getProtobufMessage(bytes, HumlaTCPMessageType.ChannelState) as Mumble.ChannelState

        assertThat(parsed.channelId).isEqualTo(5)
        assertThat(parsed.name).isEqualTo("five")
    }

    @Test
    fun voiceTargetIsNotAServerToClientMessage() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            HumlaConnection.getProtobufMessage(ByteArray(0), HumlaTCPMessageType.VoiceTarget)
        }
    }
}
