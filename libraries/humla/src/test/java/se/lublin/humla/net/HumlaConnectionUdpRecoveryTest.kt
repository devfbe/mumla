package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.awaitUntil
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What the decisions of [UdpHealthMonitor] do to the voice route, and what happens to the UDP
 * transport after its thread has died.
 *
 * The connection's clock is injected, so "twenty seconds later" is a value rather than a wait. The
 * restart delays are posted on the protocol looper and driven with `shadowOf(looper).idleFor(...)`.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaConnectionUdpRecoveryTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val transports = FakeTransports()
    private val listener = RecordingConnectionListener()
    private val clock = AtomicLong(0L) // nanoseconds
    private val server = Server(-1, "test", "127.0.0.1", 64738, "user", "")
    private val built = CopyOnWriteArrayList<HumlaConnection>()

    /** Built without a policy, so the production default is what the backoff test measures. */
    private fun newConnection(): HumlaConnection =
        HumlaConnection(listener, transports, Handler(Looper.getMainLooper()), clock::get).also { built += it }

    private fun newConnection(health: UdpHealthMonitor): HumlaConnection =
        HumlaConnection(listener, transports, Handler(Looper.getMainLooper()), clock::get, health).also { built += it }

    private fun newConnection(health: UdpHealthMonitor, restartPolicy: ReconnectPolicy): HumlaConnection =
        HumlaConnection(listener, transports, Handler(Looper.getMainLooper()), clock::get, health, restartPolicy)
            .also { built += it }

    @After
    fun tearDown() {
        built.forEach { it.disconnect() }
        mainLooper.idle()
        built.forEach { c -> awaitUntil(description = "connection terminated") { c.isTerminated } }
    }

    private fun HumlaConnection.establish(forceTcp: Boolean = false): FakeTcpTransport {
        setForceTCP(forceTcp)
        connect(server)
        awaitUntil(description = "tcp connect") {
            transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null
        }
        val tcp = transports.tcps[0]
        tcp.simulateConnected()
        awaitUntil(description = "connection established") { isConnected }
        mainLooper.idle()
        return tcp
    }

    private fun HumlaConnection.firstUdp(): FakeUdpTransport {
        awaitUntil(description = "udp started") {
            transports.udps.isNotEmpty() && transports.udps[0].connectCalls.get() == 1
        }
        return transports.udps[0]
    }

    /**
     * Waits until the protocol thread has worked past everything queued before this call. Needed
     * because the runnable under test is often queued from inside another runnable.
     */
    private fun HumlaConnection.drainProtocolQueue(description: String) {
        val drained = AtomicBoolean(false)
        check(protocolHandler.post { drained.set(true) }) { "the protocol looper is gone" }
        awaitUntil(description = description) { drained.get() }
    }

    private fun serverPing(good: Int): ByteArray =
        Mumble.Ping.newBuilder().setTimestamp(0L).setGood(good).build().toByteArray()

    private fun atSeconds(s: Long) { clock.set(s * 1_000_000_000L) }

    private fun HumlaConnection.feedPings(seconds: List<Long>, tcp: FakeTcpTransport, good: Int = 0) {
        for (s in seconds) {
            atSeconds(s)
            tcp.simulateMessage(HumlaTCPMessageType.Ping, serverPing(good))
            drainProtocolQueue("ping at $s s handled")
        }
    }

    @Test
    fun udpSocketErrorTunnelsVoiceOverTcpAndRestartsUdpWithBackoff() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        assertThat(connection.isUsingUdp).isTrue()

        udp.simulateError(IOException("network unreachable"))
        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        // The failure handler flips the route before it tells the server, so wait for all of it.
        connection.drainProtocolQueue("failure handled to the end")
        mainLooper.idle()

        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel) // tells the server to tunnel
        assertThat(transports.udps).hasSize(1)

        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1)) // first restart after 1 s
        awaitUntil(description = "udp restarted") {
            transports.udps.size == 2 && transports.udps[1].connectCalls.get() == 1
        }

        transports.udps[1].simulateError(IOException("still down"))
        connection.drainProtocolQueue("second failure handled")
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))
        assertThat(transports.udps).hasSize(2) // second restart waits 2 s
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))
        awaitUntil(description = "udp restarted again") { transports.udps.size == 3 }
    }

    @Test
    fun twentySecondsWithoutUdpTrafficSwitchesToTcpAndWarns() {
        val connection = newConnection()
        val tcp = connection.establish()
        connection.firstUdp()

        connection.feedPings(listOf(0L, 5L, 10L, 15L, 20L), tcp)

        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_UNAVAILABLE)
    }

    @Test
    fun missingUdpPingRepliesForFifteenSecondsSwitchToTcp() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(1).build().toByteArray()
        )
        awaitUntil(description = "first udp ping sent") { udp.sent.isNotEmpty() }

        connection.feedPings(listOf(16L), tcp, good = 5)

        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_PING_TIMEOUT)
    }

    /** A reply resets the timeout, so sixteen seconds after the *first* send nothing switches. */
    @Test
    fun aUdpPingReplyResetsTheTimeoutAndFeedsTheLatency() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(1).build().toByteArray()
        )
        awaitUntil(description = "first udp ping sent") { udp.sent.isNotEmpty() }

        atSeconds(10)
        udp.simulateDatagram(udpPingReply(sentAtMicros = 4_000_000L))
        connection.drainProtocolQueue("ping reply handled")
        connection.feedPings(listOf(16L), tcp, good = 5)
        mainLooper.idle()

        assertThat(connection.isUsingUdp).isTrue()
        assertThat(listener.warnings).isEmpty()
        assertThat(connection.getUDPLatency()).isEqualTo(6_000_000L)
    }

    /**
     * Without a server Version the connection stays on the legacy format, whose ping a 1.5 server
     * reads with `decodePing_legacy`: at most nine bytes behind the header - one varint. Anything
     * else (other than the 12-byte extended-information request) is dropped by the server.
     */
    @Test
    fun theUdpPingIsALegacyPingAMumble15ServerAccepts() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        atSeconds(300) // 300 000 000 us: the four-byte form, 0xF0 0x11 0xE1 0xA3 0x00

        val ping = connection.synchronizeAndAwaitFirstPing(tcp, udp)

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
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        connection.synchronizeAndAwaitFirstPing(tcp, udp)

        val eightyMinutes = 80L * 60L
        atSeconds(eightyMinutes)
        udp.simulateDatagram(udpPingReply(sentAtMicros = eightyMinutes * 1_000_000L - 50_000L))
        connection.drainProtocolQueue("ping reply handled")

        assertThat(connection.getUDPLatency()).isEqualTo(50_000L)
    }

    /** What a server older than 1.5 does: it sends the datagram back as it came. */
    @Test
    fun aPingEchoedByAnOlderServerFeedsTheLatencyAndResetsTheTimeout() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        atSeconds(2)
        val ping = connection.synchronizeAndAwaitFirstPing(tcp, udp)

        atSeconds(10)
        udp.simulateDatagram(ping.copyOf())
        connection.drainProtocolQueue("echo handled")
        connection.feedPings(listOf(20L), tcp, good = 5) // 18 s after the send, 10 s after the echo
        mainLooper.idle()

        assertThat(connection.getUDPLatency()).isEqualTo(8_000_000L)
        assertThat(connection.isUsingUdp).isTrue()
        assertThat(listener.warnings).isEmpty()
    }

    /**
     * A ping datagram too short to carry a timestamp, or carrying a negative one, is dropped
     * quietly: no error log, no latency, and it does not hold off the timeout.
     */
    @Test
    fun aTruncatedPingReplyIsIgnoredWithoutAnError() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        connection.synchronizeAndAwaitFirstPing(tcp, udp)
        ShadowLog.clear()

        atSeconds(5)
        udp.simulateDatagram(byteArrayOf(0x20))
        udp.simulateDatagram(byteArrayOf(0x20, 0xF4.toByte(), 0x01))
        udp.simulateDatagram(byteArrayOf(0x20, 0xFC.toByte())) // varint -1: no ping of ours says that
        connection.drainProtocolQueue("truncated replies handled")
        connection.feedPings(listOf(16L), tcp, good = 5)
        mainLooper.idle()

        assertThat(ShadowLog.getLogs().filter { it.type >= android.util.Log.ERROR }.map { it.msg }).isEmpty()
        assertThat(connection.getUDPLatency()).isEqualTo(0L)
        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_PING_TIMEOUT)
    }

    /**
     * Once the voice is tunneled the server sends nothing over UDP but the answers to our pings
     * (sent with force = true), so those answers are all that can lift localGood past the restore
     * threshold.
     */
    @Test
    fun udpComesBackAfterAPingTimeoutOnceThePingsAreAnsweredAgain() {
        val connection = newConnection() // the production monitor: 20 s window, 15 s timeout, threshold 1
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        connection.synchronizeAndAwaitFirstPing(tcp, udp)

        connection.feedPings(listOf(16L), tcp, good = 5)
        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }

        // Tunneling moves the voice, not the ping: the next ping still goes out on UDP.
        val pingsBefore = udp.sent.size
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(5))
        awaitUntil(description = "a udp ping sent while tunneled") { udp.sent.size > pingsBefore }

        var serverGood = 5
        for (s in listOf(20L, 25L, 30L, 35L, 40L)) {
            atSeconds(s)
            udp.simulateDatagram(udpPingReply(sentAtMicros = s * 1_000_000L - 30_000L))
            connection.drainProtocolQueue("reply at $s s handled")
            serverGood++ // the server decrypted our ping of the same tick
            connection.feedPings(listOf(s), tcp, good = serverGood)
        }

        awaitUntil(description = "udp restored") { connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings)
            .containsExactly(ConnectionWarning.UDP_PING_TIMEOUT, ConnectionWarning.UDP_RESTORED).inOrder()
        assertThat(connection.getUDPLatency()).isEqualTo(30_000L)
    }

    @Test
    fun aServerPingFeedsTheTcpLatency() {
        val connection = newConnection()
        val tcp = connection.establish()

        atSeconds(10)
        tcp.simulateMessage(
            HumlaTCPMessageType.Ping,
            Mumble.Ping.newBuilder().setTimestamp(4_000_000L).build().toByteArray()
        )
        connection.drainProtocolQueue("server ping handled")

        assertThat(connection.getTCPLatency()).isEqualTo(6_000_000L)
    }

    @Test
    fun theClientPingReportsCryptCountersAndPingStatistics() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(1).build().toByteArray(),
        )
        connection.drainProtocolQueue("first pings sent")
        val first = tcp.sentMessages.filterIsInstance<Mumble.Ping>().single()
        assertThat(first.tcpPackets).isEqualTo(0)
        assertThat(first.udpPackets).isEqualTo(0)
        assertThat(first.hasTcpPingAvg()).isFalse()
        assertThat(first.hasUdpPingAvg()).isFalse()

        // TCP round trips of 10 and 30 ms, one UDP round trip of 4 ms.
        for ((sentAt, now) in listOf(0L to 10L, 40L to 70L)) {
            clock.set(now * 1_000_000L)
            tcp.simulateMessage(
                HumlaTCPMessageType.Ping,
                Mumble.Ping.newBuilder().setTimestamp(sentAt * 1_000L).build().toByteArray(),
            )
            connection.drainProtocolQueue("reply at $now ms handled")
        }
        clock.set(80_000_000L)
        udp.simulateDatagram(udpPingReply(sentAtMicros = 76_000L))
        connection.drainProtocolQueue("replies handled")

        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(5))
        awaitUntil(description = "second ping") { tcp.sentMessages.filterIsInstance<Mumble.Ping>().size == 2 }
        val second = tcp.sentMessages.filterIsInstance<Mumble.Ping>()[1]
        assertThat(second.tcpPackets).isEqualTo(2)
        assertThat(second.tcpPingAvg).isWithin(1e-3f).of(20f)
        assertThat(second.tcpPingVar).isWithin(1e-3f).of(100f)
        assertThat(second.udpPackets).isEqualTo(1)
        assertThat(second.udpPingAvg).isWithin(1e-3f).of(4f)
        assertThat(second.udpPingVar).isWithin(1e-3f).of(0f)
        assertThat(second.good).isEqualTo(1)
    }

    @Test
    fun forcedTcpNeverStartsUdpNorWarns() {
        val connection = newConnection()
        val tcp = connection.establish(forceTcp = true)

        connection.feedPings(listOf(0L, 10L, 20L, 30L), tcp)
        mainLooper.idle()

        assertThat(transports.udps).isEmpty()
        assertThat(listener.warnings).isEmpty()
        assertThat(connection.isUsingUdp).isFalse()
    }

    /** `useTor` is the other input to `shouldForceTCP()`. */
    @Test
    fun routingOverTorTunnelsVoiceTheSameWayTheSettingDoes() {
        val connection = newConnection()
        connection.setUseTor(true)
        connection.connect(server)
        awaitUntil(description = "tcp connect") {
            transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null
        }
        val tcp = transports.tcps[0]
        tcp.simulateConnected()
        awaitUntil(description = "connection established") { connection.isConnected }

        connection.feedPings(listOf(0L, 5L, 10L, 15L, 20L), tcp)
        mainLooper.idle()

        assertThat(tcp.connectUseTor).isTrue()
        assertThat(transports.udps).isEmpty()
        assertThat(listener.warnings).isEmpty()
        assertThat(connection.isUsingUdp).isFalse()
    }

    /**
     * Where the UDP socket is pointed, for the first start and a restart. Compared with what the TCP
     * transport got, since the host may be an SRV answer. The emptiness check is separate because
     * InetAddress.getByName("") silently resolves to loopback.
     */
    @Test
    fun everyUdpTransportIsConnectedToTheSameEndpointTheTcpTransportGot() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()

        assertThat(udp.connectHost).isEqualTo(tcp.connectHost)
        assertThat(udp.connectPort).isEqualTo(tcp.connectPort)
        assertThat(udp.connectHost).isNotEmpty()
        assertThat(udp.connectPort).isNotEqualTo(0)

        udp.simulateError(IOException("down"))
        connection.drainProtocolQueue("failure handled to the end")
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))
        awaitUntil(description = "udp restarted") { transports.udps.size == 2 }

        assertThat(transports.udps[1].connectHost).isEqualTo(tcp.connectHost)
        assertThat(transports.udps[1].connectPort).isEqualTo(tcp.connectPort)
    }

    /**
     * Both settings on: the corner where `||` and `xor` differ. Under `xor` the voice of someone who
     * asked for Tor would leave the device outside the proxy.
     */
    @Test
    fun forcingTcpWhileAlsoRoutingOverTorStillTunnelsTheVoice() {
        val connection = newConnection()
        connection.setUseTor(true)
        val tcp = connection.establish(forceTcp = true)

        connection.feedPings(listOf(0L, 5L, 10L, 15L, 20L), tcp)
        mainLooper.idle()

        assertThat(tcp.connectUseTor).isTrue()
        assertThat(transports.udps).isEmpty()
        assertThat(listener.warnings).isEmpty()
        assertThat(connection.isUsingUdp).isFalse()
    }

    /**
     * Forcing TCP *after* the connection is up stops the UDP ping, so the counters stop moving; the
     * monitor must not then report a dead link to a user who switched UDP off themselves.
     */
    @Test
    fun forcingTcpMidConnectionStopsTheJudgementInsteadOfWarningAboutIt() {
        val connection = newConnection()
        val tcp = connection.establish()
        connection.firstUdp()

        connection.setForceTCP(true)
        connection.feedPings(listOf(0L, 5L, 10L, 15L, 20L), tcp)
        mainLooper.idle()

        assertThat(listener.warnings).isEmpty()
    }

    /**
     * The backoff belongs to the outage, not to the connection. The monitor is tuned to restore on
     * the second ping.
     */
    @Test
    fun restoringUdpResetsTheBackoffSoTheNextOutageRetriesAfterASecond() {
        val connection = newConnection(UdpHealthMonitor(windowMicros = 2_000_000L, restoreThreshold = -1))
        val tcp = connection.establish()
        val udp = connection.firstUdp()

        udp.simulateError(IOException("down"))
        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        connection.drainProtocolQueue("failure handled to the end")
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))
        awaitUntil(description = "udp restarted") { transports.udps.size == 2 }

        connection.feedPings(listOf(0L, 2L), tcp)
        awaitUntil(description = "udp restored") { connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_RESTORED).inOrder()

        transports.udps[1].simulateError(IOException("down again"))
        connection.drainProtocolQueue("second failure handled")
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))

        assertThat(transports.udps).hasSize(3)
    }

    /**
     * The production restore threshold, end to end. While tunneled, UDP pings keep going out every
     * five seconds; the replies raise `CryptState.good` (via the fake's real decrypt) and the
     * server's `good` count, and four of each per window clear a threshold of one.
     */
    @Test
    fun udpIsRestoredAtTheThresholdTheAppShipsOncePingRepliesFlowAgain() {
        val connection = newConnection() // the production monitor: 20 s window, threshold 1
        val tcp = connection.establish()
        val udp = connection.firstUdp()

        udp.simulateError(IOException("down"))
        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        connection.drainProtocolQueue("failure handled to the end")
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))
        awaitUntil(description = "udp restarted") { transports.udps.size == 2 }
        val restarted = transports.udps[1]

        connection.feedPings(listOf(0L), tcp, good = 0) // the base sample, both counters at zero
        repeat(4) {
            restarted.simulateDatagram(udpPingReply(sentAtMicros = 0L))
            connection.drainProtocolQueue("ping reply handled")
        }
        connection.feedPings(listOf(20L), tcp, good = 4)

        awaitUntil(description = "udp restored") { connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_RESTORED).inOrder()
    }

    /**
     * SWITCH_TO_TCP_SEND: a firewall that passes one way only - we keep hearing the server, the
     * server stops hearing us.
     */
    @Test
    fun aServerThatStopsHearingUsTunnelsTheVoiceWhileWeStillHearIt() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()

        connection.feedPings(listOf(0L), tcp, good = 0)
        repeat(4) {
            udp.simulateDatagram(udpPingReply(sentAtMicros = 0L))
            connection.drainProtocolQueue("datagram handled")
        }
        connection.feedPings(listOf(20L), tcp, good = 0) // the server still reports nothing good

        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_SEND_FAILED)
    }

    /** SWITCH_TO_TCP_RECEIVE: the server hears us, nothing comes back. */
    @Test
    fun aServerWeCanReachButNotHearTunnelsTheVoiceToo() {
        val connection = newConnection()
        val tcp = connection.establish()
        connection.firstUdp()

        connection.feedPings(listOf(0L), tcp, good = 0)
        connection.feedPings(listOf(20L), tcp, good = 4) // the server heard four, we heard none

        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        mainLooper.idle()
        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_RECEIVE_FAILED)
    }

    /**
     * A restart that comes due behind a disconnect must not open a socket nothing would close. The
     * zero-delay policy forces the interleaving; with the production delay the cancelled delay would
     * drop the restart anyway.
     */
    @Test
    fun aRestartComingDueBehindADisconnectStartsNoSecondUdpTransport() {
        val connection = newConnection(UdpHealthMonitor(), immediateRestarts)
        val tcp = connection.establish()
        connection.firstUdp()
        val gate = CountDownLatch(1)
        connection.protocolHandler.post { gate.await() }
        connection.protocolHandler.post { connection.onUDPConnectionError(IOException("down")) }

        connection.disconnect()
        gate.countDown()
        awaitUntil(description = "teardown ran behind the queued failure") { tcp.disconnectCalls == 1 }
        awaitUntil(description = "connection terminated") { connection.isTerminated }

        assertThat(transports.udps).hasSize(1)
    }

    /** The user forces TCP while a restart is already queued. */
    @Test
    fun aRestartIsSkippedWhenTcpWasForcedWhileItWasPending() {
        val connection = newConnection(UdpHealthMonitor(), immediateRestarts)
        val tcp = connection.establish()
        connection.firstUdp()
        val gate = CountDownLatch(1)
        connection.protocolHandler.post { gate.await() }
        connection.protocolHandler.post { connection.onUDPConnectionError(IOException("down")) }

        connection.setForceTCP(true)
        gate.countDown()
        // Twice: the restart the failure handler schedules can land behind the first barrier.
        connection.drainProtocolQueue("failure handled")
        connection.drainProtocolQueue("the restart the failure queued handled")
        mainLooper.idle()

        assertThat(transports.udps).hasSize(1)
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel) // the failure itself was still reported
    }

    /** A finite attempt count is honoured (the production default is unbounded). */
    @Test
    fun udpStopsRetryingOnceThePolicyRunsOutOfAttempts() {
        val onlyOneRetry = ReconnectPolicy(
            baseDelayMillis = 0L, maxDelayMillis = 0L, maxAttempts = 1, maxJitterFraction = 0.0
        )
        val connection = newConnection(UdpHealthMonitor(), onlyOneRetry)
        val tcp = connection.establish()
        val udp = connection.firstUdp()

        udp.simulateError(IOException("down"))
        awaitUntil(description = "the one retry ran") { transports.udps.size == 2 }
        transports.udps[1].simulateError(IOException("still down"))
        connection.drainProtocolQueue("second failure handled")
        connection.drainProtocolQueue("anything the second failure queued handled")
        mainLooper.idle()

        assertThat(transports.udps).hasSize(2)
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel)
    }

    /**
     * UDP voice arriving continuously while the reply to our own UDP ping never comes back: the
     * route must stay on UDP and nothing may be logged.
     */
    @Test
    fun aMissingUdpPingReplyDoesNotFlapTheRouteWhileVoiceKeepsArriving() {
        val connection = newConnection()
        val tcp = connection.establish()
        val udp = connection.firstUdp()
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(1).build().toByteArray()
        )
        awaitUntil(description = "first udp ping sent") { udp.sent.isNotEmpty() }

        var good = 0
        for (t in 0L..120L step 5) {
            repeat(4) {
                udp.simulateDatagram(udpVoice()) // decrypts, counts, answers no ping
                good += 1
            }
            connection.drainProtocolQueue("voice at $t s handled")
            connection.feedPings(listOf(t), tcp, good = good)
        }
        mainLooper.idle()

        assertThat(connection.isUsingUdp).isTrue()
        assertThat(listener.warnings).isEmpty()
    }

    /**
     * De-duplication is by warning, not by cause: a second genuine UDP thread failure inside the
     * interval is silent in the log, but the route change still happens.
     */
    @Test
    fun anIdenticalWarningInsideTheSuppressionIntervalIsDeliveredOnce() {
        val connection = newConnection()
        connection.establish()
        val udp = connection.firstUdp()

        udp.simulateError(IOException("down"))
        awaitUntil(description = "switched to tcp") { !connection.isUsingUdp }
        connection.drainProtocolQueue("first failure handled")
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1))
        awaitUntil(description = "udp restarted") { transports.udps.size == 2 }

        atSeconds(2)
        transports.udps[1].simulateError(IOException("down again"))
        connection.drainProtocolQueue("second failure handled")
        mainLooper.idle()

        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)

        // It is an interval, not a mute button.
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(2))
        awaitUntil(description = "udp restarted twice") { transports.udps.size == 3 }
        atSeconds(70)
        transports.udps[2].simulateError(IOException("still down"))
        connection.drainProtocolQueue("third failure handled")
        mainLooper.idle()

        assertThat(listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_THREAD_FAILED)

        // The interval runs from the last delivery, not from the start of the connection (the
        // earlier deliveries were at t=0, where that difference is invisible).
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(4))
        awaitUntil(description = "udp restarted three times") { transports.udps.size == 4 }
        atSeconds(100)
        transports.udps[3].simulateError(IOException("down for a fourth time"))
        connection.drainProtocolQueue("fourth failure handled")
        mainLooper.idle()

        assertThat(listener.warnings).hasSize(2)
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
        val connection = newConnection(
            UdpHealthMonitor(pingTimeoutMicros = 3_600_000_000L, restoreThreshold = -1)
        )
        val tcp = connection.establish()
        var changes = 0
        var route = connection.isUsingUdp

        for (t in 0L..300L step 20) {
            connection.feedPings(listOf(t), tcp)
            mainLooper.idle()
            if (connection.isUsingUdp != route) {
                route = connection.isUsingUdp
                changes++
            }
            val last = listener.warnings.lastOrNull() ?: continue
            assertThat(last == ConnectionWarning.UDP_RESTORED).isEqualTo(connection.isUsingUdp)
        }

        assertThat(changes).isEqualTo(15)
        assertThat(connection.isUsingUdp).isFalse()
        assertThat(listener.warnings).hasSize(15)
        assertThat(listener.warnings.last()).isEqualTo(ConnectionWarning.UDP_UNAVAILABLE)
    }

    /** The remaining corner of warn()'s condition: a *different* warning after the interval. */
    @Test
    fun aDifferentWarningIsDeliveredOnceTheIntervalHasPassedAsWell() {
        val connection = newConnection(
            UdpHealthMonitor(pingTimeoutMicros = 3_600_000_000L, restoreThreshold = -1)
        )
        val tcp = connection.establish()

        connection.feedPings(listOf(0L, 20L), tcp)
        mainLooper.idle()
        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_UNAVAILABLE)

        connection.feedPings(listOf(100L), tcp) // 80 s later, so well past the interval
        mainLooper.idle()

        assertThat(listener.warnings)
            .containsExactly(ConnectionWarning.UDP_UNAVAILABLE, ConnectionWarning.UDP_RESTORED)
            .inOrder()
        assertThat(connection.isUsingUdp).isTrue()
    }

    /** A datagram shaped like voice: it decrypts and it counts, and it tells the monitor nothing. */
    private fun udpVoice(): ByteArray = ByteArray(16).also {
        it[0] = ((HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) and 0xFF).toByte()
    }

    /**
     * The answer a Mumble 1.5 server sends to a connectivity ping: the header and the timestamp as
     * a varint, nothing else (`UDPPingEncoder::encodePingPacket_legacy`).
     */
    private fun udpPingReply(sentAtMicros: Long): ByteArray = MumbleLegacyPingDecoder.encodeReplyAsServer(sentAtMicros)

    private fun HumlaConnection.synchronizeAndAwaitFirstPing(tcp: FakeTcpTransport, udp: FakeUdpTransport): ByteArray {
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(1).build().toByteArray()
        )
        awaitUntil(description = "first udp ping sent") { udp.sent.isNotEmpty() }
        return udp.sent[0]
    }

    private companion object {
        /** No delay, so a restart can be queued right beside a teardown or a setting change. */
        val immediateRestarts = ReconnectPolicy(
            baseDelayMillis = 0L, maxDelayMillis = 0L, maxAttempts = Int.MAX_VALUE, maxJitterFraction = 0.0
        )
    }
}
