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

/**
 * Decides whether voice should flow over UDP or be tunneled over TCP.
 *
 * Two independent reasons to give up on UDP:
 *
 * - **No packets in the last [windowMicros]**, judged on deltas of the cumulative counters over a
 *   sliding window rather than on the counters themselves.
 * - **No reply to a UDP ping for [pingTimeoutMicros]**, but only while the window is silent in at
 *   least one direction. Some servers never answer our UDP ping while voice works fine in both
 *   directions (`remoteGood` climbing means the server hears us), so the timeout alone must not
 *   switch.
 *
 * **Hysteresis**: a decision is not reversed within one window of taking it. This bounds the rate
 * of route changes, not their number; a bursty link can still flip once per window, and
 * [HumlaConnection]'s `warn` de-duplicates the announcements. The cost is that a link dying right
 * after a restore stays on UDP until the lockout expires.
 *
 * After a gap in TCP pings the effective window widens (the head is kept until the sample behind it
 * is a whole window old). That is conservative when switching away and permissive when restoring,
 * and deliberately not fixed. [samples] is bounded by the server's ping rate.
 *
 * All times are microseconds on the connection's clock ([HumlaConnection.elapsed]), which only ever
 * moves forward. Not thread-safe: the protocol thread raises every event and reads every decision.
 */
class UdpHealthMonitor(
    private val windowMicros: Long = 20_000_000L,
    private val pingTimeoutMicros: Long = 15_000_000L,
    private val restoreThreshold: Int = 1,
) {
    enum class Decision { KEEP, SWITCH_TO_TCP_BOTH, SWITCH_TO_TCP_SEND, SWITCH_TO_TCP_RECEIVE, SWITCH_TO_TCP_PING_TIMEOUT, RESTORE_UDP }

    private class Sample(val atMicros: Long, val localGood: Int, val remoteGood: Int)

    private val samples = ArrayDeque<Sample>()
    private var firstPingSentMicros = -1L
    private var lastPingReplyMicros = -1L

    /** When the last state-changing decision was handed out, or -1 if none has been. */
    private var lastChangeMicros = -1L

    init {
        // Non-positive values don't crash; they silently tunnel every connection forever.
        require(windowMicros > 0) { "windowMicros must be positive, was $windowMicros" }
        require(pingTimeoutMicros > 0) { "pingTimeoutMicros must be positive, was $pingTimeoutMicros" }
    }

    /**
     * A UDP ping went out. Only the first send is remembered: it is the timeout reference until a
     * reply arrives. A reference that moved with each (5 s) send would never time out.
     */
    fun onUdpPingSent(nowMicros: Long) {
        if (firstPingSentMicros < 0) firstPingSentMicros = nowMicros
    }

    /** A UDP ping came back, which is the only evidence that the round trip still works. */
    fun onUdpPingReply(nowMicros: Long) {
        lastPingReplyMicros = nowMicros
    }

    /**
     * Judges the connection on the server's ping, which is the only message that carries both
     * counters at one instant.
     *
     * @param localGood packets this client decrypted successfully (`CryptState.mUiGood`, cumulative).
     * @param remoteGood packets the server reported as good in its Ping (cumulative).
     * @param usingUdp whether voice currently goes over UDP.
     */
    fun onTcpPing(nowMicros: Long, localGood: Int, remoteGood: Int, usingUdp: Boolean): Decision {
        samples.addLast(Sample(nowMicros, localGood, remoteGood))
        // Drop the head only once the sample behind it is itself a whole window old. Dropping
        // everything older than the window would, with pings landing slightly late, never leave a
        // full window and never decide again.
        while (samples.size > 1 && nowMicros - samples[1].atMicros >= windowMicros) samples.removeFirst()

        val first = samples.first()
        val localDelta = localGood - first.localGood
        val remoteDelta = remoteGood - first.remoteGood
        // Positive evidence needs no full window: one counted packet each way suffices.
        val carriesBothWays = localDelta > 0 && remoteDelta > 0

        val decision = decide(nowMicros, usingUdp, first, localDelta, remoteDelta, carriesBothWays)
        if (decision == Decision.KEEP) return decision
        // Every non-KEEP decision is a state change, so one timestamp serves both directions of
        // the lockout.
        if (lastChangeMicros >= 0 && nowMicros - lastChangeMicros < windowMicros) return Decision.KEEP
        lastChangeMicros = nowMicros
        return decision
    }

    private fun decide(
        nowMicros: Long,
        usingUdp: Boolean,
        first: Sample,
        localDelta: Int,
        remoteDelta: Int,
        carriesBothWays: Boolean,
    ): Decision {
        // Ahead of the window test: decidable from the first send, without waiting a full window.
        if (usingUdp && firstPingSentMicros >= 0 && !carriesBothWays) {
            val reference = if (lastPingReplyMicros >= 0) lastPingReplyMicros else firstPingSentMicros
            if (nowMicros - reference > pingTimeoutMicros) return Decision.SWITCH_TO_TCP_PING_TIMEOUT
        }

        if (nowMicros - first.atMicros < windowMicros) return Decision.KEEP // window not full yet

        return if (usingUdp) {
            when {
                localDelta == 0 && remoteDelta == 0 -> Decision.SWITCH_TO_TCP_BOTH
                remoteDelta == 0 -> Decision.SWITCH_TO_TCP_SEND
                localDelta == 0 -> Decision.SWITCH_TO_TCP_RECEIVE
                else -> Decision.KEEP
            }
        } else {
            // Both directions: voice that only goes one way is not a working connection.
            if (localDelta > restoreThreshold && remoteDelta > restoreThreshold) Decision.RESTORE_UDP else Decision.KEEP
        }
    }
}
