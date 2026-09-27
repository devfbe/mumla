package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.LogRecorder
import se.lublin.humla.util.HumlaLog
import java.io.IOException

/**
 * What the decisions of [UdpHealthMonitor] do to the voice route, the pings and their statistics,
 * and what happens to the UDP transport after it failed.
 *
 * The protocol's clock is set by the test, so "twenty seconds later" is a value rather than a
 * wait; the ping interval and the restart delays run on the harness's virtual time.
 */
class HumlaConnectionUdpRecoveryTest {
    @get:Rule
    val log = LogRecorder()

    @get:Rule
    internal val harnesses = ConnectionHarnesses()

    private fun harness(
        udpHealth: UdpHealthMonitor = UdpHealthMonitor(),
        restartPolicy: ReconnectPolicy = ProtocolSession.UDP_RESTART_POLICY,
        forceTcp: Boolean = false,
        useTor: Boolean = false,
    ) = harnesses.add(ConnectionHarness(forceTcp, useTor, udpHealth = udpHealth, udpRestartPolicy = restartPolicy))

    private val ConnectionHarness.udps: List<FakeUdpTransport> get() = transports.udps

    private fun serverPing(good: Int): Mumble.Ping = Mumble.Ping.newBuilder().setTimestamp(0L).setGood(good).build()

    private fun ConnectionHarness.feedPings(seconds: List<Long>, good: Int = 0) {
        for (s in seconds) {
            atSeconds(s)
            receive(HumlaTCPMessageType.Ping, serverPing(good))
        }
    }

    private fun ConnectionHarness.datagram(udp: FakeUdpTransport, data: ByteArray) {
        udp.simulateDatagram(data)
        runCurrent()
    }

    /** Fails UDP transport [index] and lets the first restart come due one second later. */
    private fun ConnectionHarness.failUdpAndRestart(index: Int) {
        udps[index].simulateError(IOException("down"))
        runCurrent()
        advanceBy(1_000)
    }

    /** Synchronizes, which sends the first pings; returns the UDP one. */
    private fun ConnectionHarness.synchronizeAndTakeFirstPing(): ByteArray {
        synchronize()
        return udps.single().sent.single()
    }

