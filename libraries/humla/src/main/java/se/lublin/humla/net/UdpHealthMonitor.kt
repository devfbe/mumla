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
 * Decides whether voice should flow over UDP or be tunneled over TCP (spec A5).
 *
 * Two independent reasons to give up on UDP, and they are separate because they fail separately:
 *
 * - **No packets in the last [windowMicros].** The counters the server and the crypt state keep are
 *   cumulative, so the check this replaces ("either count is still zero") answered a question about
 *   the whole session: a connection that carried one packet in its first second and nothing since
 *   read as healthy for as long as it lasted. A delta over a sliding window asks about now.
 * - **No reply to a UDP ping for [pingTimeoutMicros].** The window above cannot see this on its
 *   own, because the counters it reads keep climbing while voice still arrives - a firewall that
 *   drops our side of the flow shows up here first.
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

    init {
        // A zero or negative window is not a crash, which is what makes it worth refusing: the trim
        // then always reduces to the newest sample alone, every delta is zero against itself, and
        // the monitor answers SWITCH_TO_TCP_BOTH forever without anything looking wrong.
        require(windowMicros > 0) { "windowMicros must be positive, was $windowMicros" }
        // Word for word the same argument, and it was missing because this parameter had only ever
        // been driven at its default: `nowMicros - reference > 0` is already true at the ping that
        // records the first send, so a non-positive timeout tunnels every connection's voice from
        // its first ping onwards and never lets it back.
        require(pingTimeoutMicros > 0) { "pingTimeoutMicros must be positive, was $pingTimeoutMicros" }
    }

    /**
     * A UDP ping went out. Only the *first* send is remembered, and that is the whole point of the
     * field: it answers "has UDP ever been asked anything" for the case where no reply has ever
     * come back. The connection sends a ping every five seconds, so a reference that moved with
     * each send would never be [pingTimeoutMicros] old and this half of spec A5 would be dead code
     * - which is what aResentPingDoesNotRestartTheTimeoutClock fails on when the condition goes.
     * Once a reply has arrived, [lastPingReplyMicros] answers instead and this field is unread.
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
        // The head goes only once the sample behind it can take over as the base, i.e. is itself a
        // whole window old. Dropping every sample older than the window instead - which is the
        // obvious way to write this and the way it was written first - throws away the only sample
        // that could make the window full: pings are `postDelayed(5 s)` and land a few milliseconds
        // late, the lateness accumulates, and from the first late tick onwards the oldest surviving
        // sample is always younger than a window. Measured: the decision is then never taken again
        // for the rest of the session. aWindowOfLatePingsStillReachesADecision is the difference.
        while (samples.size > 1 && nowMicros - samples[1].atMicros >= windowMicros) samples.removeFirst()

        // Ahead of the window test on purpose: a ping that never comes back is decidable from the
        // first send onwards, and waiting for a full window of samples would add five seconds of
        // one-way voice to every one of these.
        if (usingUdp && firstPingSentMicros >= 0) {
            val reference = if (lastPingReplyMicros >= 0) lastPingReplyMicros else firstPingSentMicros
            if (nowMicros - reference > pingTimeoutMicros) return Decision.SWITCH_TO_TCP_PING_TIMEOUT
        }

        val first = samples.first()
        if (nowMicros - first.atMicros < windowMicros) return Decision.KEEP // window not full yet

        val localDelta = localGood - first.localGood
        val remoteDelta = remoteGood - first.remoteGood
        return if (usingUdp) {
            when {
                localDelta == 0 && remoteDelta == 0 -> Decision.SWITCH_TO_TCP_BOTH
                remoteDelta == 0 -> Decision.SWITCH_TO_TCP_SEND
                localDelta == 0 -> Decision.SWITCH_TO_TCP_RECEIVE
                else -> Decision.KEEP
            }
        } else {
            // Both directions, because voice that only goes one way is not a working connection -
            // and `and` rather than `or` is a claim about the two corners where they disagree, which
            // doesNotRestoreUdpWhenOnlyThe{Receiving,Sending}DirectionRecovers are.
            if (localDelta > restoreThreshold && remoteDelta > restoreThreshold) Decision.RESTORE_UDP else Decision.KEEP
        }
    }
}
