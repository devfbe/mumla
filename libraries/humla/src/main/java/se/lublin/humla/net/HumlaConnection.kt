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
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.util.HumlaException
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

/**
 * One connection to a Mumble server. Single-use.
 *
 * Resolution, sockets, parsing, dispatch, voice routing, pings and transport callbacks run on the
 * "humla-protocol" [HandlerThread]; [HumlaConnectionListener] callbacks are posted to [mainHandler],
 * which must be the thread `HumlaCallbacks` delivers on. [sendTCPMessage] and [sendUDPMessage] may
 * be called from any thread. State flags are only ever set, never cleared; [disconnect] closes
 * [isConnected]/[isSynchronized] via [disconnectRequested].
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

    /** Builds the transports, so tests can supply fakes that never open a socket. */
    interface TransportFactory {
        fun createTcp(socketFactory: HumlaSSLSocketFactory, callbackHandler: Handler): TcpTransport
        fun createUdp(cryptState: CryptState, listener: HumlaUDP.UDPConnectionListener, callbackHandler: Handler): UdpTransport
    }

    class DefaultTransportFactory : TransportFactory {
        override fun createTcp(socketFactory: HumlaSSLSocketFactory, callbackHandler: Handler): TcpTransport =
            HumlaTCP(socketFactory, callbackHandler)

        override fun createUdp(cryptState: CryptState, listener: HumlaUDP.UDPConnectionListener, callbackHandler: Handler): UdpTransport =
            HumlaUDP(cryptState, listener, callbackHandler)
    }

    /** Not started by reading this; [protocolHandler] starts it. */
    internal val protocolThread = HandlerThread(PROTOCOL_THREAD_NAME)

    /** Started on first access (by [connect]), so an unconnected connection leaks no thread. */
    val protocolHandler: Handler by lazy {
        protocolThread.start()
        Handler(protocolThread.looper)
    }
    val protocolLooper: Looper get() = protocolHandler.looper

    // Authentication
    private var certificate: ByteArray? = null
    private var certificatePassword: String? = null
    private var trustStorePath: String? = null
    private var trustStorePassword: String? = null
    private var trustStoreFormat: String? = null

    // Networking and protocols
    @Volatile private var tcp: TcpTransport? = null
    @Volatile private var udp: UdpTransport? = null
    @Volatile private var usingUdp = true
    @Volatile private var forceTcp = false
    @Volatile private var useTor = false
    /** Written on the protocol thread, only ever to true; read via [isConnected]/[isSynchronized]. */
    @Volatile private var connected = false
    @Volatile private var synchronizedWithServer = false
    @Volatile private var lastError: HumlaException? = null
    private val exceptionHandled = AtomicBoolean(false)
    private val disconnectDelivered = AtomicBoolean(false)
    @Volatile private var connectCalled = false
    @Volatile private var disconnectRequested = false

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

    // Protocol thread only. Assigned before the TCP transport exists and never cleared:
    // InetAddress.getByName("") resolves to 127.0.0.1, so a UDP start must never see "".
    private var host = ""
    private var port = 0
    @Volatile private var remoteVersion = 0
    @Volatile private var remoteRelease: String? = null
    @Volatile private var remoteOsName: String? = null
    @Volatile private var remoteOsVersion: String? = null
    @Volatile private var serverMaxBandwidth = 0
    @Volatile private var serverCodec: HumlaUDPMessageType? = null

    // Session
    @Volatile private var sessionId = 0

    // Message handlers (the protocol thread iterates; any thread may add or remove)
    private val tcpHandlers = ConcurrentLinkedQueue<HumlaTCPMessageListener>()
    private val udpHandlers = ConcurrentLinkedQueue<HumlaUDPMessageListener>()

    /** Sends the pings and reschedules itself; quitSafely drops a not-yet-due reschedule. */
    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPings()
            protocolHandler.postDelayed(this, PING_INTERVAL_MILLIS)
        }
    }

    /** UDP rebuilds since it last carried traffic, so the backoff is per outage. Protocol thread. */
    private var udpRestartAttempt = 0

    /**
     * Rebuilds the UDP transport after its thread died. Guarded by [disconnectRequested]: quitSafely
     * still runs due posts, and a post-teardown restart would open a socket nothing closes.
     */
    private val udpRestartRunnable = Runnable {
        if (disconnectRequested || shouldForceTCP()) return@Runnable
        Log.i(TAG, "Restarting UDP transport, attempt $udpRestartAttempt")
        startUdp()
    }

    private fun scheduleUdpRestart() {
        udpRestartAttempt += 1
        // No jitter: this is one socket in a live session. Null means the policy gave up.
        val delay = udpRestartPolicy.delayFor(udpRestartAttempt, 0.0) ?: return
        Log.i(TAG, "UDP restart scheduled in $delay ms")
        protocolHandler.postDelayed(udpRestartRunnable, delay)
    }

    /** Tunnels outgoing voice over TCP and tells the user why. Protocol thread. */
    private fun switchToTcp(warning: ConnectionWarning) {
        usingUdp = false
        warn(warning)
    }

    /** Handles packets received that are critical to the connection state (protocol thread). */
    private val connectionMessageHandler = object : HumlaTCPMessageListener.Stub() {
        override fun messageServerSync(msg: Mumble.ServerSync) {
            // Protocol says we're supposed to send a dummy UDPTunnel packet here to let the server know we don't like UDP.
            if (shouldForceTCP()) enableForceTCP()

            // Start pinging. FIXME is this the right place?
            protocolHandler.removeCallbacks(pingRunnable)
            protocolHandler.post(pingRunnable)

            sessionId = msg.session
            serverMaxBandwidth = if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1
            synchronizedWithServer = true

            notifyListener { onConnectionSynchronized() }
        }

        override fun messageCodecVersion(msg: Mumble.CodecVersion) {
            serverCodec = when {
                msg.hasOpus() && msg.opus -> HumlaUDPMessageType.UDPVoiceOpus
                msg.hasBeta() && !msg.preferAlpha -> HumlaUDPMessageType.UDPVoiceCELTBeta
                else -> HumlaUDPMessageType.UDPVoiceCELTAlpha
            }
        }

        override fun messageReject(msg: Mumble.Reject) {
            handleFatalException(HumlaException(msg))
        }

        override fun messageUserRemove(msg: Mumble.UserRemove) {
            if (msg.session == sessionId) {
                handleFatalException(HumlaException(msg))
            }
        }

        override fun messageCryptSetup(msg: Mumble.CryptSetup) {
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
                        cryptState.mUiResync++
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

        override fun messageVersion(msg: Mumble.Version) {
            remoteVersion = msg.version
            remoteRelease = msg.release
            remoteOsName = msg.os
            remoteOsVersion = msg.osVersion
        }

        override fun messagePing(msg: Mumble.Ping) {
            cryptState.mUiRemoteGood = msg.good
            cryptState.mUiRemoteLate = msg.late
            cryptState.mUiRemoteLost = msg.lost
            cryptState.mUiRemoteResync = msg.resync

            // In microseconds
            val now = elapsed
            tcpLatency = now - msg.timestamp

            // Forced TCP freezes both UDP counters; judging them would falsely report UDP down.
            if (shouldForceTCP()) return

            val decision = udpHealth.onTcpPing(now, cryptState.mUiGood, cryptState.mUiRemoteGood, usingUdp)
            if (decision == UdpHealthMonitor.Decision.RESTORE_UDP) {
                usingUdp = true
                udpRestartAttempt = 0
                warn(ConnectionWarning.UDP_RESTORED)
            } else {
                switchWarningFor(decision)?.let { switchToTcp(it) }
            }
        }
    }

    private val udpPingListener = object : HumlaUDPMessageListener.Stub() {
        override fun messageUDPPing(data: ByteArray) {
            val timestamp = UdpPing.decodeTimestamp(data) ?: return
            val now = elapsed
            udpLatency = now - timestamp
            udpHealth.onUdpPingReply(now)
        }
    }

    private fun sendPings() {
        // In microseconds
        val t = elapsed
        if (!shouldForceTCP()) {
            val ping = UdpPing.encode(t)
            sendUDPMessage(ping, ping.size, true)
            udpHealth.onUdpPingSent(t)
        }
        val pb = Mumble.Ping.newBuilder()
        pb.timestamp = t
        pb.good = cryptState.mUiGood
        pb.late = cryptState.mUiLate
        pb.lost = cryptState.mUiLost
        pb.resync = cryptState.mUiResync
        // TODO accumulate stats and send with ping
        sendTCPMessage(pb.build(), HumlaTCPMessageType.Ping)
    }

    init {
        tcpHandlers.add(connectionMessageHandler)
        udpHandlers.add(udpPingListener)
    }

    /**
     * Starts connecting. Resolution (incl. the blocking SRV lookup, skipped over Tor), key store
     * loading and socket creation run on the protocol thread; every outcome goes to the listener.
     */
    fun connect(server: Server) {
        // Written before disconnectRequested is read (disconnect() mirrors this).
        check(!connectCalled) { "HumlaConnection is single-use; create a new one for another connection" }
        connectCalled = true
        check(!disconnectRequested) { "HumlaConnection is single-use; create a new one after disconnect()" }
        usingUdp = !shouldForceTCP()
        startTimestamp = nanoClock()

        protocolHandler.post {
            if (disconnectRequested) {
                // disconnect() ran before the thread existed, so it could not post the teardown.
                quitProtocolThread()
                return@post
            }
            val socketFactory = try {
                createSocketFactory(server.host ?: "")
            } catch (e: HumlaException) {
                handleFatalException(e)
                return@post
            }
            // Over Tor the proxy resolves the host; an SRV query would leak it to the local resolver.
            if (useTor) server.resolveWithoutSrv()
            val resolvedHost = server.srvHost
            val resolvedPort = server.srvPort
            // Must be assigned before the transport exists; see the field declaration.
            host = resolvedHost
            port = resolvedPort
            val transport = transports.createTcp(socketFactory, protocolHandler)
            transport.setTCPConnectionListener(this)
            tcp = transport
            try {
                transport.connect(resolvedHost, resolvedPort, useTor)
            } catch (e: ConnectException) {
                handleFatalException(HumlaException(e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR))
            }
        }
    }

    val isConnected: Boolean get() = connected && !disconnectRequested

    /** True once ServerSync arrived; don't log user actions before that. */
    val isSynchronized: Boolean get() = synchronizedWithServer && !disconnectRequested

    /**
     * Whether [sendUDPMessage] would use UDP for an unforced packet. [setForceTCP] mid-connection
     * doesn't change this, so read [shouldForceTCP] alongside it.
     */
    val isUsingUdp: Boolean get() = usingUdp

    /** Microseconds since connect(). */
    val elapsed: Long get() = (nanoClock() - startTimestamp) / 1000

    override fun addTCPMessageHandlers(vararg handlers: HumlaTCPMessageListener) {
        tcpHandlers.addAll(handlers)
    }

    override fun removeTCPMessageHandler(handler: HumlaTCPMessageListener) {
        tcpHandlers.remove(handler)
    }

    override fun addUDPMessageHandlers(vararg handlers: HumlaUDPMessageListener) {
        udpHandlers.addAll(handlers)
    }

    override fun removeUDPMessageHandler(handler: HumlaUDPMessageListener) {
        udpHandlers.remove(handler)
    }

    /** Proxy all connections over a local Orbot instance; forces TCP tunneling for voice. */
    fun setUseTor(useTor: Boolean) {
        this.useTor = useTor
    }

    /** Tunnel all voice packets over TCP, disabling the UDP thread. */
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

    @Throws(NotSynchronizedException::class)
    fun getServerVersion(): Int {
        if (!isSynchronized) throw NotSynchronizedException()
        return remoteVersion
    }

    @Throws(NotSynchronizedException::class)
    fun getServerRelease(): String? {
        if (!isSynchronized) throw NotSynchronizedException()
        return remoteRelease
    }

    @Throws(NotSynchronizedException::class)
    fun getServerOSName(): String? {
        if (!isSynchronized) throw NotSynchronizedException()
        return remoteOsName
    }

    @Throws(NotSynchronizedException::class)
    fun getServerOSVersion(): String? {
        if (!isSynchronized) throw NotSynchronizedException()
        return remoteOsVersion
    }

    @Throws(NotConnectedException::class)
    fun getTCPLatency(): Long {
        if (!isConnected) throw NotConnectedException()
        return tcpLatency
    }

    @Throws(NotConnectedException::class)
    fun getUDPLatency(): Long {
        if (!isConnected) throw NotConnectedException()
        return udpLatency
    }

    @Throws(NotSynchronizedException::class)
    fun getSession(): Int {
        if (!isSynchronized) throw NotSynchronizedException("Session is set during synchronization")
        return sessionId
    }

    /** Server-reported maximum input bandwidth in bps, or -1 if not set. */
    @Throws(NotSynchronizedException::class)
    fun getMaxBandwidth(): Int {
        if (!isSynchronized) throw NotSynchronizedException()
        return serverMaxBandwidth
    }

    @Throws(NotSynchronizedException::class)
    fun getCodec(): HumlaUDPMessageType? {
        if (!isSynchronized) throw NotSynchronizedException()
        return serverCodec
    }

    /** True if TCP is manually forced or Tor is enabled. */
    fun shouldForceTCP(): Boolean = forceTcp || useTor

    /**
     * Shuts down networking. Safe from any thread, idempotent, non-blocking. onConnectionDisconnected
     * is delivered exactly once per started connection, and last.
     */
    fun disconnect() {
        // Written before connectCalled is read; see connect().
        disconnectRequested = true
        if (protocolThread.isAlive) {
            protocolHandler.post {
                tcp?.disconnect()
                tcp = null
                udp?.disconnect()
                udp = null
                quitProtocolThread()
            }
        }
        deliverDisconnected()
    }

    /**
     * Quits the protocol looper at the end of the teardown; earlier would refuse the transports'
     * terminal callbacks, and an unreported disconnect never reconnects.
     */
    private fun quitProtocolThread() {
        protocolThread.quitSafely()
    }

    /** Reports the end of the connection to the listener, at most once. */
    private fun deliverDisconnected() {
        if (!connectCalled) return // nothing was ever started, so there is nothing to report
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

    /** Sends over UDP, or tunnels through TCP unless [force]; the only gate on the voice path. */
    fun sendUDPMessage(data: ByteArray, length: Int, force: Boolean) {
        if (!isConnected) return
        require(length <= data.size) { "Requested length $length is longer than available data length ${data.size}!" }
        if (remoteVersion == 0x10202) applyLegacyCodecWorkaround(data)
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
        val transport = transports.createUdp(cryptState, this, protocolHandler)
        udp = transport
        transport.connect(host, port)
    }

    // ---- TCPConnectionListener (protocol thread) ----

    override fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray) {
        // Drop frames that arrive during teardown; the consumer's audio path is already gone.
        if (disconnectRequested) return
        if (!UNLOGGED_MESSAGES.contains(type)) Log.v(TAG, "IN: $type")

        if (type == HumlaTCPMessageType.UDPTunnel) {
            onUDPDataReceived(data)
            return
        }
        try {
            val message = getProtobufMessage(data, type)
            for (handler in tcpHandlers) broadcastTCPMessage(handler, message, type)
        } catch (e: InvalidProtocolBufferException) {
            Log.w(TAG, "Could not parse $type", e)
        } catch (e: RuntimeException) {
            // A single bad message must not kill the protocol thread, and with it the session.
            Log.e(TAG, "Handler failed for $type", e)
        }
    }

    override fun onTCPConnectionEstablished() {
        if (disconnectRequested) return
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
        if (disconnectRequested) return
        handleFatalException(e)
    }

    override fun onTCPConnectionDisconnect() {
        // No guard needed: disconnect() is idempotent.
        disconnect()
    }

    // ---- UDPConnectionListener (protocol thread) ----

    override fun onUDPDataReceived(data: ByteArray) {
        if (disconnectRequested) return
        if (remoteVersion == 0x10202) applyLegacyCodecWorkaround(data)
        val dataType = (data[0].toInt() shr 5) and 0x7
        val types = HumlaUDPMessageType.values()
        if (dataType < 0 || dataType >= types.size) return // Discard invalid data types
        val udpDataType = types[dataType]
        try {
            for (handler in udpHandlers) broadcastUDPMessage(handler, data, udpDataType)
        } catch (e: RuntimeException) {
            Log.e(TAG, "UDP handler failed for $udpDataType", e)
        }
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

    /** Workaround for 1.2.2 servers that report the old types for CELT alpha and beta. */
    private fun applyLegacyCodecWorkaround(data: ByteArray) {
        var dataType = HumlaUDPMessageType.values()[(data[0].toInt() shr 5) and 0x7]
        if (dataType == HumlaUDPMessageType.UDPVoiceCELTBeta) {
            dataType = HumlaUDPMessageType.UDPVoiceCELTAlpha
        } else if (dataType == HumlaUDPMessageType.UDPVoiceCELTAlpha) {
            dataType = HumlaUDPMessageType.UDPVoiceCELTBeta
        }
        data[0] = ((dataType.ordinal shl 5) and 0xFF).toByte()
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

        /** Parses the passed TCP payload once so every handler receives the same message object. */
        @JvmStatic
        @Throws(InvalidProtocolBufferException::class)
        fun getProtobufMessage(data: ByteArray, messageType: HumlaTCPMessageType): MessageLite = when (messageType) {
            HumlaTCPMessageType.Authenticate -> Mumble.Authenticate.parseFrom(data)
            HumlaTCPMessageType.BanList -> Mumble.BanList.parseFrom(data)
            HumlaTCPMessageType.Reject -> Mumble.Reject.parseFrom(data)
            HumlaTCPMessageType.ServerSync -> Mumble.ServerSync.parseFrom(data)
            HumlaTCPMessageType.ServerConfig -> Mumble.ServerConfig.parseFrom(data)
            HumlaTCPMessageType.PermissionDenied -> Mumble.PermissionDenied.parseFrom(data)
            HumlaTCPMessageType.UDPTunnel -> Mumble.UDPTunnel.parseFrom(data)
            HumlaTCPMessageType.UserState -> Mumble.UserState.parseFrom(data)
            HumlaTCPMessageType.UserRemove -> Mumble.UserRemove.parseFrom(data)
            HumlaTCPMessageType.ChannelState -> Mumble.ChannelState.parseFrom(data)
            HumlaTCPMessageType.ChannelRemove -> Mumble.ChannelRemove.parseFrom(data)
            HumlaTCPMessageType.TextMessage -> Mumble.TextMessage.parseFrom(data)
            HumlaTCPMessageType.ACL -> Mumble.ACL.parseFrom(data)
            HumlaTCPMessageType.QueryUsers -> Mumble.QueryUsers.parseFrom(data)
            HumlaTCPMessageType.Ping -> Mumble.Ping.parseFrom(data)
            HumlaTCPMessageType.CryptSetup -> Mumble.CryptSetup.parseFrom(data)
            HumlaTCPMessageType.ContextAction -> Mumble.ContextAction.parseFrom(data)
            HumlaTCPMessageType.ContextActionModify -> Mumble.ContextActionModify.parseFrom(data)
            HumlaTCPMessageType.Version -> Mumble.Version.parseFrom(data)
            HumlaTCPMessageType.UserList -> Mumble.UserList.parseFrom(data)
            HumlaTCPMessageType.PermissionQuery -> Mumble.PermissionQuery.parseFrom(data)
            HumlaTCPMessageType.CodecVersion -> Mumble.CodecVersion.parseFrom(data)
            HumlaTCPMessageType.UserStats -> Mumble.UserStats.parseFrom(data)
            HumlaTCPMessageType.RequestBlob -> Mumble.RequestBlob.parseFrom(data)
            HumlaTCPMessageType.SuggestConfig -> Mumble.SuggestConfig.parseFrom(data)
            HumlaTCPMessageType.VoiceTarget -> throw InvalidProtocolBufferException("Unknown TCP data passed.")
        }

        /** Routes a parsed TCP message into the matching responder method of the handler. */
        private fun broadcastTCPMessage(handler: HumlaTCPMessageListener, msg: MessageLite, messageType: HumlaTCPMessageType) {
            when (messageType) {
                HumlaTCPMessageType.Authenticate -> handler.messageAuthenticate(msg as Mumble.Authenticate)
                HumlaTCPMessageType.BanList -> handler.messageBanList(msg as Mumble.BanList)
                HumlaTCPMessageType.Reject -> handler.messageReject(msg as Mumble.Reject)
                HumlaTCPMessageType.ServerSync -> handler.messageServerSync(msg as Mumble.ServerSync)
                HumlaTCPMessageType.ServerConfig -> handler.messageServerConfig(msg as Mumble.ServerConfig)
                HumlaTCPMessageType.PermissionDenied -> handler.messagePermissionDenied(msg as Mumble.PermissionDenied)
                HumlaTCPMessageType.UDPTunnel -> handler.messageUDPTunnel(msg as Mumble.UDPTunnel)
                HumlaTCPMessageType.UserState -> handler.messageUserState(msg as Mumble.UserState)
                HumlaTCPMessageType.UserRemove -> handler.messageUserRemove(msg as Mumble.UserRemove)
                HumlaTCPMessageType.ChannelState -> handler.messageChannelState(msg as Mumble.ChannelState)
                HumlaTCPMessageType.ChannelRemove -> handler.messageChannelRemove(msg as Mumble.ChannelRemove)
                HumlaTCPMessageType.TextMessage -> handler.messageTextMessage(msg as Mumble.TextMessage)
                HumlaTCPMessageType.ACL -> handler.messageACL(msg as Mumble.ACL)
                HumlaTCPMessageType.QueryUsers -> handler.messageQueryUsers(msg as Mumble.QueryUsers)
                HumlaTCPMessageType.Ping -> handler.messagePing(msg as Mumble.Ping)
                HumlaTCPMessageType.CryptSetup -> handler.messageCryptSetup(msg as Mumble.CryptSetup)
                HumlaTCPMessageType.ContextAction -> handler.messageContextAction(msg as Mumble.ContextAction)
                HumlaTCPMessageType.ContextActionModify -> {
                    val actionModify = msg as Mumble.ContextActionModify
                    when (actionModify.operation) {
                        Mumble.ContextActionModify.Operation.Add -> handler.messageContextActionModify(actionModify)
                        Mumble.ContextActionModify.Operation.Remove -> handler.messageRemoveContextAction(actionModify)
                        else -> Unit
                    }
                }
                HumlaTCPMessageType.Version -> handler.messageVersion(msg as Mumble.Version)
                HumlaTCPMessageType.UserList -> handler.messageUserList(msg as Mumble.UserList)
                HumlaTCPMessageType.PermissionQuery -> handler.messagePermissionQuery(msg as Mumble.PermissionQuery)
                HumlaTCPMessageType.CodecVersion -> handler.messageCodecVersion(msg as Mumble.CodecVersion)
                HumlaTCPMessageType.UserStats -> handler.messageUserStats(msg as Mumble.UserStats)
                HumlaTCPMessageType.RequestBlob -> handler.messageRequestBlob(msg as Mumble.RequestBlob)
                HumlaTCPMessageType.SuggestConfig -> handler.messageSuggestConfig(msg as Mumble.SuggestConfig)
                HumlaTCPMessageType.VoiceTarget -> Unit // client-to-server only; never parsed
            }
        }

        /** Routes a UDP datagram into the matching responder method of the handler. */
        private fun broadcastUDPMessage(handler: HumlaUDPMessageListener, data: ByteArray, messageType: HumlaUDPMessageType) {
            when (messageType) {
                HumlaUDPMessageType.UDPPing -> handler.messageUDPPing(data)
                HumlaUDPMessageType.UDPVoiceCELTAlpha,
                HumlaUDPMessageType.UDPVoiceSpeex,
                HumlaUDPMessageType.UDPVoiceCELTBeta,
                HumlaUDPMessageType.UDPVoiceOpus -> handler.messageVoiceData(data, messageType)
            }
        }
    }
}
