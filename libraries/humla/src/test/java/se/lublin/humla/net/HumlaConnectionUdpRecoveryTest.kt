package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.testutil.awaitUntil
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec A5 at the connection: what the decisions of [UdpHealthMonitor] do to the voice route, and
 * what happens to the UDP transport after its thread has died.
 *
 * The connection's own clock is injected, so "twenty seconds later" is a value rather than a wait.
 * The restart delays are the looper's, so they are driven by the shadow clock instead - checked
 * first with a probe that a `postDelayed` on a background [android.os.HandlerThread] really does
 * run under `shadowOf(looper).idleFor(...)`, which it does; no fallback to a zero-delay policy was
 * needed for the backoff test, and the tests that use one use it to reach an interleaving rather
 * than to avoid the clock.
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
        built.forEach { c -> awaitUntil(description = "protocol thread quit") { !c.protocolThread.isAlive } }
    }

    private fun HumlaConnection.establish(forceTcp: Boolean = false): FakeTcpTransport {
        setForceTCP(forceTcp)
        connect(server)
        awaitUntil(description = "tcp connect") { transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null }
        val tcp = transports.tcps[0]
        tcp.simulateConnected()
        awaitUntil(description = "connection established") { isConnected }
        mainLooper.idle()
        return tcp
    }

    private fun HumlaConnection.firstUdp(): FakeUdpTransport {
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() && transports.udps[0].connectCalls.get() == 1 }
        return transports.udps[0]
    }

    /**
     * Waits until the protocol thread has worked past everything queued before this call. A plain
     * wait on a counter cannot stand in for it: the runnable under test is queued from inside
     * another runnable, so it is behind whatever the test can already see.
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
        // Not the same instant: the failure handler flips the route first and tells the server
        // afterwards, so a wait on isUsingUdp lands in the middle of it. Measured - the UDPTunnel
        // assertion below failed against an empty list without this barrier.
        connection.drainProtocolQueue("failure handled to the end")
        mainLooper.idle()

        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel) // tells the server to tunnel
        assertThat(transports.udps).hasSize(1)

        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(1)) // first restart after 1 s
        awaitUntil(description = "udp restarted") { transports.udps.size == 2 && transports.udps[1].connectCalls.get() == 1 }

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

    /**
     * The other side of the timeout: a reply that arrives resets the clock, so the ping sixteen
     * seconds after the *first* send does not switch anything. Without it the connection would
     * tunnel its voice fifteen seconds into every session, however well UDP is working - the
     * timeout would be measured from a send that was answered long ago.
     *
     * The latency is asserted in the same breath because it is the other thing this callback writes
     * into the connection, and nothing read it back before.
     */
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

    /**
     * The other input to `shouldForceTCP()`, and nothing in this repository had ever written it:
     * every test drives `forceTcp` and leaves `useTor` false, so `forceTcp || useTor` was only ever
     * sampled over half of its two-boolean input space and the Tor clause was untested by
     * construction - in a file where that one predicate decides whether a UDP socket is opened at
     * all. The flag's trip into the transport was unread for the same reason; the fake now records
     * it.
     *
     * Tor is also where it matters most: it is the configuration in which the handshake can outlast
     * the ping timeout, which is the case theTimeoutIsMeasuredFromTheFirstPing... covers next door.
     */
    @Test
    fun routingOverTorTunnelsVoiceTheSameWayTheSettingDoes() {
        val connection = newConnection()
        connection.setUseTor(true)
        connection.connect(server)
        awaitUntil(description = "tcp connect") { transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null }
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
     * The other half of that dimension, and the half the brief's test cannot see: forcing TCP
     * *after* the connection is up. `connect()` decides `usingUdp` once, so the judgement is still
     * armed - and the counters it reads stop moving the moment the user forces TCP, because
     * `sendPings` stops sending the UDP ping and voice is tunneled. Twenty seconds later the
     * monitor would report a dead link and the chat log would say "UDP unavailable" to a user who
     * had just switched UDP off themselves.
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
     * The backoff belongs to the outage, not to the connection: once UDP has been judged healthy
     * again, the next outage has to retry after a second rather than carrying on where the last one
     * stopped. A monitor tuned to restore on the second ping stands in for twenty seconds of
     * counters climbing, which the fake transports do not produce.
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
     * A restart that comes due behind a disconnect. It is the one callback in this task that can
     * still open a socket after the teardown has run, and nothing would ever close it: the teardown
     * disconnects the transport the connection held at that moment and the object is single-use, so
     * there is no second teardown. The zero-delay policy is what makes the interleaving a fact -
     * with the production delay the restart is not due when `quitSafely` sweeps the queue, so it is
     * dropped for a reason that has nothing to do with the guard.
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
        awaitUntil(description = "protocol thread quit") { !connection.protocolThread.isAlive }

        assertThat(transports.udps).hasSize(1)
    }

    /** The same for the other window: the user forces TCP while a restart is already queued. */
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
        connection.drainProtocolQueue("failure and the restart behind it handled")
        mainLooper.idle()

        assertThat(transports.udps).hasSize(1)
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel) // the failure itself was still reported
    }

    /**
     * The policy's own answer to "stop trying" is honoured rather than read as zero. Nothing passes
     * a finite attempt count today - the default is unbounded, because a UDP link can come back an
     * hour into a call - so this is what keeps the branch from being a line no caller can reach.
     */
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

    /** A datagram shaped like the server's answer to our UDP ping: type nibble, then the echo. */
    private fun udpPingReply(sentAtMicros: Long): ByteArray = ByteArray(9).also {
        it[0] = ((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte()
        java.nio.ByteBuffer.wrap(it, 1, 8).putLong(sentAtMicros)
    }

    private companion object {
        /**
         * Restarts with no delay at all, so a restart can be placed in the queue beside a teardown
         * or a setting change instead of a second behind it.
         */
        val immediateRestarts = ReconnectPolicy(
            baseDelayMillis = 0L, maxDelayMillis = 0L, maxAttempts = Int.MAX_VALUE, maxJitterFraction = 0.0
        )
    }
}
