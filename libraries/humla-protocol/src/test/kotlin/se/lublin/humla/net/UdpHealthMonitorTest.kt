package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

    /**
     * The decision at 20 s after a full window of pings at 0..15 s, whose counters (ours, the
     * server's) [fill] gives for each second, with [last] reported at 20 s.
     */
    private fun afterFullWindow(usingUdp: Boolean, last: Pair<Int, Int>, fill: (Int) -> Pair<Int, Int>): Decision {
        val fresh = UdpHealthMonitor()
        for (t in 0..15 step 5) {
            val (good, serverGood) = fill(t)
            fresh.onTcpPing(seconds(t.toLong()), good, serverGood, usingUdp)
        }
        return fresh.onTcpPing(seconds(20), last.first, last.second, usingUdp)
    }

    /**
     * Judged by the deltas in the window, not by lifetime counters: large frozen counters would keep
     * UDP forever under a cumulative check. Tunneling over TCP the only UDP traffic is the 5 s ping,
     * four per window, so two replies each way must restore, or one lost ping disables UDP forever.
     * Voice only works when it flows both ways, so the restore joins both directions with `and`;
     * the one-direction rows cover the corners where `and`/`or`/`xor` differ.
     */
    @Test
    fun aFullWindowIsJudgedByTheDeltasOfBothDirections() {
        val onUdp = listOf(
            Triple(20 to 20, { t: Int -> t to t }, Decision.KEEP),
            Triple(10 to 40, { t: Int -> 10 to t * 2 }, Decision.SWITCH_TO_TCP_RECEIVE),
            Triple(40 to 7, { t: Int -> t * 2 to 7 }, Decision.SWITCH_TO_TCP_SEND),
            Triple(100 to 100, { _: Int -> 100 to 100 }, Decision.SWITCH_TO_TCP_BOTH),
        )
        for ((last, fill, expected) in onUdp) {
            assertWithMessage("on UDP, $last").that(afterFullWindow(usingUdp = true, last, fill)).isEqualTo(expected)
        }
        val tunnelled = mapOf(2 to 2 to Decision.RESTORE_UDP, 5 to 1 to Decision.KEEP, 1 to 5 to Decision.KEEP)
        for ((last, expected) in tunnelled) {
            assertWithMessage("tunnelled, $last").that(afterFullWindow(usingUdp = false, last) { 0 to 0 })
                .isEqualTo(expected)
        }
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
     * itself and voice would be tunneled for the whole session. A non-positive timeout would fire at
     * the first ping and never recover, because the reference only moves forward with a reply.
     */
    @Test
    fun rejectsANonPositiveWindowOrPingTimeout() {
        assertThrows(IllegalArgumentException::class.java) { UdpHealthMonitor(windowMicros = 0L) }
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
