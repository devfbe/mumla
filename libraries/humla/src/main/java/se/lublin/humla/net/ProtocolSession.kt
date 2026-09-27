/*
 * Copyright (C) 2014 Andrew Comminos
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

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Latency
import se.lublin.humla.model.ServerInfo
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaLog
import se.lublin.humla.util.MumbleVersion
import java.security.InvalidKeyException

/** A tunnelled dummy packet; its arrival tells the server to tunnel voice over TCP from now on. */
private const val FORCE_TCP_PACKET_BYTES = 3

private const val NANOS_PER_MICRO = 1000
private const val MICROS_PER_MILLI = 1_000.0
private const val PING_INTERVAL_MILLIS = 5_000L

/** How long a repeat of the last delivered [ConnectionWarning] stays suppressed. */
private const val WARNING_REPEAT_MICROS = 60_000_000L

/**
 * The client side of the Mumble protocol on one connection, above the transports: the handshake's
 * outcome ([ServerInfo] at ServerSync), the codec, the crypt keys and their resyncs, the pings with
 * their statistics, and where voice goes - over UDP, or tunnelled through TCP while UDP is down,
 * with a restart of the UDP transport after it failed.
 *
 * Confined to the protocol context ([Link.scope]), except for the properties marked volatile.
 *
 * @param tunnelVoice Voice goes through TCP for the whole connection (forced TCP or Tor); UDP is
 *   never started and never judged.
 * @param nanoClock The clock of [elapsedMicros], replaceable so tests can set the time.
 */
