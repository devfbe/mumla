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

    /**
     * The whole decision-to-warning mapping in one assertion, iterated from the enum rather than
     * written out case by case. Three of the four switch reasons cannot be produced through the
     * fake transports at all - they need `CryptState.mUiGood` itself to move - so as five branches
     * inside the ping handler they would have been five arms with two of them pinned, which 4.04
     * calls a function that only looks covered. Written this way, a decision added later is an
     * extra entry here as well as a compile error in the `when`, and a swapped pair of warnings is
     * a mismatched value rather than a still-distinct set.
     */
    @Test
    fun everyUdpSwitchDecisionCarriesItsOwnWarning() {
        val mapped = UdpHealthMonitor.Decision.values().associateWith { HumlaConnection.switchWarningFor(it) }

        assertThat(mapped).containsExactly(
            UdpHealthMonitor.Decision.KEEP, null,
            UdpHealthMonitor.Decision.RESTORE_UDP, null,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_BOTH, ConnectionWarning.UDP_UNAVAILABLE,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_SEND, ConnectionWarning.UDP_SEND_FAILED,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_RECEIVE, ConnectionWarning.UDP_RECEIVE_FAILED,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_PING_TIMEOUT, ConnectionWarning.UDP_PING_TIMEOUT,
        )
    }

    @Test
    fun voiceTargetIsNotAServerToClientMessage() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            HumlaConnection.getProtobufMessage(ByteArray(0), HumlaTCPMessageType.VoiceTarget)
        }
    }
}
