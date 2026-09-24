package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

/** The pure helpers of [HumlaConnection]. */
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

        val parsed = HumlaTCPMessageType.ChannelState.parse(bytes) as Mumble.ChannelState

        assertThat(parsed.channelId).isEqualTo(5)
        assertThat(parsed.name).isEqualTo("five")
    }

    /**
     * The whole decision-to-warning mapping, iterated from the enum, so a new decision needs an
     * entry here and a swapped pair of warnings shows up as a mismatched value.
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

    /** Each type parses into the protobuf class of the same name, so a swapped parser shows up. */
    @Test
    fun everyServerSentTypeParsesIntoItsOwnMessageClass() {
        for (type in HumlaTCPMessageType.entries - HumlaTCPMessageType.VoiceTarget) {
            // An empty payload lacks required fields for some types; the parser still built one.
            val parsed = try {
                type.parse(ByteArray(0))
            } catch (e: InvalidProtocolBufferException) {
                e.unfinishedMessage
            }
            assertThat(parsed.javaClass.simpleName).isEqualTo(type.name)
        }
    }

    @Test
    fun voiceTargetIsNotAServerToClientMessage() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            HumlaTCPMessageType.VoiceTarget.parse(ByteArray(0))
        }
    }
}
