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

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.TcpMessageHandler
import se.lublin.humla.protocol.VoicePacketHandler
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.MumbleVersion
import java.io.IOException
import java.net.ConnectException
import java.security.InvalidKeyException
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One connection to a Mumble server. Single-use.
 *
 * All work of the connection runs in one [CoroutineScope], created with the connection and
 * cancelled by [disconnect]. Resolution, parsing, dispatch, voice routing, pings and transport
 * callbacks run strictly in order on the single "humla-protocol" thread; [HumlaConnectionListener]
 * callbacks are posted to [mainHandler], the service's main thread. [sendTCPMessage] and
 * [sendUDPMessage] may be called from any thread. State flags are only ever set, never cleared;
 * the cancelled scope closes [isConnected]/[isSynchronized].
 */
class HumlaConnection @JvmOverloads constructor(
    private val listener: HumlaConnectionListener,
    private val transports: TransportFactory = DefaultTransportFactory(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val nanoClock: () -> Long = System::nanoTime,
    private val udpHealth: UdpHealthMonitor = UdpHealthMonitor(),
    private val udpRestartPolicy: ReconnectPolicy = ReconnectPolicy(
        baseDelayMillis = 1_000L,
        maxDelayMillis = 30_000L,
        maxAttempts = Int.MAX_VALUE,
        maxJitterFraction = 0.0,
    ),
) : HumlaTCP.TCPConnectionListener, HumlaUDP.UDPConnectionListener, MessageHandlerRegistry {

    /**
     * Builds the transports, so tests can supply fakes that never open a socket. Each gets the
     * connection's scope: cancelled on disconnect, dispatching on the protocol thread.
     */
    interface TransportFactory {
        fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport
        fun createUdp(
            cryptState: CryptState,
            listener: HumlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport
    }

    class DefaultTransportFactory : TransportFactory {
        override fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
            HumlaTCP(socketFactory, scope)

        override fun createUdp(
            cryptState: CryptState,
            listener: HumlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport = HumlaUDP(cryptState, listener, scope)
    }

    /** The thread behind the protocol dispatcher; not started by reading this. */
    @VisibleForTesting
    internal val protocolThread = HandlerThread(PROTOCOL_THREAD_NAME)

    /**
     * Started on first access (by [connect]), so an unconnected connection leaks no thread. A looper
     * rather than an executor, so delays run on the looper's clock, which tests can drive.
     */
    @VisibleForTesting
    internal val protocolHandler: Handler by lazy {
        protocolThread.start()
        Handler(protocolThread.looper)
    }
    @VisibleForTesting
    internal val protocolLooper: Looper get() = protocolHandler.looper

    /** Parent of all of the connection's coroutines; cancelled by [disconnect]. */
    private val job = SupervisorJob()

    /**
     * Runs everything on the protocol thread, in submission order. An exception nothing caught ends
     * the connection the way a transport failure does.
     */
    private val scope: CoroutineScope by lazy {
        val onUncaught = CoroutineExceptionHandler { _, e ->
            val reason = HumlaException.HumlaDisconnectReason.OTHER_ERROR
            handleFatalException(HumlaException("Connection failed", e, reason))
        }
        CoroutineScope(job + protocolHandler.asCoroutineDispatcher(PROTOCOL_THREAD_NAME) + onUncaught)
    }

    /** True once no coroutine and no thread of this connection is left. */
    @VisibleForTesting
    internal val isTerminated: Boolean get() = job.isCompleted && !protocolThread.isAlive

    /** True from [disconnect] on; every transport callback is inert from then on. */
    private val closed: Boolean get() = !job.isActive

    /** Guards [connectCalled] and [teardownStarted] against a racing connect/disconnect. */
    private val lifecycleLock = Any()

    // Authentication
    @VisibleForTesting
    internal var certificate: ByteArray? = null
        private set
    @VisibleForTesting
    internal var certificatePassword: String? = null
        private set
    @VisibleForTesting
    internal var trustStorePath: String? = null
        private set
    @VisibleForTesting
    internal var trustStorePassword: String? = null
        private set
    @VisibleForTesting
    internal var trustStoreFormat: String? = null
        private set

    // Networking and protocols
    @Volatile private var tcp: TcpTransport? = null
    @Volatile private var udp: UdpTransport? = null
    @Volatile private var usingUdp = true
    @Volatile @VisibleForTesting
    internal var forceTcp = false
        private set
    @Volatile @VisibleForTesting
    internal var useTor = false
        private set
    /** Written on the protocol thread, only ever to true; read via [isConnected]/[isSynchronized]. */
    @Volatile private var connected = false
    @Volatile private var synchronizedWithServer = false
    @Volatile private var lastError: HumlaException? = null
    private val exceptionHandled = AtomicBoolean(false)
    private val disconnectDelivered = AtomicBoolean(false)
    private var connectCalled = false
    private var teardownStarted = false

    /** [mainHandler]'s thread only. */
    private var disconnectReported = false
    @Volatile private var startTimestamp = 0L // Time that the connection was initiated in nanoseconds
    private val cryptState = CryptState()

    /** Protocol thread only; see [warn]. */
    private var lastWarning: ConnectionWarning? = null
    private var lastWarnedMicros = 0L

    // Latency
    @Volatile private var udpLatency = 0L
    @Volatile private var tcpLatency = 0L

    /** Round trips of the server's replies to our pings. Protocol thread only. */
    private val udpPingStats = PingStats()
    private val tcpPingStats = PingStats()

    // Protocol thread only. Assigned before the TCP transport exists and never cleared:
    // InetAddress.getByName("") resolves to 127.0.0.1, so a UDP start must never see "".
    private var host = ""
    private var port = 0
    @Volatile private var remoteVersion = 0
    @Volatile private var remoteRelease: String? = null
    @Volatile private var remoteOsName: String? = null
    @Volatile private var remoteOsVersion: String? = null

    /** The version this client announces, in the v2 format; with the server's it picks [udpProtocol]. */
    @VisibleForTesting
    internal var clientVersion: Long = MumbleVersion.CLIENT_V2

    /**
     * The format of voice packets and UDP pings, fixed by the server's Version message, which
     * precedes ServerSync and so any voice. Legacy until then.
     */
    @Volatile var udpProtocol = UdpProtocol.LEGACY
        private set

    /** Decodes received voice into [voicePacket]; protocol thread only. */
    private val udpDecoder = UdpPacketDecoder()
    private val voicePacket = VoicePacket()

    @Volatile private var serverMaxBandwidth = 0
    @Volatile private var serverCodec: HumlaUDPMessageType? = null
    /** The server-lacks-Opus warning is shown once per connection; protocol thread only. */
    private var noOpusWarned = false

    // Session
    @Volatile private var sessionId = 0

    // Message handlers (the protocol thread iterates; any thread may add or remove)
    private val tcpHandlers = ConcurrentLinkedQueue<TcpMessageHandler>()
    private val voiceHandlers = ConcurrentLinkedQueue<VoicePacketHandler>()

    /** Sends the pings every [PING_INTERVAL_MILLIS]. Protocol thread. */
    private var pingJob: Job? = null

    /** UDP rebuilds since it last carried traffic, so the backoff is per outage. Protocol thread. */
    private var udpRestartAttempt = 0

    /** Rebuilds the UDP transport after it failed, unless TCP was forced in the meantime. */
    private fun scheduleUdpRestart() {
        udpRestartAttempt += 1
        // No jitter: this is one socket in a live session. Null means the policy gave up.
        val delayMillis = udpRestartPolicy.delayFor(udpRestartAttempt, 0.0) ?: return
        Log.i(TAG, "UDP restart scheduled in $delayMillis ms")
        scope.launch {
            delay(delayMillis)
            if (shouldForceTCP()) return@launch
            Log.i(TAG, "Restarting UDP transport, attempt $udpRestartAttempt")
            startUdp()
        }
    }

    /** Tunnels outgoing voice over TCP and tells the user why. Protocol thread. */
    private fun switchToTcp(warning: ConnectionWarning) {
        usingUdp = false
        warn(warning)
    }

    /** Handles the messages that drive the connection itself; runs before the registered handlers. */
    private fun handleConnectionMessage(msg: MessageLite) {
        when (msg) {
            is Mumble.ServerSync -> onServerSync(msg)
            is Mumble.CodecVersion -> onCodecVersion(msg)
            is Mumble.Reject -> handleFatalException(HumlaException(msg))
            is Mumble.UserRemove -> if (msg.session == sessionId) handleFatalException(HumlaException(msg))
            is Mumble.CryptSetup -> onCryptSetup(msg)
            is Mumble.Version -> {
                remoteVersion = MumbleVersion.legacyOf(msg)
                udpProtocol = UdpProtocol.negotiate(clientVersion, MumbleVersion.v2Of(msg))
                remoteRelease = msg.release
                remoteOsName = msg.os
                remoteOsVersion = msg.osVersion
            }
            is Mumble.Ping -> onPing(msg)
        }
    }

    private fun onServerSync(msg: Mumble.ServerSync) {
        // Protocol says we're supposed to send a dummy UDPTunnel packet here to let the server know we don't like UDP.
        if (shouldForceTCP()) enableForceTCP()

        // Start pinging. FIXME is this the right place?
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                sendPings()
                delay(PING_INTERVAL_MILLIS)
            }
        }

        sessionId = msg.session
        serverMaxBandwidth = if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1
        synchronizedWithServer = true

        notifyListener { onConnectionSynchronized() }
    }

    private fun onCodecVersion(msg: Mumble.CodecVersion) {
        if (msg.opus) {
            serverCodec = HumlaUDPMessageType.UDPVoiceOpus
            return
        }
        // Opus is the only codec this client has; without it there is no voice at all.
        serverCodec = null
        if (!noOpusWarned) {
            noOpusWarned = true
            warn(ConnectionWarning.NO_OPUS)
        }
    }

    private fun onCryptSetup(msg: Mumble.CryptSetup) {
        try {
            if (msg.hasKey() && msg.hasClientNonce() && msg.hasServerNonce()) {
                val key = msg.key
                val clientNonce = msg.clientNonce
                val serverNonce = msg.serverNonce
                if (key.size() == CryptState.AES_BLOCK_SIZE &&
                    clientNonce.size() == CryptState.AES_BLOCK_SIZE &&
                    serverNonce.size() == CryptState.AES_BLOCK_SIZE
                ) {
                    cryptState.setKeys(key.toByteArray(), clientNonce.toByteArray(), serverNonce.toByteArray())
                }
            } else if (msg.hasServerNonce()) {
                val serverNonce = msg.serverNonce
                if (serverNonce.size() == CryptState.AES_BLOCK_SIZE) {
                    cryptState.resync++
                    cryptState.setDecryptIV(serverNonce.toByteArray())
                }
            } else {
                val csb = Mumble.CryptSetup.newBuilder()
                csb.clientNonce = ByteString.copyFrom(cryptState.encryptIV)
                sendTCPMessage(csb.build(), HumlaTCPMessageType.CryptSetup)
            }
        } catch (e: InvalidKeyException) {
            handleFatalException(
                HumlaException(
                    "Received invalid cryptographic nonce from server", e,
                    HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
                )
            )
        }
    }

    private fun onPing(msg: Mumble.Ping) {
        cryptState.remoteGood = msg.good
        cryptState.remoteLate = msg.late
        cryptState.remoteLost = msg.lost
        cryptState.remoteResync = msg.resync

        // In microseconds
        val now = elapsed
        tcpLatency = now - msg.timestamp
        tcpPingStats.add(tcpLatency / MICROS_PER_MILLI)

        // Forced TCP freezes both UDP counters; judging them would falsely report UDP down.
        if (shouldForceTCP()) return

        val decision = udpHealth.onTcpPing(now, cryptState.good, cryptState.remoteGood, usingUdp)
        if (decision == UdpHealthMonitor.Decision.RESTORE_UDP) {
            usingUdp = true
            udpRestartAttempt = 0
            warn(ConnectionWarning.UDP_RESTORED)
        } else {
            switchWarningFor(decision)?.let { switchToTcp(it) }
        }
    }

    private fun onUdpPing(data: ByteArray) {
        val timestamp = UdpPing.decodeTimestamp(udpProtocol, data) ?: return
        val now = elapsed
        udpLatency = now - timestamp
        udpPingStats.add(udpLatency / MICROS_PER_MILLI)
        udpHealth.onUdpPingReply(now)
    }

    private fun sendPings() {
        // In microseconds
        val t = elapsed
        if (!shouldForceTCP()) {
            val ping = UdpPing.encode(udpProtocol, t)
            sendUDPMessage(ping, ping.size, true)
            udpHealth.onUdpPingSent(t)
        }
        val pb = Mumble.Ping.newBuilder()
        pb.timestamp = t
        pb.good = cryptState.good
        pb.late = cryptState.late
        pb.lost = cryptState.lost
        pb.resync = cryptState.resync
        pb.udpPackets = udpPingStats.count
        if (udpPingStats.count > 0) {
            pb.udpPingAvg = udpPingStats.average
            pb.udpPingVar = udpPingStats.variance
        }
        pb.tcpPackets = tcpPingStats.count
        if (tcpPingStats.count > 0) {
            pb.tcpPingAvg = tcpPingStats.average
            pb.tcpPingVar = tcpPingStats.variance
        }
        sendTCPMessage(pb.build(), HumlaTCPMessageType.Ping)
    }

    /**
     * Starts connecting. Resolution (incl. the blocking SRV lookup, skipped over Tor), key store
     * loading and socket creation run on the protocol thread; every outcome goes to the listener.
     */
    fun connect(server: Server): Unit = synchronized(lifecycleLock) {
        check(!connectCalled) { "HumlaConnection is single-use; create a new one for another connection" }
        connectCalled = true
        check(!closed) { "HumlaConnection is single-use; create a new one after disconnect()" }
        usingUdp = !shouldForceTCP()
        startTimestamp = nanoClock()

        scope.launch {
            val socketFactory = try {
                createSocketFactory(server.host ?: "")
            } catch (e: HumlaException) {
                handleFatalException(e)
                return@launch
            }
            // Over Tor the proxy resolves the host; an SRV query would leak it to the local resolver.
            if (useTor) server.resolveWithoutSrv()
            val resolvedHost = server.srvHost
            val resolvedPort = server.srvPort
            // Must be assigned before the transport exists; see the field declaration.
            host = resolvedHost
            port = resolvedPort
            val transport = transports.createTcp(socketFactory, scope)
            transport.setTCPConnectionListener(this@HumlaConnection)
            tcp = transport
            try {
                transport.connect(resolvedHost, resolvedPort, useTor)
            } catch (e: ConnectException) {
                handleFatalException(HumlaException(e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR))
            }
        }
    }

    val isConnected: Boolean get() = connected && !closed

    /** True once ServerSync arrived; don't log user actions before that. */
    val isSynchronized: Boolean get() = synchronizedWithServer && !closed

    /**
     * Whether [sendUDPMessage] would use UDP for an unforced packet. [setForceTCP] mid-connection
     * doesn't change this, so read [shouldForceTCP] alongside it.
     */
    val isUsingUdp: Boolean get() = usingUdp

    /** Microseconds since connect(). */
    val elapsed: Long get() = (nanoClock() - startTimestamp) / 1000

    override fun addTcpHandler(handler: TcpMessageHandler) {
        tcpHandlers.add(handler)
    }

    override fun removeTcpHandler(handler: TcpMessageHandler) {
        tcpHandlers.remove(handler)
    }

    override fun addVoiceHandler(handler: VoicePacketHandler) {
        voiceHandlers.add(handler)
    }

    override fun removeVoiceHandler(handler: VoicePacketHandler) {
        voiceHandlers.remove(handler)
    }

    /** Proxy all connections over a local Orbot instance; forces TCP tunneling for voice. */
    fun setUseTor(useTor: Boolean) {
        this.useTor = useTor
    }

    /** Tunnel all voice packets over TCP, disabling the UDP transport. */
    fun setForceTCP(forceTcp: Boolean) {
        this.forceTcp = forceTcp
    }

    /** PKCS12 certificate data and password to use when authenticating. */
    fun setKeys(certificate: ByteArray?, password: String?) {
        this.certificate = certificate
        this.certificatePassword = password
    }

    fun setTrustStore(path: String?, password: String?, format: String?) {
        trustStorePath = path
        trustStorePassword = password
        trustStoreFormat = format
    }

    fun getServerVersion(): Int = whenSynchronized(remoteVersion)
    fun getServerRelease(): String? = whenSynchronized(remoteRelease)
    fun getServerOSName(): String? = whenSynchronized(remoteOsName)
    fun getServerOSVersion(): String? = whenSynchronized(remoteOsVersion)
    fun getTCPLatency(): Long = whenConnected(tcpLatency)
    fun getUDPLatency(): Long = whenConnected(udpLatency)
    fun getSession(): Int = whenSynchronized(sessionId)

    /** Server-reported maximum input bandwidth in bps, or -1 if not set. */
    fun getMaxBandwidth(): Int = whenSynchronized(serverMaxBandwidth)
    fun getCodec(): HumlaUDPMessageType? = whenSynchronized(serverCodec)

    /** [value], or [IllegalStateException] before ServerSync, which is what sets it. */
    private fun <T> whenSynchronized(value: T): T {
        check(isSynchronized) { "Not synchronized with the server" }
        return value
    }

    private fun <T> whenConnected(value: T): T {
        check(isConnected) { "Not connected" }
        return value
    }

    /** True if TCP is manually forced or Tor is enabled. */
    fun shouldForceTCP(): Boolean = forceTcp || useTor

    /**
     * Shuts down networking. Safe from any thread, idempotent, non-blocking. onConnectionDisconnected
     * is delivered exactly once per started connection, and last.
     */
    fun disconnect() {
        val started: Boolean
        val tearDown: Boolean
        synchronized(lifecycleLock) {
            job.cancel()
            started = connectCalled
            tearDown = started && !teardownStarted
            teardownStarted = true
        }
        // Behind whatever the protocol thread is running, so it never races a transport being
        // built; not cancellable, as the scope it runs in has just been cancelled.
        if (tearDown) {
            scope.launch(NonCancellable) {
                tcp?.disconnect()
                tcp = null
                udp?.disconnect()
                udp = null
                protocolThread.quitSafely()
            }
        }
        if (started) deliverDisconnected() // nothing was ever started, so there is nothing to report
    }

    /** Reports the end of the connection to the listener, at most once. */
    private fun deliverDisconnected() {
        if (!disconnectDelivered.compareAndSet(false, true)) return
        val e = lastError
        mainHandler.post {
            disconnectReported = true
            listener.onConnectionDisconnected(e)
        }
    }

    /** Handles an exception that would cause termination of the connection. Protocol thread. */
    private fun handleFatalException(e: HumlaException) {
        if (!exceptionHandled.compareAndSet(false, true)) return
        lastError = e
        Log.e(TAG, "Fatal connection error: ${e.message}", e)
        disconnect()
    }

    /**
     * Tells the user about the connection, suppressing a repeat of the last delivered warning for
     * [WARNING_REPEAT_MICROS], so on a flapping link the last line matches the current route.
     */
    private fun warn(warning: ConnectionWarning) {
        val now = elapsed
        if (warning == lastWarning && now - lastWarnedMicros < WARNING_REPEAT_MICROS) {
            Log.d(TAG, "Suppressing a repeat of $warning")
            return
        }
        lastWarning = warning
        lastWarnedMicros = now
        notifyListener { onConnectionWarning(warning) }
    }

    /**
     * Queues a listener callback on [mainHandler], dropped at delivery if the disconnect report
     * already ran, which makes onConnectionDisconnected terminal.
     */
    private fun notifyListener(callback: HumlaConnectionListener.() -> Unit) {
        mainHandler.post { if (!disconnectReported) listener.callback() }
    }

    /** [peerHost] is the host the user entered, which the server certificate must match. */
    @Throws(HumlaException::class)
    private fun createSocketFactory(peerHost: String): HumlaSSLSocketFactory {
        try {
            var keyStore: KeyStore? = null
            val cert = certificate
            if (cert != null) {
                keyStore = Pkcs12Certificates.load(cert, certificatePassword)
            }
            return HumlaSSLSocketFactory(
                keyStore, certificatePassword, trustStorePath, trustStorePassword, trustStoreFormat, peerHost
            )
        } catch (e: KeyManagementException) {
            throw HumlaException("Could not recover keys from certificate", e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        } catch (e: KeyStoreException) {
            throw HumlaException("Could not recover keys from certificate", e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        } catch (e: UnrecoverableKeyException) {
            throw HumlaException("Could not recover keys from certificate", e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        } catch (e: IOException) {
            throw HumlaException("Could not read certificate file", e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        } catch (e: CertificateException) {
            throw HumlaException("Could not read certificate", e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        } catch (e: NoSuchAlgorithmException) {
            // Never happens: BouncyCastle ships with the app and provides every algorithm used here.
            throw RuntimeException("We use BouncyCastle- what? ", e)
        } catch (e: NoSuchProviderException) {
            // Never happens, same reason.
            throw RuntimeException("We use BouncyCastle- what? ", e)
        }
    }

    /** Sends a protobuf message over TCP. Can silently fail. */
    fun sendTCPMessage(message: MessageLite, messageType: HumlaTCPMessageType) {
        if (!isConnected) return
        tcp?.sendMessage(message, messageType)
    }

    /**
     * Sends over UDP, or tunnels through TCP unless [force]; the only gate on the voice path.
     * [data] is not kept after this returns, so the caller may reuse it.
     */
    fun sendUDPMessage(data: ByteArray, length: Int, force: Boolean) {
        if (!isConnected) return
        require(length <= data.size) { "Requested length $length is longer than available data length ${data.size}!" }
        val tcpTransport = tcp
        val udpTransport = udp
        if (!force && (shouldForceTCP() || !usingUdp) && tcpTransport != null) {
            tcpTransport.sendMessage(data, length, HumlaTCPMessageType.UDPTunnel)
        } else if (!shouldForceTCP() && udpTransport != null) {
            udpTransport.sendMessage(data, length)
        }
    }

    /** Asks the server to tunnel future voice packets over TCP. */
    private fun enableForceTCP() {
        val utb = Mumble.UDPTunnel.newBuilder()
        utb.packet = ByteString.copyFrom(ByteArray(3))
        sendTCPMessage(utb.build(), HumlaTCPMessageType.UDPTunnel)
    }

    fun sendAccessTokens(tokens: Collection<String>) {
        val ab = Mumble.Authenticate.newBuilder()
        ab.addAllTokens(tokens)
        sendTCPMessage(ab.build(), HumlaTCPMessageType.Authenticate)
    }

    private fun startUdp() {
        val transport = transports.createUdp(cryptState, this, scope)
        udp = transport
        transport.connect(host, port)
    }

    // ---- TCPConnectionListener (protocol thread) ----

    override fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray) {
        // Drop frames that arrive during teardown; the consumer's audio path is already gone.
        if (closed) return
        if (!UNLOGGED_MESSAGES.contains(type)) Log.v(TAG, "IN: $type")

        if (type == HumlaTCPMessageType.UDPTunnel) {
            onUDPDataReceived(data)
            return
        }
        try {
            // Parsed once, so every handler receives the same message object.
            val message = type.parse(data)
            handleConnectionMessage(message)
            for (handler in tcpHandlers) handler.onMessage(message)
        } catch (e: InvalidProtocolBufferException) {
            Log.w(TAG, "Could not parse $type", e)
        } catch (e: RuntimeException) {
            // A single bad message must not kill the protocol thread, and with it the session.
            Log.e(TAG, "Handler failed for $type", e)
        }
    }

    override fun onTCPConnectionEstablished() {
        if (closed) return
        connected = true
        if (!shouldForceTCP()) startUdp()
        notifyListener { onConnectionEstablished() }
    }

    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>) {
        notifyListener { onConnectionHandshakeFailed(chain) }
        // Posted first, so the certificate prompt is queued ahead of the disconnect.
        disconnect()
    }

    override fun onTLSCertificateChanged(chain: Array<X509Certificate>) {
        notifyListener { onConnectionCertificateChanged(chain) }
        disconnect()
    }

    override fun onTCPConnectionFailed(e: HumlaException) {
        // Otherwise an already-reported clean disconnect would gain an error afterwards.
        if (closed) return
        handleFatalException(e)
    }

    override fun onTCPConnectionDisconnect() {
        // No guard needed: disconnect() is idempotent.
        disconnect()
    }

    // ---- UDPConnectionListener (protocol thread) ----

    override fun onUDPDataReceived(data: ByteArray) {
        if (closed || data.isEmpty()) return
        try {
            if (udpProtocol == UdpProtocol.PROTOBUF) onProtobufUdp(data) else onLegacyUdp(data)
        } catch (e: RuntimeException) {
            Log.e(TAG, "UDP handler failed", e)
        }
    }

    private fun onProtobufUdp(data: ByteArray) {
        when (data[0].toInt() and BYTE_MASK) {
            UdpAudioEncoder.PROTOBUF_PING -> onUdpPing(data)
            UdpAudioEncoder.PROTOBUF_AUDIO ->
                if (udpDecoder.decodeProtobuf(data, 1, data.size - 1, voicePacket)) dispatchVoice()
            // Unknown message types are dropped.
        }
    }

    private fun onLegacyUdp(data: ByteArray) {
        if ((data[0].toInt() and BYTE_MASK) ushr LEGACY_TYPE_SHIFT == HumlaUDPMessageType.UDPPing.ordinal) {
            onUdpPing(data)
        } else if (udpDecoder.decodeLegacy(data, data.size, voicePacket)) {
            dispatchVoice()
        }
    }

    private fun dispatchVoice() {
        for (handler in voiceHandlers) handler.onVoicePacket(voicePacket)
    }

    override fun onUDPConnectionError(e: Exception) {
        Log.w(TAG, "UDP connection thread failed", e)
        usingUdp = false
        warn(ConnectionWarning.UDP_THREAD_FAILED)
        enableForceTCP()
        scheduleUdpRestart()
    }

    override fun resyncCryptState() {
        // Through sendTCPMessage so the isConnected check applies.
        sendTCPMessage(Mumble.CryptSetup.newBuilder().build(), HumlaTCPMessageType.CryptSetup)
    }

    /** If the connection was lost due to an error, the exception; else null. */
    val error: HumlaException? get() = lastError

    /** Every method is called on the main thread. */
    interface HumlaConnectionListener {
        /** Called when the socket to the remote server has opened. */
        fun onConnectionEstablished()

        /** Called when the protocol handshake completes. */
        fun onConnectionSynchronized()

        /** Certificate verification failed; [onConnectionDisconnected] still follows. */
        fun onConnectionHandshakeFailed(chain: Array<X509Certificate>)
        fun onConnectionCertificateChanged(chain: Array<X509Certificate>)

        /** [e] is null for a clean disconnect. Exactly once per started connection, and last. */
        fun onConnectionDisconnected(e: HumlaException?)

        /** Called if the user should be notified of a connection-related warning. */
        fun onConnectionWarning(warning: ConnectionWarning)
    }

    companion object {
        private val TAG: String = HumlaConnection::class.java.name
        private const val PROTOCOL_THREAD_NAME = "humla-protocol"
        private const val PING_INTERVAL_MILLIS = 5_000L
        private const val MICROS_PER_MILLI = 1_000.0
        private const val BYTE_MASK = 0xFF
        private const val LEGACY_TYPE_SHIFT = 5

        /** How long a repeat of the last delivered [ConnectionWarning] stays suppressed. */
        private const val WARNING_REPEAT_MICROS = 60_000_000L

        /** The warning for a decision that takes voice off UDP, else null. */
        internal fun switchWarningFor(decision: UdpHealthMonitor.Decision): ConnectionWarning? = when (decision) {
            UdpHealthMonitor.Decision.KEEP, UdpHealthMonitor.Decision.RESTORE_UDP -> null
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_BOTH -> ConnectionWarning.UDP_UNAVAILABLE
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_SEND -> ConnectionWarning.UDP_SEND_FAILED
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_RECEIVE -> ConnectionWarning.UDP_RECEIVE_FAILED
            UdpHealthMonitor.Decision.SWITCH_TO_TCP_PING_TIMEOUT -> ConnectionWarning.UDP_PING_TIMEOUT
        }

        /** Message types that aren't shown in logcat, for annoying types like UDPTunnel. */
        @JvmField
        val UNLOGGED_MESSAGES: Set<HumlaTCPMessageType> =
            setOf(HumlaTCPMessageType.UDPTunnel, HumlaTCPMessageType.Ping)

        // Tor connection details
        const val TOR_HOST = "localhost"
        const val TOR_PORT = 9050

        /** Bandwidth in bps for audio with these parameters, including packet overhead. */
        @JvmStatic
        fun calculateAudioBandwidth(bitrate: Int, framesPerPacket: Int): Int {
            // FIXME: assumes worst-case using TCP
            var overhead = 20 + 8 + 4 + 1 + 2 + 12 + framesPerPacket
            overhead *= (800 / framesPerPacket)
            return overhead + bitrate
        }
    }
}
