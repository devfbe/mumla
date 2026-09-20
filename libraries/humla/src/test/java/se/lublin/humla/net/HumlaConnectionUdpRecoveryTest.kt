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

    /**
     * The other latency line, and the sibling of the one the effect sweep did catch. `tcpLatency =
     * now - msg.timestamp` -> `0L` SURVIVED: the value is written into a field with a public getter
     * and no test read it back, which is the same hole aUdpPingReplyResetsTheTimeout... closed one
     * callback over. A sweep that finds one of a pair has found half a form.
     */
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
     * Where the UDP socket is pointed, for both callers of `startUdp()`. It was unpinned, and not
     * for want of a mutation: `transport.connect("", 0)` at HumlaConnection.kt:751 SURVIVED all 230
     * tests because [FakeUdpTransport] counted the call and recorded neither argument. Exactly the
     * defect [FakeTcpTransport.connectUseTor] was added for, one class further down.
     *
     * Asserted against what the TCP transport was handed rather than against a literal, because
     * "the voice goes to the server the control connection went to" is the property; the host is a
     * SRV lookup's answer, not the string the test passed in. The emptiness check is separate
     * because InetAddress.getByName("") resolves to loopback rather than failing, so an empty host
     * is not an error the user would ever see - it is a call that goes quietly to 127.0.0.1.
     *
     * The restart half matters on its own: this task gave startUdp() its second caller, and the
     * argument that keeps `host` from being read before it is written is written down at the
     * fields' declaration for both of them now.
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
     * The fourth corner of `forceTcp || useTor`, and the only one the other three cannot reach:
     * over (false,false), (true,false) and (false,true) `||` and `xor` agree. Measured before this
     * test existed - `forceTcp || useTor` -> `forceTcp xor useTor` SURVIVED all 230 tests, while
     * the same mutation on the restore condition in UdpHealthMonitor was KILLED(5). So what was
     * missing was this corner, not the technique: the 2^k rule had been applied correctly one file
     * over and left unapplied on the predicate this task had just opened up.
     *
     * The corner is a user who switched both settings on, and under `xor` it is the worst of the
     * four: shouldForceTCP() answers false, the connection opens a UDP socket, and the voice of
     * someone who asked for Tor leaves the device outside the proxy.
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
     * The production restore condition, driven end to end for the first time.
     *
     * It could not be before, and the reason was a fake rather than a missing test:
     * `CryptState.mUiGood` grows only in `CryptState.decrypt()`, reachable only from HumlaUDP's
     * receive loop, and [FakeTransports.createUdp] threw the crypt state away. `localGood` was
     * therefore constant zero in every connection test here, which made `localDelta > threshold`
     * unsatisfiable and forced restoringUdpResetsTheBackoff... to construct
     * `restoreThreshold = -1`. The threshold the app ships was never once reached in a test.
     *
     * The history is the one the app actually produces while voice is tunneled: sendPings keeps
     * sending a UDP ping every five seconds because only the *route* changed, the replies raise
     * mUiGood here and the server's `good` count in its own Ping, and four of each per twenty
     * second window clear a threshold of one comfortably.
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
     * SWITCH_TO_TCP_SEND at the connection, which the same fake made unreachable: it needs
     * `localDelta > 0` while the server's count stands still, and localDelta could not move.
     * Reachable now, and it is the case of a firewall that passes our way and not the other - we
     * keep hearing the server, the server stops hearing us, and voice has to be tunneled anyway
     * because a call that only works in one direction is not a call.
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

    /**
     * The mirror image, SWITCH_TO_TCP_RECEIVE: the server hears us, nothing comes back. Reachable
     * with a constant-zero localGood too, and untested for exactly that reason - every history the
     * fakes could produce ended in SWITCH_TO_TCP_BOTH, which is the arm before it.
     */
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
        // Twice, for the reason the neighbour above drains twice. One drain only waits past what
        // was queued when it was posted: if main posts the barrier before the protocol thread has
        // taken the failure handler off the queue, the restart the handler schedules lands *behind*
        // the barrier and the assertion is taken on an empty window. It kills its mutant today and
        // would go on killing it - it is a race in the test, not a hole in the coverage.
        connection.drainProtocolQueue("failure handled")
        connection.drainProtocolQueue("the restart the failure queued handled")
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

    /**
     * The whole loop at the connection, as it was observed: UDP voice arriving continuously in both
     * directions and the reply to our own UDP ping never coming back. On the device this produced
     * "Switching to TCP mode" and "Switching back to UDP mode" alternately every five seconds for
     * the length of the call.
     *
     * Two things are asserted, because two separate mistakes made it: the route must settle, and
     * the chat log must not be filled even if a decision is taken twice. This test is RED on HEAD -
     * it is a live defect, not a coverage hole - and it could not have been written before this
     * round, because FakeUdpTransport dropped the crypt state and localGood could not move at all.
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
     * The third mistake on its own: a decision that turns out wrong must not be able to fill the
     * chat log, whoever raises it. Identical warnings inside one suppression interval are delivered
     * once. Scope of the claim: this de-duplicates by warning, not by cause - a second genuine UDP
     * thread failure inside the interval is silent in the log, and the route change still happens.
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

        // And it is an interval, not a mute button. Without this half, "never warn twice" passes.
        shadowOf(connection.protocolLooper).idleFor(Duration.ofSeconds(2))
        awaitUntil(description = "udp restarted twice") { transports.udps.size == 3 }
        atSeconds(70)
        transports.udps[2].simulateError(IOException("still down"))
        connection.drainProtocolQueue("third failure handled")
        mainLooper.idle()

        assertThat(listener.warnings)
            .containsExactly(ConnectionWarning.UDP_THREAD_FAILED, ConnectionWarning.UDP_THREAD_FAILED)
    }

    /** A datagram shaped like voice: it decrypts and it counts, and it tells the monitor nothing. */
    private fun udpVoice(): ByteArray = ByteArray(16).also {
        it[0] = ((HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) and 0xFF).toByte()
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
