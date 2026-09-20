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

    /**
     * The ping timeout is what catches a link the window cannot see yet, and it still has to fire
     * before the window is full - that is the whole reason it sits ahead of the window test. What
     * it may not do any more is fire while both counters are moving; this history has ours moving
     * and the server's frozen, which is the firewall case the timeout was written for.
     *
     * Was `...EvenWithTraffic`, with both counters climbing, and that history is now KEEP by
     * design: see aLinkThatCarriesVoiceBothWaysIsNotTakenOffUdpForAMissingPingReply.
     */
    @Test
    fun missingPingReplyForFifteenSecondsSwitchesToTcpWhenOnlyOneDirectionCarries() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(1))
        assertThat(monitor.onTcpPing(seconds(15), 30, 0, usingUdp = true)).isEqualTo(Decision.KEEP)

        assertThat(monitor.onTcpPing(seconds(17), 34, 0, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * The fourth corner of `localDelta > 0 && remoteDelta > 0`, the predicate the ping timeout is
     * now measured against: the server hears us, nothing comes back, and no ping is answered
     * either. The timeout has to fire here, and before the window is full, which is the whole
     * reason it sits in front of the window test.
     *
     * Written out rather than mutated clause by clause, for the reason the restore condition's KDoc
     * gives: over (F,F) and (T,T) `&&` and `||` agree, so two corners prove nothing about the
     * operator. (F,F) is pingTimeoutCountsFromTheFirstPingWhenNoReplyEverArrived, (T,F) is
     * missingPingReplyForFifteenSecondsSwitchesToTcpWhenOnlyOneDirectionCarries, and (T,T) is the
     * flap below.
     */
    @Test
    fun missingPingReplySwitchesToTcpWhenOnlyTheServerCanHearUs() {
        monitor.onUdpPingSent(seconds(0))
        monitor.onUdpPingReply(seconds(1))
        assertThat(monitor.onTcpPing(seconds(15), 0, 30, usingUdp = true)).isEqualTo(Decision.KEEP)

        assertThat(monitor.onTcpPing(seconds(17), 0, 34, usingUdp = true)).isEqualTo(Decision.SWITCH_TO_TCP_PING_TIMEOUT)
    }

    /**
     * Measured on a Galaxy S25 against a real Mumble server: SWITCH_TO_TCP_PING_TIMEOUT and
     * RESTORE_UDP alternating at the TCP ping's own five second rate, for as long as the call
     * lasted, filling the user's chat log. Voice worked the whole time - logcat showed packets
     * decrypting continuously - and only the *reply to our UDP ping* never came back.
     *
     * The loop is closed by the two halves of onTcpPing never seeing each other: the timeout is
     * gated on `usingUdp` and the restore lives in the `else` of the same `if`, so while UDP is on
     * the missing reply switches it off, and while it is off the window deltas - still climbing,
     * because switchToTcp only changes the route and sendPings keeps pinging - switch it back on.
     *
     * The rule this pins: the reply to a single packet is evidence of last resort. While both
     * counters testify that UDP carries in both directions, no missing reply may take voice off it.
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
     * The second half of the same defect, and the one that bounds it whatever the first half
     * misses: two state changes inside one window are a fault of the procedure, never a state of
     * the network. A window is the shortest history this class can judge at all, so a decision
     * taken on one is not allowed to be reversed on evidence it already had.
     *
     * Here the counters alone would restore five seconds after the switch, off a window that is
     * still full from before it.
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
     * The other direction of the same lockout, and the one that costs the user something: a link
     * restored on evidence that sits at the start of the window, and dead from that instant on.
     * The ping-timeout arm is decidable at 25 s - the trim has rolled the window past the two
     * replies, so `carriesBothWays` is false and the last reply is 23 s old - and the lockout holds
     * it until 40 s. Measured both ways: with the lockout line deleted the same history switches at
     * 25 s, so the price is three TCP pings of one-way voice, and it is a delay rather than a loss.
     *
     * Written because the class doc named the rule and never its price, and because
     * aDecisionIsNotReversedWithinOneWindowOfTakingIt drives only restore-after-switch: one guard,
     * two directions, and the second had no test.
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
