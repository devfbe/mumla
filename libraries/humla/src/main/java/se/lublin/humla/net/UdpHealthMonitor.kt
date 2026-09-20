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
 * - **No reply to a UDP ping for [pingTimeoutMicros], while the window does not vouch for the
 *   link.** It is evidence of last resort, and the qualification is the whole of it. The reason
 *   first written here - "the counters keep climbing while voice still arrives" - does not survive
 *   reading what the counters are: `remoteGood` is what the *server* decrypted, so if it climbs the
 *   server is hearing us, and a link whose counters both climb is carrying voice in both
 *   directions by definition. Measured on a Galaxy S25 against a real server: voice worked
 *   throughout and the reply to our own UDP ping never once came back, and this half took the call
 *   off UDP every fifteen seconds. So the timeout may fire only where the window is silent in at
 *   least one direction - which is the firewall case it was written for, and it still fires there
 *   before a full window has accumulated.
 *
 * On top of both, **hysteresis**: a decision is not reversed inside one window of taking it. A
 * window is the shortest history this class can judge, so reversing a decision sooner is reversing
 * it on evidence it already had. Two state changes inside one window are always a fault of the
 * procedure, never a state of the network.
 *
 * Two properties of the window that are scoped rather than guaranteed, both measured:
 *
 * - **The effective window widens across a gap in the TCP pings.** The head is kept until the
 *   sample behind it is a whole window old, so after a gap the base can be much older than
 *   [windowMicros]: samples at 0 s and 60 s carrying three packets each way yield RESTORE_UDP,
 *   although three packets in sixty seconds are well under what the threshold is meant to ask for.
 *   It is conservative where it switches away and permissive where it restores, which is the safe
 *   way round of the two, and capping the window a second time would reintroduce the late-ping bug
 *   the trim above exists to avoid. Deliberately not fixed.
 * - **[samples] is bounded by the *server's* ping rate, not by this class.** The trim keeps roughly
 *   [windowMicros] worth of them, so at the protocol's five second ping that is five samples and at
 *   a server that pings a thousand times a second it is twenty thousand - a few hundred kilobytes
 *   that the window sheds again as it moves, not a leak and not a crash. A cap belongs on the
 *   frame boundary where the server-controlled rate enters, next to the channel depth guard, not
 *   here; writing one here would be a branch no test of this class can reach.
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

        val first = samples.first()
        val localDelta = localGood - first.localGood
        val remoteDelta = remoteGood - first.remoteGood
        // Positive evidence needs no full window. "Silent for a window" is a claim that needs the
        // whole window under it; "carrying right now" needs one counted packet each way, and the
        // two are not symmetric. This is what the ping timeout is measured against.
        val carriesBothWays = localDelta > 0 && remoteDelta > 0

        val decision = decide(nowMicros, usingUdp, first, localDelta, remoteDelta, carriesBothWays)
        if (decision == Decision.KEEP) return decision
        // Every non-KEEP decision here is a state change: the switch arms are reachable only while
        // UDP is in use and RESTORE_UDP only while it is not, so one counter serves both directions
        // of the lockout.
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
        // Still ahead of the window test: a link that is silent one way and answers no ping is
        // decidable from the first send onwards, and waiting for a full window would add five
        // seconds of one-way voice to every one of these. What changed is `!carriesBothWays`, and
        // it is the fix for a measured flap - see the class doc.
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
            // Both directions, because voice that only goes one way is not a working connection -
            // and `and` rather than `or` is a claim about the two corners where they disagree, which
            // doNotRestoreUdpWhenOnlyThe{Receiving,Sending}DirectionRecovers are.
            if (localDelta > restoreThreshold && remoteDelta > restoreThreshold) Decision.RESTORE_UDP else Decision.KEEP
        }
    }
}