// One handler per message the protocol reacts to; the link and the tuning tests replace.
@Suppress("LongParameterList", "TooManyFunctions")
internal class ProtocolSession(
    private val link: Link,
    private val tunnelVoice: Boolean,
    private val clientVersion: Long = MumbleVersion.CLIENT_V2,
    private val udpHealth: UdpHealthMonitor = UdpHealthMonitor(),
    private val udpRestartPolicy: ReconnectPolicy = UDP_RESTART_POLICY,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    /** What the protocol needs of the transports below it. */
    interface Link {
        /** The protocol context: everything of the connection runs here, in order. */
        val scope: CoroutineScope
        val cryptState: CryptState

        /** Where the connection went; known before any server message arrives. */
        val endpoint: Endpoint

        fun sendTcp(message: MessageLite, type: HumlaTCPMessageType)

        /** Sends over UDP, whichever way voice currently goes. */
        fun sendUdp(data: ByteArray, length: Int)

        /** Builds and connects a new UDP transport. */
        fun startUdp()

        fun onSynchronized()
        fun onWarning(warning: ConnectionWarning)

        /** Ends the connection with [error]. */
        fun fail(error: HumlaException)
    }

    @Volatile private var startNanos = 0L

    /** Microseconds since [start]. */
    val elapsedMicros: Long get() = (nanoClock() - startNanos) / NANOS_PER_MICRO

    /** Whether unforced voice goes over UDP rather than through the TCP tunnel. */
    @Volatile var usingUdp: Boolean = !tunnelVoice
        private set

    /**
     * The format of voice packets and UDP pings, fixed by the server's Version message, which
     * precedes ServerSync and so any voice. Legacy until then.
     */
    @Volatile var udpProtocol: UdpProtocol = UdpProtocol.LEGACY
        private set

    /** Null until ServerSync. */
    @Volatile var serverInfo: ServerInfo? = null
        private set

    @Volatile private var tcpLatency = 0L
    @Volatile private var udpLatency = 0L
    val latency: Latency get() = Latency(tcpLatency, udpLatency)

    private var version: Mumble.Version? = null
    private var opus = false
    private var noOpusWarned = false
    private var session = -1

    private val udpPingStats = PingStats()
    private val tcpPingStats = PingStats()
    private var pingJob: Job? = null

    /** UDP rebuilds since it last carried traffic, so the backoff is per outage. */
    private var udpRestartAttempt = 0

    private var lastWarning: ConnectionWarning? = null
    private var lastWarnedMicros = 0L

    /** Starts the clock; the connection is about to be opened. Any thread. */
    fun start() {
        startNanos = nanoClock()
    }

    /** The TLS connection is up. */
    fun onEstablished() {
        if (!tunnelVoice) link.startUdp()
    }

    /**
     * Handles what drives the connection itself. Runs before the registered handlers, except for
     * a UserRemove: a removal of our own session ends the connection, and the model must still
     * name the actor when that end is reported.
     */
    fun onMessage(message: MessageLite) {
        when (message) {
            is Mumble.ServerSync -> onServerSync(message)
            is Mumble.CodecVersion -> onCodecVersion(message)
            is Mumble.Reject -> link.fail(HumlaException(message))
            is Mumble.UserRemove -> if (message.session == session) link.fail(HumlaException(message))
            is Mumble.CryptSetup -> onCryptSetup(message)
            is Mumble.Version -> {
                version = message
                udpProtocol = UdpProtocol.negotiate(clientVersion, MumbleVersion.v2Of(message))
            }
            is Mumble.Ping -> onPing(message)
        }
    }

    private fun onServerSync(message: Mumble.ServerSync) {
        // The protocol's way of telling the server to tunnel our voice: a dummy UDPTunnel packet.
        if (tunnelVoice) sendTunnelRequest()
        pingJob?.cancel()
        pingJob = link.scope.launch {
            while (true) {
                sendPings()
                delay(PING_INTERVAL_MILLIS)
            }
        }
        session = message.session
        val version = version
        serverInfo = ServerInfo(
            host = link.endpoint.host,
            port = link.endpoint.port,
            release = version?.release,
            osName = version?.os,
            osVersion = version?.osVersion,
            version = version?.let(MumbleVersion::legacyOf) ?: 0,
            maxBandwidth = if (message.hasMaxBandwidth()) message.maxBandwidth else -1,
            opus = opus,
        )
        link.onSynchronized()
    }

    private fun onCodecVersion(message: Mumble.CodecVersion) {
        opus = message.opus
        serverInfo = serverInfo?.copy(opus = opus)
        if (!opus && !noOpusWarned) {
            noOpusWarned = true
            warn(ConnectionWarning.NO_OPUS)
        }
    }

    private fun onCryptSetup(message: Mumble.CryptSetup) {
        val crypt = link.cryptState
        try {
            if (message.hasKey() && message.hasClientNonce() && message.hasServerNonce()) {
                val key = message.key
                val clientNonce = message.clientNonce
                val serverNonce = message.serverNonce
                if (key.size() == CryptState.AES_BLOCK_SIZE &&
                    clientNonce.size() == CryptState.AES_BLOCK_SIZE &&
                    serverNonce.size() == CryptState.AES_BLOCK_SIZE
                ) {
                    crypt.setKeys(key.toByteArray(), clientNonce.toByteArray(), serverNonce.toByteArray())
                }
            } else if (message.hasServerNonce()) {
                val serverNonce = message.serverNonce
                if (serverNonce.size() == CryptState.AES_BLOCK_SIZE) {
                    crypt.resync++
                    crypt.setDecryptIV(serverNonce.toByteArray())
                }
            } else {
                val reply = Mumble.CryptSetup.newBuilder().setClientNonce(ByteString.copyFrom(crypt.encryptIV))
                link.sendTcp(reply.build(), HumlaTCPMessageType.CryptSetup)
            }
        } catch (e: InvalidKeyException) {
            link.fail(
                HumlaException(
                    "Received invalid cryptographic nonce from server", e,
                    HumlaException.HumlaDisconnectReason.CONNECTION_ERROR,
                )
            )
        }
    }

    /** Received UDP packets stopped decrypting; asks the server for a fresh nonce. */
    fun requestCryptResync() {
        link.sendTcp(Mumble.CryptSetup.newBuilder().build(), HumlaTCPMessageType.CryptSetup)
    }

    private fun onPing(message: Mumble.Ping) {
        val crypt = link.cryptState
        crypt.remoteGood = message.good
        crypt.remoteLate = message.late
        crypt.remoteLost = message.lost
        crypt.remoteResync = message.resync

        val now = elapsedMicros
        tcpLatency = now - message.timestamp
        tcpPingStats.add(tcpLatency / MICROS_PER_MILLI)

        // Tunnelled voice freezes both UDP counters; judging them would falsely report UDP down.
        if (tunnelVoice) return

        val decision = udpHealth.onTcpPing(now, crypt.good, crypt.remoteGood, usingUdp)
        if (decision == UdpHealthMonitor.Decision.RESTORE_UDP) {
            usingUdp = true
            udpRestartAttempt = 0
            warn(ConnectionWarning.UDP_RESTORED)
        } else {
            switchWarningFor(decision)?.let(::switchToTcp)
        }
    }

    /** A UDP ping came back: over UDP, or tunnelled. */
    fun onUdpPing(data: ByteArray) {
        val timestamp = UdpPing.decodeTimestamp(udpProtocol, data) ?: return
        val now = elapsedMicros
        udpLatency = now - timestamp
        udpPingStats.add(udpLatency / MICROS_PER_MILLI)
        udpHealth.onUdpPingReply(now)
    }

    private fun sendPings() {
        val now = elapsedMicros
        if (!tunnelVoice) {
            val ping = UdpPing.encode(udpProtocol, now)
            link.sendUdp(ping, ping.size)
            udpHealth.onUdpPingSent(now)
        }
        val crypt = link.cryptState
        val ping = Mumble.Ping.newBuilder()
            .setTimestamp(now)
            .setGood(crypt.good)
            .setLate(crypt.late)
            .setLost(crypt.lost)
            .setResync(crypt.resync)
            .setUdpPackets(udpPingStats.count)
            .setTcpPackets(tcpPingStats.count)
        if (udpPingStats.count > 0) ping.setUdpPingAvg(udpPingStats.average).setUdpPingVar(udpPingStats.variance)
        if (tcpPingStats.count > 0) ping.setTcpPingAvg(tcpPingStats.average).setTcpPingVar(tcpPingStats.variance)
        link.sendTcp(ping.build(), HumlaTCPMessageType.Ping)
    }

    /** The UDP transport's socket failed: tunnels the voice and rebuilds the transport later. */
    fun onUdpFailed(error: Exception) {
        HumlaLog.w(TAG, "UDP connection failed", error)
        switchToTcp(ConnectionWarning.UDP_THREAD_FAILED)
        sendTunnelRequest()
        udpRestartAttempt += 1
        // No jitter: this is one socket in a live session. Null means the policy gave up.
        val delayMillis = udpRestartPolicy.delayFor(udpRestartAttempt, 0.0) ?: return
        HumlaLog.i(TAG, "UDP restart scheduled in $delayMillis ms")
        link.scope.launch {
            delay(delayMillis)
            HumlaLog.i(TAG, "Restarting UDP transport, attempt $udpRestartAttempt")
            link.startUdp()
        }
    }

    private fun switchToTcp(warning: ConnectionWarning) {
        usingUdp = false
        warn(warning)
    }

    private fun sendTunnelRequest() {
        val packet = Mumble.UDPTunnel.newBuilder().setPacket(ByteString.copyFrom(ByteArray(FORCE_TCP_PACKET_BYTES)))
        link.sendTcp(packet.build(), HumlaTCPMessageType.UDPTunnel)
    }

    /**
     * Tells the user about the connection, suppressing a repeat of the last delivered warning for
     * [WARNING_REPEAT_MICROS], so on a flapping link the last line matches the current route.
     */
    private fun warn(warning: ConnectionWarning) {
        val now = elapsedMicros
        if (warning == lastWarning && now - lastWarnedMicros < WARNING_REPEAT_MICROS) {
            HumlaLog.d(TAG, "Suppressing a repeat of $warning")
            return
        }
        lastWarning = warning
        lastWarnedMicros = now
        link.onWarning(warning)
    }

    companion object {
        private const val TAG = "ProtocolSession"

        /** Unbounded, without jitter: one socket of a live session, retried until it ends. */
        val UDP_RESTART_POLICY = ReconnectPolicy(
            baseDelayMillis = 1_000L,
            maxDelayMillis = 30_000L,
            maxAttempts = Int.MAX_VALUE,
            maxJitterFraction = 0.0,
        )

        /** The warning for a decision that takes voice off UDP, else null. */
        fun switchWarningFor(decision: UdpHealthMonitor.Decision): ConnectionWarning? = when (decision) {
            UdpHealthMonitor.Decision.KEEP, UdpHealthMonitor.Decision.RESTORE_UDP -> null
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_BOTH -> ConnectionWarning.UDP_UNAVAILABLE
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_SEND -> ConnectionWarning.UDP_SEND_FAILED
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_RECEIVE -> ConnectionWarning.UDP_RECEIVE_FAILED
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_PING_TIMEOUT -> ConnectionWarning.UDP_PING_TIMEOUT
        }
    }
}
