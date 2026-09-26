package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.net.UdpHealthMonitor.Decision

/**
 * The UDP health decision function: deltas over a sliding window, and a ping reply that has been
 * missing too long. The compound conditions (ping-timeout guard, restore) are driven over all four
 * corners of their two booleans.
 */
class UdpHealthMonitorTest {
    private val monitor = UdpHealthMonitor()
    private fun seconds(s: Long) = s * 1_000_000L

    @Test
    fun keepsUdpWhileTheWindowIsNotFull() {
        assertThat(monitor.onTcpPing(seconds(0), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(15), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
    }

    /** The healthy case over a *full* window, not answered by the window-not-full early return. */
    @Test
    fun keepsUdpWhenBothDirectionsCarryTrafficAcrossAFullWindow() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), t.toInt(), t.toInt(), usingUdp = true)

        assertThat(monitor.onTcpPing(seconds(20), 20, 20, usingUdp = true)).isEqualTo(Decision.KEEP)
    }

    @Test
    fun switchesToTcpWhenNothingWasReceivedForTwentySeconds() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 10, (t * 2).toInt(), usingUdp = true)

        assertThat(monitor.onTcpPing(seconds(20), 10, 40, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_RECEIVE)
    }

    @Test
    fun switchesToTcpWhenTheServerReceivedNothingForTwentySeconds() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), (t * 2).toInt(), 7, usingUdp = true)

        assertThat(monitor.onTcpPing(seconds(20), 40, 7, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_SEND)
    }

    @Test
    fun judgesByDeltasInTheWindowNotByLifetimeCounters() {
        // Both counters are large but frozen: a cumulative check would keep UDP forever.
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 100, 100, usingUdp = true)

        assertThat(monitor.onTcpPing(seconds(20), 100, 100, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_BOTH)
    }

    @Test
    fun windowSlidesSoOldTrafficDoesNotCount() {
        monitor.onTcpPing(seconds(0), 0, 0, usingUdp = true)
        monitor.onTcpPing(seconds(5), 50, 50, usingUdp = true) // traffic between 0 s and 5 s only
        for (t in 10L..20L step 5) monitor.onTcpPing(seconds(t), 50, 50, usingUdp = true)

        assertThat(monitor.onTcpPing(seconds(25), 50, 50, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_BOTH)
    }

    /**
     * The pings are `postDelayed(5 s)` and their lateness accumulates over the window, so the head
     * sample is kept until the sample *behind* it is old enough to take over as the base; otherwise
     * the window would never be full.
     */
    @Test
    fun aWindowOfLatePingsStillReachesADecision() {
        val jitterMicros = 10_000L // 10 ms late per tick, and it accumulates
        var at = 0L
        repeat(4) {
            monitor.onTcpPing(at, 100, 100, usingUdp = true)
            at += seconds(5) + jitterMicros
        }

        assertThat(monitor.onTcpPing(at, 100, 100, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_BOTH)
    }

    @Test
    fun restoresUdpWhenBothDirectionsFlowAgain() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = false)

        assertThat(monitor.onTcpPing(seconds(20), 1, 1, usingUdp = false)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(25), 2, 2, usingUdp = false)).isEqualTo(Decision.RESTORE_UDP)
    }

    @Test
    fun oneLostPingInAWindowStillAllowsRecovery() {
        // Tunneling over TCP, the only UDP traffic is the 5 s ping: four per 20 s window.
        // Two replies in each direction must be enough, or one lost ping disables UDP forever.
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = false)

        assertThat(monitor.onTcpPing(seconds(20), 2, 2, usingUdp = false)).isEqualTo(Decision.RESTORE_UDP)
    }

    /**
     * Voice only works when it flows both ways, so the restore condition joins both directions with
     * `and`; this and the next test cover the corners where `and`/`or`/`xor` differ.
     */
    @Test
    fun doesNotRestoreUdpWhenOnlyTheReceivingDirectionRecovers() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = false)

        assertThat(monitor.onTcpPing(seconds(20), 5, 1, usingUdp = false)).isEqualTo(Decision.KEEP)
    }

    /** The server hears us again but we still hear nothing. */
    @Test
    fun doesNotRestoreUdpWhenOnlyTheSendingDirectionRecovers() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = false)

        assertThat(monitor.onTcpPing(seconds(20), 1, 5, usingUdp = false)).isEqualTo(Decision.KEEP)
    }

    /**
     * The ping timeout catches a link the window cannot see yet, so it fires before the window is
     * full - but only when not both counters are moving. Here ours moves and the server's is frozen.
     */
    @Test
    fun missingPingReplyForFifteenSecondsSwitchesToTcpWhenOnlyOneDirectionCarries() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(1))
        assertThat(monitor.onTcpPing(seconds(15), 30, 0, usingUdp = true)).isEqualTo(Decision.KEEP)

        assertThat(monitor.onTcpPing(seconds(17), 34, 0, usingUdp = true))
            .isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * The server hears us, nothing comes back, and no ping is answered either: the timeout fires
     * before the window is full.
     */
    @Test
    fun missingPingReplySwitchesToTcpWhenOnlyTheServerCanHearUs() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(1))
        assertThat(monitor.onTcpPing(seconds(15), 0, 30, usingUdp = true)).isEqualTo(Decision.KEEP)

        assertThat(monitor.onTcpPing(seconds(17), 0, 34, usingUdp = true))
            .isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * Voice flowing both ways while only the *reply to our UDP ping* never comes back must not flap
     * between SWITCH_TO_TCP_PING_TIMEOUT and RESTORE_UDP. While both counters show UDP carrying in
     * both directions, a missing reply may not take voice off it.
     */
    @Test
    fun aLinkThatCarriesVoiceBothWaysIsNotTakenOffUdpForAMissingPingReply() {
        monitor.onUdpPingSent(seconds(0)) // and no reply ever arrives
        var usingUdp = true
        var good = 0
        val decisions = mutableListOf<Pair<Long, Decision>>()
        for (t in 0L..120L step 5) {
            good += 4 // four packets decrypted each way per five seconds, both directions
            val d = monitor.onTcpPing(seconds(t), good, good, usingUdp)
            if (d != Decision.KEEP) {
                decisions += t to d
                usingUdp = d == Decision.RESTORE_UDP
            }
        }

        assertThat(decisions).isEmpty()
    }

    /**
     * Two state changes inside one window are a fault of the procedure, never a state of the
     * network: here the counters alone would restore five seconds after the switch, off a window
     * that is still full from before it.
     */
    @Test
    fun aDecisionIsNotReversedWithinOneWindowOfTakingIt() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = true)
        assertThat(monitor.onTcpPing(seconds(20), 0, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_BOTH)

        assertThat(monitor.onTcpPing(seconds(25), 50, 50, usingUdp = false)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(35), 100, 100, usingUdp = false)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(40), 150, 150, usingUdp = false)).isEqualTo(Decision.RESTORE_UDP)
    }

    /**
     * The same lockout in the other direction: a link restored and dead from that instant on is
     * only switched away once the window has passed (at 40 s rather than 25 s).
     */
    @Test
    fun aSwitchIsDelayedByAWholeWindowWhenTheLinkDiesRightAfterARestoration() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(1))
        monitor.onUdpPingReply(seconds(2))
        assertThat(monitor.onTcpPing(seconds(0), 0, 0, usingUdp = false)).isEqualTo(Decision.KEEP)
        for (t in 5L..15L step 5) monitor.onTcpPing(seconds(t), 2, 2, usingUdp = false)
        assertThat(monitor.onTcpPing(seconds(20), 2, 2, usingUdp = false)).isEqualTo(Decision.RESTORE_UDP)

        // The link is dead from 20 s on: the counters stand still and no reply comes back.
        assertThat(monitor.onTcpPing(seconds(25), 2, 2, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(30), 2, 2, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(35), 2, 2, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(40), 2, 2, usingUdp = true))
            .isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * Once a reply has arrived the timeout counts from the reply, not from the first send; here the
     * two cross 15 s ten seconds apart.
     */
    @Test
    fun aPingReplyRestartsTheTimeoutClock() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(10))

        assertThat(monitor.onTcpPing(seconds(16), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(26), 0, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * Pings go out every five seconds, so only the first send since the last reply may count,
     * otherwise the timeout could never be reached.
     */
    @Test
    fun aResentPingDoesNotRestartTheTimeoutClock() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingSent(seconds(10))

        assertThat(monitor.onTcpPing(seconds(16), 0, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    @Test
    fun pingTimeoutCountsFromTheFirstPingWhenNoReplyEverArrived() {
        monitor.onUdpPingSent(seconds(0))

        assertThat(monitor.onTcpPing(seconds(14), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(16), 0, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * Pinging starts at ServerSync, after lookup, connect, TLS and authentication; over Tor that can
     * take well over fifteen seconds, so the timeout must not count from the start of the connection.
     */
    @Test
    fun theTimeoutIsMeasuredFromTheFirstPingAndNotFromTheStartOfTheConnection() {
        monitor.onUdpPingSent(seconds(100)) // a handshake that took a minute and a half

        assertThat(monitor.onTcpPing(seconds(110), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(116), 0, 0, usingUdp = true))
            .isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    @Test
    fun pingTimeoutDoesNotFireWhileAlreadyOnTcp() {
        monitor.onUdpPingSent(seconds(0))

        assertThat(monitor.onTcpPing(seconds(16), 0, 0, usingUdp = false)).isEqualTo(Decision.KEEP)
    }

    /**
     * A non-positive window would not crash: every delta would be taken against the newest sample
     * itself and voice would be tunneled for the whole session.
     */
    @Test
    fun rejectsANonPositiveWindow() {
        assertThrows(IllegalArgumentException::class.java) { UdpHealthMonitor(windowMicros = 0L) }
    }

    /**
     * A non-positive timeout would fire at the first ping and never recover, because the reference
     * only moves forward with a reply.
     */
    @Test
    fun rejectsANonPositivePingTimeout() {
        assertThrows(IllegalArgumentException::class.java) { UdpHealthMonitor(pingTimeoutMicros = 0L) }
    }

    /** The timeout parameter is honoured; five seconds is the ping interval. */
    @Test
    fun aPingTimeoutOtherThanTheDefaultIsWhatDecides() {
        val impatient = UdpHealthMonitor(pingTimeoutMicros = seconds(5))
        impatient.onUdpPingSent(seconds(0))

        assertThat(impatient.onTcpPing(seconds(4), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(impatient.onTcpPing(seconds(6), 0, 0, usingUdp = true))
            .isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }
}
