package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.net.UdpHealthMonitor.Decision

/**
 * Spec A5's decision function on its own: deltas over a sliding window instead of cumulative
 * counters, and a ping reply that has been missing too long.
 *
 * Every `onTcpPing` arm is reached from both sides here. The two that carry a compound condition -
 * the ping-timeout guard and the restore test - are driven over all four corners of their two
 * booleans rather than one mutation per clause, because `&&` and `||` agree on three of them.
 */
class UdpHealthMonitorTest {
    private val monitor = UdpHealthMonitor()
    private fun seconds(s: Long) = s * 1_000_000L

    @Test
    fun keepsUdpWhileTheWindowIsNotFull() {
        assertThat(monitor.onTcpPing(seconds(0), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(15), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
    }

    /**
     * The healthy case over a *full* window, which is the one the three switch arms are measured
     * against. Without it every "keep using UDP" assertion in this class is answered by the
     * window-not-full early return, and the arm that decides a working connection is unpinned.
     */
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
        // Both counters are large but frozen: the old cumulative check would keep UDP forever.
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
     * The pings that drive this are `postDelayed(5 s)`, which never lands on the tick, and the
     * lateness accumulates over the window. Dropping every sample older than [windowMicros] throws
     * away the only sample that could ever make the window full - the oldest kept one is then
     * always less than a window old - so with the brief's trim this returned KEEP here, and on a
     * real connection for the whole session: the delta half of spec A5 would never fire once.
     *
     * The head is kept until the sample *behind* it is old enough to take over as the base instead.
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
     * The third corner of the restore condition. Voice only works when it flows both ways, so the
     * two halves are joined by `and` - and over three of the four corners `and`, `or` and `xor`
     * agree, which is why this corner and the next one are written out instead of trusting a
     * clause-by-clause mutation of the condition.
     */
    @Test
    fun doesNotRestoreUdpWhenOnlyTheReceivingDirectionRecovers() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = false)

        assertThat(monitor.onTcpPing(seconds(20), 5, 1, usingUdp = false)).isEqualTo(Decision.KEEP)
    }

    /** The fourth corner: the server hears us again but we still hear nothing. */
    @Test
    fun doesNotRestoreUdpWhenOnlyTheSendingDirectionRecovers() {
        for (t in 0L..15L step 5) monitor.onTcpPing(seconds(t), 0, 0, usingUdp = false)

        assertThat(monitor.onTcpPing(seconds(20), 1, 5, usingUdp = false)).isEqualTo(Decision.KEEP)
    }

    @Test
    fun missingPingReplyForFifteenSecondsSwitchesToTcpEvenWithTraffic() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(1))
        assertThat(monitor.onTcpPing(seconds(15), 30, 30, usingUdp = true)).isEqualTo(Decision.KEEP)

        assertThat(monitor.onTcpPing(seconds(17), 34, 34, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * The reply, not the first send, is what the timeout counts from once one has arrived. The
     * brief's test above cannot tell the two apart: its reply lands one second after the send, so
     * both references cross 15 s at the same ping. Here they cross ten seconds apart.
     */
    @Test
    fun aPingReplyRestartsTheTimeoutClock() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(10))

        assertThat(monitor.onTcpPing(seconds(16), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(26), 0, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * A ping is sent every five seconds, so if each send reset the reference the timeout could
     * never be reached and the whole ping-timeout half of spec A5 would be dead code. Only the
     * first send since the last reply counts.
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
     * Every other ping test here sends its first ping at time zero, which makes "count from the
     * first send" and "count from time zero" the same number - and a mutation that replaced the
     * whole reference with the never-set reply time survived on exactly that. The dimension nothing
     * had varied is *when* pinging starts: it starts at the ServerSync, so the reference is the
     * host lookup plus the TCP connect plus the TLS handshake plus authentication. Over Tor, or on
     * a server that takes its time, that is easily more than fifteen seconds, and the connection
     * would tunnel its voice over TCP from its first ping onwards with a warning in the chat log.
     */
    @Test
    fun theTimeoutIsMeasuredFromTheFirstPingAndNotFromTheStartOfTheConnection() {
        monitor.onUdpPingSent(seconds(100)) // a handshake that took a minute and a half

        assertThat(monitor.onTcpPing(seconds(110), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(monitor.onTcpPing(seconds(116), 0, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    @Test
    fun pingTimeoutDoesNotFireWhileAlreadyOnTcp() {
        monitor.onUdpPingSent(seconds(0))

        assertThat(monitor.onTcpPing(seconds(16), 0, 0, usingUdp = false)).isEqualTo(Decision.KEEP)
    }

    /**
     * A non-positive window does not crash, it lies: the trim reduces to the newest sample alone,
     * every delta is taken against itself, and the monitor tunnels voice over TCP for the whole
     * session while every counter it reads is climbing.
     */
    @Test
    fun rejectsANonPositiveWindow() {
        assertThrows(IllegalArgumentException::class.java) { UdpHealthMonitor(windowMicros = 0L) }
    }

    /**
     * The same argument as the window, one dimension over, and it was missing for the same reason
     * the timeout was only ever sampled at its default: a non-positive timeout does not crash
     * either. `now - reference > 0` is already true at the ping that records the first send, so
     * every connection would tunnel its voice from its first ping onwards and tell the user UDP
     * timed out - and never come back, because a reference only ever moves forward with a reply
     * that this configuration can no longer wait for.
     */
    @Test
    fun rejectsANonPositivePingTimeout() {
        assertThrows(IllegalArgumentException::class.java) { UdpHealthMonitor(pingTimeoutMicros = 0L) }
    }

    /**
     * [UdpHealthMonitor.pingTimeoutMicros] was driven at its default and nowhere else, so replacing
     * the parameter with the literal `15_000_000L` survived the whole suite: one point on an axis
     * cannot tell a parameter from a constant, however many mutations the other axes have had.
     * Five seconds is the ping interval, i.e. the tightest timeout a caller could sensibly ask for.
     *
     * This test passes on HEAD - it is a coverage hole being closed, not a defect being fixed.
     */
    @Test
    fun aPingTimeoutOtherThanTheDefaultIsWhatDecides() {
        val impatient = UdpHealthMonitor(pingTimeoutMicros = seconds(5))
        impatient.onUdpPingSent(seconds(0))

        assertThat(impatient.onTcpPing(seconds(4), 0, 0, usingUdp = true)).isEqualTo(Decision.KEEP)
        assertThat(impatient.onTcpPing(seconds(6), 0, 0, usingUdp = true))
            .isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }
}