    @Test
    fun aUdpSocketErrorTunnelsVoiceOverTcpAndRestartsUdpWithBackoff() {
        val h = harness()
        val tcp = h.establish()
        val udp = h.udps.single()
        assertThat(h.connection.isUsingUdp).isTrue()

        udp.simulateError(IOException("network unreachable"))
        h.runCurrent()

        assertThat(h.connection.isUsingUdp).isFalse()
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)
        assertThat(h.listener.allOnCallbackThread).isTrue()
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel) // tells the server to tunnel
        assertThat(h.udps).hasSize(1)

        h.advanceBy(1_000) // the first restart after 1 s
        assertThat(h.udps).hasSize(2)
        assertThat(h.udps[1].connectCalls.get()).isEqualTo(1)

        h.failUdpAndRestart(1)
        assertThat(h.udps).hasSize(2) // the second restart waits 2 s
        h.advanceBy(1_000)
        assertThat(h.udps).hasSize(3)
    }

    @Test
    fun twentySecondsWithoutUdpTrafficSwitchToTcpAndWarn() {
        val h = harness()
        h.establish()

        h.feedPings(listOf(0L, 5L, 10L, 15L, 20L))

        assertThat(h.connection.isUsingUdp).isFalse()
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.UDP_UNAVAILABLE)
    }

    @Test
    fun missingUdpPingRepliesForFifteenSecondsSwitchToTcp() {
        val h = harness()
        h.establish()
        h.synchronizeAndTakeFirstPing()

        h.feedPings(listOf(16L), good = 5)

        assertThat(h.connection.isUsingUdp).isFalse()
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.UDP_PING_TIMEOUT)
    }

    /** A reply resets the timeout, so sixteen seconds after the *first* send nothing switches. */
    @Test
    fun aUdpPingReplyResetsTheTimeoutAndFeedsTheLatency() {
        val h = harness()
        h.establish()
        h.synchronizeAndTakeFirstPing()

        h.atSeconds(10)
        h.datagram(h.udps.single(), udpPingReply(sentAtMicros = 4_000_000L))
        h.feedPings(listOf(16L), good = 5)

        assertThat(h.connection.isUsingUdp).isTrue()
        assertThat(h.listener.warnings).isEmpty()
        assertThat(h.connection.latency!!.udpMicros).isEqualTo(6_000_000L)
    }

    /**
     * Without a server Version the connection stays on the legacy format, whose ping a 1.5 server
     * reads with `decodePing_legacy`: at most nine bytes behind the header - one varint. Anything
     * else (other than the 12-byte extended-information request) is dropped by the server.
     */
    @Test
    fun theUdpPingIsALegacyPingAMumble15ServerAccepts() {
        val h = harness()
        h.establish()
        h.atSeconds(300) // 300 000 000 us: the four-byte form, 0xF0 0x11 0xE1 0xA3 0x00

        val ping = h.synchronizeAndTakeFirstPing()

        assertThat(ping.map { it.toInt() and 0xFF }).containsExactly(0x20, 0xF0, 0x11, 0xE1, 0xA3, 0x00).inOrder()
        assertThat(ping.size).isAtMost(10)
        assertThat(MumbleLegacyPingDecoder.decodeAsServer(ping)).isEqualTo(300_000_000L)
    }

    /** Guards the decoder: a 16-byte ping (header, raw long, padding) must be rejected. */
    @Test
    fun theMumble15RulesDropTheSixteenBytePingThisClientUsedToSend() {
        val old = ByteArray(16).also {
            it[0] = ((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte()
            java.nio.ByteBuffer.wrap(it, 1, 8).putLong(300_000_000L)
        }
        assertThat(MumbleLegacyPingDecoder.decodeAsServer(old)).isNull()
    }

    /** Eighty minutes into a call the timestamp needs the eight-byte varint form. */
    @Test
    fun aMumble15ReplyLateInALongCallFeedsTheLatency() {
        val h = harness()
        h.establish()
        h.synchronizeAndTakeFirstPing()

        val eightyMinutes = 80L * 60L
        h.atSeconds(eightyMinutes)
        h.datagram(h.udps.single(), udpPingReply(sentAtMicros = eightyMinutes * 1_000_000L - 50_000L))

        assertThat(h.connection.latency!!.udpMicros).isEqualTo(50_000L)
    }

    /** What a server older than 1.5 does: it sends the datagram back as it came. */
    @Test
    fun aPingEchoedByAnOlderServerFeedsTheLatencyAndResetsTheTimeout() {
        val h = harness()
        h.establish()
        h.atSeconds(2)
        val ping = h.synchronizeAndTakeFirstPing()

        h.atSeconds(10)
        h.datagram(h.udps.single(), ping.copyOf())
        h.feedPings(listOf(20L), good = 5) // 18 s after the send, 10 s after the echo

        assertThat(h.connection.latency!!.udpMicros).isEqualTo(8_000_000L)
        assertThat(h.connection.isUsingUdp).isTrue()
        assertThat(h.listener.warnings).isEmpty()
    }

    /**
     * A ping datagram too short to carry a timestamp, or carrying a negative one, is dropped
     * quietly: no error log, no latency, and it does not hold off the timeout.
     */
    @Test
    fun aTruncatedPingReplyIsIgnoredWithoutAnError() {
        val h = harness()
        h.establish()
        h.synchronizeAndTakeFirstPing()
        log.clear()

        h.atSeconds(5)
        val udp = h.udps.single()
        h.datagram(udp, byteArrayOf(0x20))
        h.datagram(udp, byteArrayOf(0x20, 0xF4.toByte(), 0x01))
        h.datagram(udp, byteArrayOf(0x20, 0xFC.toByte())) // varint -1: no ping of ours says that
        h.feedPings(listOf(16L), good = 5)

        assertThat(log.lines.filter { it.level == HumlaLog.Level.ERROR }).isEmpty()
        assertThat(h.connection.latency!!.udpMicros).isEqualTo(0L)
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.UDP_PING_TIMEOUT)
    }

    /**
     * Once the voice is tunnelled the server sends nothing over UDP but the answers to our pings,
     * so those answers are all that can lift localGood past the restore threshold.
     */
    @Test
    fun udpComesBackAfterAPingTimeoutOnceThePingsAreAnsweredAgain() {
        val h = harness() // the production monitor: 20 s window, 15 s timeout, threshold 1
        h.establish()
        h.synchronizeAndTakeFirstPing()
        val udp = h.udps.single()

        h.feedPings(listOf(16L), good = 5)
        assertThat(h.connection.isUsingUdp).isFalse()

        // Tunnelling moves the voice, not the ping: the next ping still goes out on UDP.
        val pingsBefore = udp.sent.size
        h.advanceBy(5_000)
        assertThat(udp.sent.size).isGreaterThan(pingsBefore)

        var serverGood = 5
        for (s in listOf(20L, 25L, 30L, 35L, 40L)) {
            h.atSeconds(s)
            h.datagram(udp, udpPingReply(sentAtMicros = s * 1_000_000L - 30_000L))
            serverGood++ // the server decrypted our ping of the same tick
            h.feedPings(listOf(s), good = serverGood)
        }

        assertThat(h.connection.isUsingUdp).isTrue()
        assertThat(h.listener.warnings)
            .containsExactly(ConnectionWarning.UDP_PING_TIMEOUT, ConnectionWarning.UDP_RESTORED).inOrder()
        assertThat(h.connection.latency!!.udpMicros).isEqualTo(30_000L)
    }

    @Test
    fun aServerPingFeedsTheTcpLatency() {
        val h = harness()
        h.establish()
        h.synchronize()

        h.atSeconds(10)
        h.receive(HumlaTCPMessageType.Ping, Mumble.Ping.newBuilder().setTimestamp(4_000_000L).build())

        assertThat(h.connection.latency!!.tcpMicros).isEqualTo(6_000_000L)
    }

    @Test
    fun theClientPingReportsCryptCountersAndPingStatistics() {
        val h = harness()
        val tcp = h.establish()
        h.synchronize()
        val first = tcp.sentMessages.filterIsInstance<Mumble.Ping>().single()
        assertThat(first.tcpPackets).isEqualTo(0)
        assertThat(first.udpPackets).isEqualTo(0)
        assertThat(first.hasTcpPingAvg()).isFalse()
        assertThat(first.hasUdpPingAvg()).isFalse()

        // TCP round trips of 10 and 30 ms, one UDP round trip of 4 ms.
        for ((sentAt, now) in listOf(0L to 10L, 40L to 70L)) {
            h.clock.set(now * 1_000_000L)
            h.receive(HumlaTCPMessageType.Ping, Mumble.Ping.newBuilder().setTimestamp(sentAt * 1_000L).build())
        }
        h.clock.set(80_000_000L)
        h.datagram(h.udps.single(), udpPingReply(sentAtMicros = 76_000L))

        h.advanceBy(5_000)
        val second = tcp.sentMessages.filterIsInstance<Mumble.Ping>()[1]
        assertThat(second.tcpPackets).isEqualTo(2)
        assertThat(second.tcpPingAvg).isWithin(1e-3f).of(20f)
        assertThat(second.tcpPingVar).isWithin(1e-3f).of(100f)
        assertThat(second.udpPackets).isEqualTo(1)
        assertThat(second.udpPingAvg).isWithin(1e-3f).of(4f)
        assertThat(second.udpPingVar).isWithin(1e-3f).of(0f)
        assertThat(second.good).isEqualTo(1)
    }

    /**
     * Forced TCP and Tor both tunnel the voice, and so do both at once: the corner where `||` and
     * `xor` differ, where under `xor` the voice of someone who asked for Tor would leave the device
     * outside the proxy.
     */
    @Test
    fun forcedTcpOrTorNeverStartsUdpNorWarns() {
        for ((forceTcp, useTor) in listOf(true to false, false to true, true to true)) {
            val h = harness(forceTcp = forceTcp, useTor = useTor)
            val tcp = h.establish()

            h.feedPings(listOf(0L, 5L, 10L, 15L, 20L, 30L))

            val case = "forceTcp=$forceTcp useTor=$useTor"
            assertWithMessage(case).that(tcp.connectUseTor).isEqualTo(useTor)
            assertWithMessage(case).that(h.udps).isEmpty()
            assertWithMessage(case).that(h.listener.warnings).isEmpty()
            assertWithMessage(case).that(h.connection.isUsingUdp).isFalse()
        }
    }

    /**
     * Where the UDP socket is pointed, for the first start and a restart. Compared with what the TCP
     * transport got, since the host may be an SRV answer. The emptiness check is separate because
     * InetAddress.getByName("") silently resolves to loopback.
     */
    @Test
    fun everyUdpTransportIsConnectedToTheSameEndpointTheTcpTransportGot() {
        val h = harness()
        val tcp = h.establish()
        val udp = h.udps.single()

        assertThat(udp.connectHost).isEqualTo(tcp.connectHost)
        assertThat(udp.connectPort).isEqualTo(tcp.connectPort)
        assertThat(udp.connectHost).isNotEmpty()
        assertThat(udp.connectPort).isNotEqualTo(0)

        h.failUdpAndRestart(0)

        assertThat(h.udps[1].connectHost).isEqualTo(tcp.connectHost)
        assertThat(h.udps[1].connectPort).isEqualTo(tcp.connectPort)
    }

    /** The backoff belongs to the outage, not to the connection. The monitor restores on the second ping. */
    @Test
    fun restoringUdpResetsTheBackoffSoTheNextOutageRetriesAfterASecond() {
        val h = harness(UdpHealthMonitor(windowMicros = 2_000_000L, restoreThreshold = -1))
        h.establish()

        h.failUdpAndRestart(0)
        assertThat(h.udps).hasSize(2)

        h.feedPings(listOf(0L, 2L))
        assertThat(h.connection.isUsingUdp).isTrue()
        assertThat(h.listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_RESTORED).inOrder()

        h.failUdpAndRestart(1)

        assertThat(h.udps).hasSize(3)
    }

    /**
     * The production restore threshold, end to end. While tunnelled, UDP pings keep going out every
     * five seconds; the replies raise `CryptState.good` (via the fake's real decrypt) and the
     * server's `good` count, and four of each per window clear a threshold of one.
     */
    @Test
    fun udpIsRestoredAtTheThresholdTheAppShipsOncePingRepliesFlowAgain() {
        val h = harness() // the production monitor: 20 s window, threshold 1
        h.establish()

        h.failUdpAndRestart(0)
        val restarted = h.udps[1]

        h.feedPings(listOf(0L), good = 0) // the base sample, both counters at zero
        repeat(4) { h.datagram(restarted, udpPingReply(sentAtMicros = 0L)) }
        h.feedPings(listOf(20L), good = 4)

        assertThat(h.connection.isUsingUdp).isTrue()
        assertThat(h.listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_RESTORED).inOrder()
    }

    /**
     * A firewall that passes one way only: we hear the server and it stops hearing us
     * (SWITCH_TO_TCP_SEND), or the server hears us and nothing comes back (SWITCH_TO_TCP_RECEIVE).
     */
    @Test
    fun aOneWayLinkTunnelsTheVoice() {
        for ((repliesHeard, serverGood, warning) in listOf(
            Triple(4, 0, ConnectionWarning.UDP_SEND_FAILED),
            Triple(0, 4, ConnectionWarning.UDP_RECEIVE_FAILED),
        )) {
            val h = harness()
            h.establish()

            h.feedPings(listOf(0L), good = 0)
            repeat(repliesHeard) { h.datagram(h.udps.single(), udpPingReply(sentAtMicros = 0L)) }
            h.feedPings(listOf(20L), good = serverGood)

            assertWithMessage("$warning").that(h.connection.isUsingUdp).isFalse()
            assertWithMessage("$warning").that(h.listener.warnings).containsExactly(warning)
        }
    }

    /**
     * A restart that comes due behind a disconnect must not open a socket nothing would close. The
     * zero-delay policy puts the restart right behind the failure, and the disconnect between them.
     */
    @Test
    fun aRestartComingDueBehindADisconnectStartsNoSecondUdpTransport() {
        val h = harness(restartPolicy = immediateRestarts)
        val tcp = h.establish()

        h.onProtocolContext {
            h.connection.onUDPConnectionError(IOException("down"))
            h.connection.disconnect()
        }
        h.runCurrent()

        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(h.connection.isTerminated).isTrue()
        assertThat(h.udps).hasSize(1)
    }

    /** A finite attempt count is honoured (the production default is unbounded). */
    @Test
    fun udpStopsRetryingOnceThePolicyRunsOutOfAttempts() {
        val onlyOneRetry =
            ReconnectPolicy(baseDelayMillis = 0L, maxDelayMillis = 0L, maxAttempts = 1, maxJitterFraction = 0.0)
        val h = harness(restartPolicy = onlyOneRetry)
        val tcp = h.establish()

        h.udps.single().simulateError(IOException("down"))
        h.runCurrent()
        assertThat(h.udps).hasSize(2)
        h.udps[1].simulateError(IOException("still down"))
        h.advanceBy(60_000)

        assertThat(h.udps).hasSize(2)
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel)
    }

    /**
     * UDP voice arriving continuously while the reply to our own UDP ping never comes back: the
     * route must stay on UDP and nothing may be logged.
     */
    @Test
    fun aMissingUdpPingReplyDoesNotFlapTheRouteWhileVoiceKeepsArriving() {
        val h = harness()
        h.establish()
        h.synchronizeAndTakeFirstPing()
        val udp = h.udps.single()

        var good = 0
        for (t in 0L..120L step 5) {
            repeat(4) {
                h.datagram(udp, voiceDatagram(16)) // decrypts, counts, answers no ping
                good += 1
            }
            h.feedPings(listOf(t), good = good)
        }

        assertThat(h.connection.isUsingUdp).isTrue()
        assertThat(h.listener.warnings).isEmpty()
    }

    /**
     * De-duplication is by warning, not by cause: a second genuine UDP failure inside the interval
     * is silent in the log, but the route change still happens.
     */
    @Test
    fun anIdenticalWarningInsideTheSuppressionIntervalIsDeliveredOnce() {
        val h = harness()
        h.establish()

        h.failUdpAndRestart(0)
        assertThat(h.udps).hasSize(2)

        h.atSeconds(2)
        h.udps[1].simulateError(IOException("down again"))
        h.runCurrent()

        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)

        // It is an interval, not a mute button.
        h.advanceBy(2_000)
        assertThat(h.udps).hasSize(3)
        h.atSeconds(70)
        h.udps[2].simulateError(IOException("still down"))
        h.runCurrent()

        assertThat(h.listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_THREAD_FAILED)

        // The interval runs from the last delivery, not from the start of the connection (the
        // earlier deliveries were at t=0, where that difference is invisible).
        h.advanceBy(4_000)
        assertThat(h.udps).hasSize(4)
        h.atSeconds(100)
        h.udps[3].simulateError(IOException("down for a fourth time"))
        h.runCurrent()

        assertThat(h.listener.warnings).hasSize(2)
    }

    /**
     * A link that alternates every window. Invariant: after every ping, the last warning the user
     * can see names the route the connection is on - warn() only suppresses a line that would
     * repeat the last delivered one.
     */
    @Test
    fun aFlappingLinkNeverLeavesAWarningThatContradictsTheRoute() {
        // restoreThreshold = -1 restores on any full window, so the monitor alternates on every
        // ping; the hour-long ping timeout keeps the ping-timeout arm out.
        val h = harness(UdpHealthMonitor(pingTimeoutMicros = 3_600_000_000L, restoreThreshold = -1))
        h.establish()
        var changes = 0
        var route = h.connection.isUsingUdp

        for (t in 0L..300L step 20) {
            h.feedPings(listOf(t))
            if (h.connection.isUsingUdp != route) {
                route = h.connection.isUsingUdp
                changes++
            }
            val last = h.listener.warnings.lastOrNull() ?: continue
            assertThat(last == ConnectionWarning.UDP_RESTORED).isEqualTo(h.connection.isUsingUdp)
        }

        assertThat(changes).isEqualTo(15)
        assertThat(h.connection.isUsingUdp).isFalse()
        assertThat(h.listener.warnings).hasSize(15)
        assertThat(h.listener.warnings.last()).isEqualTo(ConnectionWarning.UDP_UNAVAILABLE)
    }

    /** The remaining corner of warn()'s condition: a *different* warning after the interval. */
    @Test
    fun aDifferentWarningIsDeliveredOnceTheIntervalHasPassedAsWell() {
        val h = harness(UdpHealthMonitor(pingTimeoutMicros = 3_600_000_000L, restoreThreshold = -1))
        h.establish()

        h.feedPings(listOf(0L, 20L))
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.UDP_UNAVAILABLE)

        h.feedPings(listOf(100L)) // 80 s later, so well past the interval

        assertThat(h.listener.warnings)
            .containsExactly(ConnectionWarning.UDP_UNAVAILABLE, ConnectionWarning.UDP_RESTORED)
            .inOrder()
        assertThat(h.connection.isUsingUdp).isTrue()
    }

    /** Every decision that takes voice off UDP carries its own warning; iterated from the enum. */
    @Test
    fun everyUdpSwitchDecisionCarriesItsOwnWarning() {
        val mapped = UdpHealthMonitor.Decision.entries.associateWith { ProtocolSession.switchWarningFor(it) }

        assertThat(mapped).containsExactly(
            UdpHealthMonitor.Decision.KEEP, null,
            UdpHealthMonitor.Decision.RESTORE_UDP, null,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_BOTH, ConnectionWarning.UDP_UNAVAILABLE,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_SEND, ConnectionWarning.UDP_SEND_FAILED,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_RECEIVE, ConnectionWarning.UDP_RECEIVE_FAILED,
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_PING_TIMEOUT, ConnectionWarning.UDP_PING_TIMEOUT,
        )
    }

    /**
     * The answer a Mumble 1.5 server sends to a connectivity ping: the header and the timestamp as
     * a varint, nothing else (`UDPPingEncoder::encodePingPacket_legacy`).
     */
    private fun udpPingReply(sentAtMicros: Long): ByteArray = MumbleLegacyPingDecoder.encodeReplyAsServer(sentAtMicros)

    private companion object {

        /** No delay, so a restart can be queued right beside a teardown. */
        val immediateRestarts = ReconnectPolicy(
            baseDelayMillis = 0L, maxDelayMillis = 0L, maxAttempts = Int.MAX_VALUE, maxJitterFraction = 0.0
        )
    }
}
