package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class HumlaTCPMessageTypeTest {
    @Test
    fun parsesAKnownMessageType() {
        val bytes = Mumble.ChannelState.newBuilder().setChannelId(5).setName("five").build().toByteArray()

        val parsed = HumlaTCPMessageType.ChannelState.parse(bytes) as Mumble.ChannelState

        assertThat(parsed.channelId).isEqualTo(5)
        assertThat(parsed.name).isEqualTo("five")
    }

    @Test
    fun onlyTheFrequentVoiceAndPingFramesAreLeftOutOfTheLog() {
        val unlogged = HumlaTCPMessageType.entries.filterNot { it.isLogged }

        assertThat(unlogged).containsExactly(HumlaTCPMessageType.UDPTunnel, HumlaTCPMessageType.Ping)
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
