/*
 * Copyright (C) 2026 The Mumla authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.MumbleVersion
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A fake server that announces 1.4 or 1.5 and then talks in the matching UDP format, over UDP and
 * through the TCP tunnel: which format the connection picks, and that voice and pings use it.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaConnectionUdpProtocolTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val transports = FakeTransports()
    private val clock = AtomicLong(0L)
    private val connection =
        HumlaConnection(RecordingConnectionListener(), transports, Handler(Looper.getMainLooper()), clock::get)

    /** Copies of what the voice handler saw, since the packet object is reused. */
    private data class Heard(
        val codec: HumlaUDPMessageType,
        val context: Int,
        val session: Int,
        val frameNumber: Long,
        val opus: List<Byte>,
        val terminator: Boolean,
        val volume: Float,
    )

    private val heard = CopyOnWriteArrayList<Heard>()

    @After
    fun tearDown() {
        connection.disconnect()
        mainLooper.idle()
        awaitUntil(description = "protocol thread quit") { !connection.protocolThread.isAlive }
    }

    private fun connect(clientV2: Long, forceTcp: Boolean = false): FakeTcpTransport {
        connection.clientVersion = clientV2
        connection.setForceTCP(forceTcp)
        connection.addVoiceHandler { p ->
            heard += Heard(
                p.codec, p.context, p.session, p.frameNumber,
                p.data.copyOfRange(p.opusOffset, p.opusOffset + p.opusLength).toList(),
                p.isTerminator, p.volumeAdjustment,
            )
        }
        connection.connect(Server(-1, "test", "127.0.0.1", 64738, "user", ""))
        awaitUntil(description = "tcp connect") {
            transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null
        }
        val tcp = transports.tcps[0]
        tcp.simulateConnected()
        awaitUntil(description = "connection established") { connection.isConnected }
        return tcp
    }

    private fun FakeTcpTransport.announce(version: Mumble.Version) {
        simulateMessage(HumlaTCPMessageType.Version, version.toByteArray())
        val sync = Mumble.ServerSync.newBuilder().setSession(1).build()
        simulateMessage(HumlaTCPMessageType.ServerSync, sync.toByteArray())
        awaitUntil(description = "synchronized") { connection.isSynchronized }
    }

    private fun drain() {
        val drained = AtomicBoolean(false)
        check(connection.protocolHandler.post { drained.set(true) })
        awaitUntil(description = "protocol queue drained") { drained.get() }
    }

    private fun udp(): FakeUdpTransport {
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() }
        return transports.udps[0]
    }

    private fun v2Version(major: Int, minor: Int, patch: Int) =
        Mumble.Version.newBuilder().setVersionV2(MumbleVersion.v2(major, minor, patch)).build()

    private fun protobufAudio(opus: ByteArray): ByteArray = byteArrayOf(0) + MumbleUDP.Audio.newBuilder()
        .setContext(VoicePacket.CONTEXT_LISTEN)
        .setSenderSession(7)
        .setFrameNumber(42)
        .setOpusData(ByteString.copyFrom(opus))
        .setVolumeAdjustment(0.5f)
        .setIsTerminator(true)
        .build()
        .toByteArray()

    private fun legacyAudio(opus: ByteArray): ByteArray = PacketBuffer.allocate(64).run {
        append(((HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) or VoicePacket.CONTEXT_SHOUT).toLong())
        writeLong(7) // session
        writeLong(42) // sequence
        writeLong(opus.size.toLong())
        append(opus, opus.size)
        val length = size()
        rewind()
        dataBlock(length)
    }

    private fun protobufPingReply(timestamp: Long) =
        byteArrayOf(1) + MumbleUDP.Ping.newBuilder().setTimestamp(timestamp).build().toByteArray()

    @Test
    fun `a 1_5 server and a 1_5 client speak protobuf over UDP`() {
        val tcp = connect(clientV2 = MumbleVersion.v2(1, 5, 0))
        val udp = udp()
        clock.set(2_000_000_000L) // 2 s: the ping carries 2 000 000 us

        tcp.announce(v2Version(1, 5, 735))

        assertThat(connection.udpProtocol).isEqualTo(UdpProtocol.PROTOBUF)
        awaitUntil(description = "udp ping") { udp.sent.isNotEmpty() }
        val ping = MumbleUDP.Ping.parseFrom(udp.sent[0].copyOfRange(1, udp.sent[0].size))
        assertThat(udp.sent[0][0]).isEqualTo(1.toByte())
        assertThat(ping.timestamp).isEqualTo(2_000_000L)

        clock.set(2_050_000_000L)
        udp.simulateDatagram(protobufPingReply(2_000_000L))
        udp.simulateDatagram(protobufAudio(byteArrayOf(0x11, 0x22)))
        drain()

        assertThat(connection.getUDPLatency()).isEqualTo(50_000L)
        assertThat(heard).containsExactly(
            Heard(HumlaUDPMessageType.UDPVoiceOpus, 3, 7, 42, listOf<Byte>(0x11, 0x22), true, 0.5f),
        )
    }

    @Test
    fun `with a 1_5 server, protobuf voice also arrives through the TCP tunnel`() {
        val tcp = connect(clientV2 = MumbleVersion.v2(1, 5, 0), forceTcp = true)
        tcp.announce(v2Version(1, 5, 0))

        tcp.simulateMessage(HumlaTCPMessageType.UDPTunnel, protobufAudio(byteArrayOf(0x33)))
        drain()

        assertThat(connection.udpProtocol).isEqualTo(UdpProtocol.PROTOBUF)
        assertThat(heard.single().opus).containsExactly(0x33.toByte())
        assertThat(heard.single().session).isEqualTo(7)
    }

    @Test
    fun `with a 1_5 server, legacy packets are not taken for voice or ping replies`() {
        val tcp = connect(clientV2 = MumbleVersion.v2(1, 5, 0))
        val udp = udp()
        tcp.announce(v2Version(1, 5, 0))

        udp.simulateDatagram(legacyAudio(byteArrayOf(0x44)))
        udp.simulateDatagram(UdpPing.encode(UdpProtocol.LEGACY, 1L))
        drain()

        assertThat(heard).isEmpty()
        assertThat(connection.getUDPLatency()).isEqualTo(0L)
    }

    @Test
    fun `a 1_4 server gets the legacy format, over UDP and through the tunnel`() {
        val tcp = connect(clientV2 = MumbleVersion.v2(1, 5, 0))
        val udp = udp()
        tcp.announce(Mumble.Version.newBuilder().setVersionV1(0x010404).build())

        assertThat(connection.udpProtocol).isEqualTo(UdpProtocol.LEGACY)
        awaitUntil(description = "udp ping") { udp.sent.isNotEmpty() }
        assertThat(udp.sent[0][0]).isEqualTo(0x20.toByte())

        udp.simulateDatagram(legacyAudio(byteArrayOf(0x55)))
        tcp.simulateMessage(HumlaTCPMessageType.UDPTunnel, legacyAudio(byteArrayOf(0x66)))
        udp.simulateDatagram(protobufAudio(byteArrayOf(0x77)))
        drain()

        // The protobuf packet reads as a legacy CELT one, which playback drops.
        val opus = heard.filter { it.codec == HumlaUDPMessageType.UDPVoiceOpus }
        assertThat(opus.map { it.opus }).containsExactly(listOf<Byte>(0x55), listOf<Byte>(0x66)).inOrder()
        assertThat(opus[0].context).isEqualTo(VoicePacket.CONTEXT_SHOUT)
        assertThat(opus[0].volume).isEqualTo(1f)
    }

    @Test
    fun `a client below 1_5 stays legacy even with a 1_5 server`() {
        val tcp = connect(clientV2 = MumbleVersion.v2(1, 4, 0))
        tcp.announce(v2Version(1, 5, 0))

        assertThat(connection.udpProtocol).isEqualTo(UdpProtocol.LEGACY)
    }

    @Test
    fun `outgoing voice goes into the tunnel byte for byte`() {
        val tcp = connect(clientV2 = MumbleVersion.v2(1, 5, 0), forceTcp = true)
        tcp.announce(v2Version(1, 5, 0))
        val packet = byteArrayOf(0, 0x08, 0, 0x2A, 1, 0x42)

        connection.sendUDPMessage(packet, packet.size, false)

        assertThat(tcp.sentFrames.last().toList()).isEqualTo(packet.toList())
    }
}
