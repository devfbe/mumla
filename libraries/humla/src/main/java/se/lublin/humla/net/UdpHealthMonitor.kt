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
 * Decides whether voice should flow over UDP or be tunneled over TCP. Gives up on UDP when no
 * packets moved in one direction over the last [windowMicros] (counter deltas), or when no UDP ping
 * reply came for [pingTimeoutMicros] while the window is silent in at least one direction (some
 * servers never answer UDP pings although voice works both ways). A decision is not reversed within
 * one window of taking it.
 *
 * Times are microseconds on the connection's monotonic clock. Not thread-safe: protocol context only.
 */
class UdpHealthMonitor(
    private val windowMicros: Long = 20_000_000L,
    private val pingTimeoutMicros: Long = 15_000_000L,
    private val restoreThreshold: Int = 1,
) {
    enum class Decision {
        KEEP,
        SWITCH_TO_TCP_BOTH,
        SWITCH_TO_TCP_SEND,
        SWITCH_TO_TCP_RECEIVE,
        SWITCH_TO_TCP_PING_TIMEOUT,
        RESTORE_UDP,
    }

    private class Sample(val atMicros: Long, val localGood: Int, val remoteGood: Int)

    private val samples = ArrayDeque<Sample>()
    private var firstPingSentMicros = -1L
    private var lastPingReplyMicros = -1L

    /** -1 if no state-changing decision was handed out yet. */
    private var lastChangeMicros = -1L

    init {
        // Non-positive values don't crash; they silently tunnel every connection forever.
        require(windowMicros > 0) { "windowMicros must be positive, was $windowMicros" }
        require(pingTimeoutMicros > 0) { "pingTimeoutMicros must be positive, was $pingTimeoutMicros" }
    }

    /** Only the first send is remembered: it is the timeout reference until a reply arrives. */
    fun onUdpPingSent(nowMicros: Long) {
        if (firstPingSentMicros < 0) firstPingSentMicros = nowMicros
    }

    fun onUdpPingReply(nowMicros: Long) {
        lastPingReplyMicros = nowMicros
    }

    /**
     * Judges the connection on the server's ping, the only message carrying both counters at once.
     *
     * @param localGood packets this client decrypted successfully (`CryptState.good`, cumulative).
     * @param remoteGood packets the server reported as good in its Ping (cumulative).
     * @param usingUdp whether voice currently goes over UDP.
     */
    fun onTcpPing(nowMicros: Long, localGood: Int, remoteGood: Int, usingUdp: Boolean): Decision {
        samples.addLast(Sample(nowMicros, localGood, remoteGood))
        // Drop the head only once the sample behind it is a whole window old, so late pings still
        // leave a full window.
        while (samples.size > 1 && nowMicros - samples[1].atMicros >= windowMicros) samples.removeFirst()

        val first = samples.first()
        val localDelta = localGood - first.localGood
        val remoteDelta = remoteGood - first.remoteGood
        val carriesBothWays = localDelta > 0 && remoteDelta > 0

        val decision = decide(nowMicros, usingUdp, first, localDelta, remoteDelta, carriesBothWays)
        if (decision == Decision.KEEP) return decision
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
        if (usingUdp && firstPingSentMicros >= 0 && !carriesBothWays) {
            val reference = if (lastPingReplyMicros >= 0) lastPingReplyMicros else firstPingSentMicros
            if (nowMicros - reference > pingTimeoutMicros) return Decision.SWITCH_TO_TCP_PING_TIMEOUT
        }

        if (nowMicros - first.atMicros < windowMicros) return Decision.KEEP

        return if (usingUdp) {
            when {
                localDelta == 0 && remoteDelta == 0 -> Decision.SWITCH_TO_TCP_BOTH
                remoteDelta == 0 -> Decision.SWITCH_TO_TCP_SEND
                localDelta == 0 -> Decision.SWITCH_TO_TCP_RECEIVE
                else -> Decision.KEEP
            }
        } else {
            if (localDelta > restoreThreshold && remoteDelta > restoreThreshold) Decision.RESTORE_UDP else Decision.KEEP
        }
    }
}
