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
import com.google.protobuf.Message
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.util.HumlaException
import java.io.IOException
import java.net.ConnectException
import java.nio.ByteBuffer
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
 * One connection to a Mumble server.
 *
 * Threading (spec A1): host resolution, socket setup, frame parsing, handler dispatch
 * (ModelHandler, AudioHandler), voice routing, the ping timer and every transport callback run on
 * the "humla-protocol" [HandlerThread] this object owns. [HumlaConnectionListener] callbacks are
 * posted to [mainHandler]. [sendTCPMessage] and [sendUDPMessage] may be called from any thread.
 *
 * Single-use, and that is what keeps its state flags honest. HumlaTCP's class comment asks of every
 * flag: which thread closes its window, and does anything fence that thread in? Here nothing closes
 * one. [connectCalled], [disconnectRequested], [disconnectDelivered], [exceptionHandled] and
 * [disconnectReported] are set once and never cleared, so there is no window for a late writer to
 * reopen, whichever thread it runs on. A second connection is a second object.
 *
 * [connected] and [synchronizedWithServer] used to be the exception, and they were HumlaTCP's
 * warning read from the other end: they were *opened* on the protocol thread and closed by whichever
 * thread called [disconnect]. An opener and a closer on different threads is the same hazard
 * mirrored - a disconnect landing between the established callback's guard check and its write one
 * instruction later would have left [connected] set for the life of the object. They are set once
 * too now: [disconnect] raises [disconnectRequested] and writes neither flag, and [isConnected] and
 * [isSynchronized] compose the pair with it. The closing writes were deleted rather than explained,
 * because with the composition in place no reader can tell them from their absence.
 *
 * What keeps a *late opener* out is not looper order, and this comment used to claim it was. A
 * [disconnect] on the main thread and an onTCPConnectionEstablished the read thread posts after it
 * reach the protocol looper in the order they were queued, which is [teardown, opener] exactly as
 * often as the other way round. The entry guard in [onTCPConnectionEstablished] is what closes that
 * window, and [onTCPMessageReceived]'s guard is what closes it for the ServerSync that would
 * otherwise set the other flag.
 *
 * What the caller's edge does not do is stop a send already past its check. [sendTCPMessage] reads
 * [isConnected], a [disconnect] runs, and the write still goes out: the teardown that drops the
 * transport is only queued at that point, [tcp] is still set and its send executor is still
 * running. The bytes really are written. That is harmless - a ping or a crypt resync on a socket
 * about to close - but it is not the same statement as "the send is dropped".
 */
class HumlaConnection @JvmOverloads constructor(
    private val listener: HumlaConnectionListener,
    private val transports: TransportFactory = DefaultTransportFactory(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val nanoClock: () -> Long = System::nanoTime,
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

    /**
     * The thread this connection runs on. Not private so a test can assert on the thread itself
     * rather than on a filter over [Thread.getAllStackTraces] by name: a library that renames
     * threads turns a name filter into a leak test that passes because it found nothing to look at.
     * Reading this does not start the thread; [protocolHandler] does.
     */
    internal val protocolThread = HandlerThread(PROTOCOL_THREAD_NAME)

    /**
     * Runs parsing, model updates and voice routing. Started on first access - i.e. by [connect] -
     * so a connection object that is built and then thrown away leaks no thread. Quit by
     * [disconnect], from inside the queued teardown rather than before it.
     *
     * What this leaves on the caller's thread is a thread start, not I/O: the initialiser calls
     * HandlerThread.getLooper(), which waits until the new thread has published its looper, and
     * `by lazy` holds a SYNCHRONIZED monitor while it does - so a [disconnect] from another thread
     * can block on that monitor for as long as [connect] holds it. Bounded by a thread start, and
     * it is the reason [connect] is the only caller that touches this before the first post.
     */
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
    /**
     * Whether the socket came up, and whether the handshake completed. Written only on the protocol
     * thread and only ever to true, by the established callback and by ServerSync, each behind an
     * entry guard of its own. Nothing closes them. [isConnected] and [isSynchronized] are their only
     * readers and compose them with [disconnectRequested], so a [disconnect] from any thread closes
     * both at once without becoming a second writer that the opener could race - and a write of
     * false anywhere would be a store no reader can tell from its absence.
     */
    @Volatile private var connected = false
    @Volatile private var synchronizedWithServer = false
    @Volatile private var lastError: HumlaException? = null
    private val exceptionHandled = AtomicBoolean(false)
    private val disconnectDelivered = AtomicBoolean(false)
    @Volatile private var connectCalled = false
    @Volatile private var disconnectRequested = false

    /**
     * Whether the listener has been told the connection ended. Not volatile, and that is the point:
     * it is written by the disconnect report itself and read by [notifyListener], both inside
     * runnables on [mainHandler]'s thread, so that one looper's FIFO order is the whole mechanism.
     * See [notifyListener] for why the decision has to be made there and not at the call site.
     */
    private var disconnectReported = false
    @Volatile private var startTimestamp = 0L // Time that the connection was initiated in nanoseconds
    private val cryptState = CryptState()

    // Latency
    @Volatile private var udpLatency = 0L
    @Volatile private var tcpLatency = 0L

    // Server. Written in the posted connect block and read by [startUdp], both on the protocol
    // thread, so neither needs to be volatile.
    //
    // What keeps [startUdp] from ever seeing the initial "" is creation order, not the value: it is
    // reachable only from [onTCPConnectionEstablished], only a TCP transport can raise that, and
    // [connect] assigns both fields before it creates the transport. Move the assignment below
    // transports.createTcp and the invariant is gone - no test holds it, so this comment is the
    // only thing that does.
    //
    // Deliberately not cleared by the teardown either: clearing them was what created the loopback
    // hazard, because InetAddress.getByName resolves null - and the empty string - to 127.0.0.1
    // rather than failing, so a UDP start racing a disconnect opened a socket to the local machine.
    // Nothing reads them after the teardown and the object is single-use, so there is nothing to
    // clear them for.
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

    /**
     * Sends the pings and reschedules itself. It replaces the ScheduledExecutorService the Java
     * kept for this one task: the protocol thread is already there and already owns the send path,
     * so a second thread bought nothing but a shutdown to get wrong.
     *
     * No entry guard of its own. It had one, and three other things already did its job: the
     * teardown removes this callback before it quits the looper, quitSafely refuses the reschedule
     * afterwards, and every byte [sendPings] produces leaves through [sendTCPMessage] or
     * [sendUDPMessage], which make the [isConnected] decision at the one place a send is
     * observable. A fourth copy of it was a branch no test could fail on.
     */
    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPings()
            protocolHandler.postDelayed(this, PING_INTERVAL_MILLIS)
        }
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
                        cryptState.mDecryptIV = serverNonce.toByteArray()
                    }
                } else {
                    val csb = Mumble.CryptSetup.newBuilder()
                    csb.clientNonce = ByteString.copyFrom(cryptState.mEncryptIV)
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

            if ((cryptState.mUiRemoteGood == 0 || cryptState.mUiGood == 0) && usingUdp && now > 20000000) {
                usingUdp = false
                if (!shouldForceTCP()) {
                    warn(
                        when {
                            cryptState.mUiRemoteGood == 0 && cryptState.mUiGood == 0 -> ConnectionWarning.UDP_UNAVAILABLE
                            cryptState.mUiRemoteGood == 0 -> ConnectionWarning.UDP_SEND_FAILED
                            else -> ConnectionWarning.UDP_RECEIVE_FAILED
                        }
                    )
                }
            } else if (!usingUdp && cryptState.mUiRemoteGood > 3 && cryptState.mUiGood > 3) {
                usingUdp = true
                if (!shouldForceTCP()) warn(ConnectionWarning.UDP_RESTORED)
            }
        }
    }

    private val udpPingListener = object : HumlaUDPMessageListener.Stub() {
        override fun messageUDPPing(data: ByteArray) {
            val timestamp = ByteBuffer.wrap(data, 1, 8).long
            udpLatency = elapsed - timestamp
            // TODO refresh UDP?
        }
    }

    private fun sendPings() {
        // In microseconds
        val t = elapsed
        if (!shouldForceTCP()) {
            val buffer = ByteBuffer.allocate(16)
            buffer.put(((HumlaUDPMessageType.UDPPing.ordinal shl 5) and 0xFF).toByte())
            buffer.putLong(t)
            sendUDPMessage(buffer.array(), 16, true)
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
     * Starts connecting. Host resolution - including the blocking SRV lookup in
     * [Server.getSrvHost] - key store loading and socket creation all happen on the protocol
     * thread; every outcome, certificate errors included, is reported through the listener rather
     * than thrown at the caller.
     */
    fun connect(server: Server) {
        // Single-use, and the two checks are ordered so that a disconnect() racing this call from
        // another thread cannot be lost: this writes connectCalled before reading
        // disconnectRequested, disconnect() writes disconnectRequested before reading
        // connectCalled, and both fields are volatile - so at most one of the two reads can see
        // the stale value. Either disconnect() sees a connection to report, or this throws.
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
                createSocketFactory()
            } catch (e: HumlaException) {
                handleFatalException(e)
                return@post
            }
            val resolvedHost = server.srvHost
            val resolvedPort = server.srvPort
            // Before the transport exists, and that ordering is load-bearing: the transport is
            // what raises onTCPConnectionEstablished, which is the only route into startUdp, which
            // is the only reader of these two. See their declaration.
            host = resolvedHost
            port = resolvedPort
            val transport = transports.createTcp(socketFactory, protocolHandler)
            transport.setTCPConnectionListener(this)
            tcp = transport
            try {
                transport.connect(resolvedHost, resolvedPort, useTor)
                // The UDP transport is formally started after the TCP connection is up.
            } catch (e: ConnectException) {
                handleFatalException(HumlaException(e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR))
            }
        }
    }

    val isConnected: Boolean get() = connected && !disconnectRequested

    /**
     * Returns whether or not the service is fully synchronized with the remote server - this
     * happens when we get the ServerSync message. You shouldn't log any user actions until the
     * connection is synchronized.
     */
    val isSynchronized: Boolean get() = synchronizedWithServer && !disconnectRequested

    /** False while voice is tunneled over TCP, because it is forced or UDP was judged unusable. */
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
     * Shuts down networking. Safe from any thread, idempotent, and never blocks on a network
     * thread. The listener's onConnectionDisconnected is delivered exactly once per *started*
     * connection and last, carrying [error] if one was recorded - a disconnect before [connect]
     * reports nothing at all, because there is nothing to report. This object is not reusable
     * afterwards.
     */
    fun disconnect() {
        // Written before connectCalled is read; see the ordering note in connect().
        disconnectRequested = true
        if (protocolThread.isAlive) {
            protocolHandler.post {
                protocolHandler.removeCallbacks(pingRunnable)
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
     * Quits the protocol looper, and only ever from the protocol thread itself, at the end of the
     * teardown. Quitting it from [disconnect] directly would refuse every post made during the
     * teardown - and the transports report their terminal callback from inside their own
     * disconnect(), by posting it here. HumlaTCP hands its token back when that post is refused and
     * leaves the report to its read thread, which has no route at all while it is stuck in a
     * connect without a timeout; a disconnect nobody reports is a session that never reconnects.
     * quitSafely still delivers everything already queued, so nothing in flight is lost either.
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
            // Set before the callback rather than after, and nothing holds that: [notifyListener]
            // always posts and never delivers inline, so no callback can slip between these two
            // lines and swapping them leaves the suite green. Written this way, not promised this
            // way.
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

    private fun warn(warning: ConnectionWarning) {
        notifyListener { onConnectionWarning(warning) }
    }

    /**
     * Queues a listener callback on [mainHandler], and drops it if the disconnect report has
     * already been delivered. This is what makes [HumlaConnectionListener.onConnectionDisconnected]
     * terminal and not merely exactly-once.
     *
     * The decision is made at delivery and only there. Checking [disconnectRequested] at the call
     * site cannot decide it: a ServerSync already past every entry guard and inside its handler
     * posts onConnectionSynchronized after a disconnect() that ran meanwhile has posted the report,
     * and measured, that is exactly what happened - [established, disconnected, synchronized] on
     * main. At delivery the looper's FIFO order has already settled the question: this callback was
     * queued before the report or it was not.
     *
     * HumlaTCP needs a per-connection Epoch object for the same promise because it is reused. This
     * object is single-use, so one flag that is only ever set - never cleared - says the same thing:
     * a second connection is a second object, with its own flag that starts false.
     *
     * A callback whose only effect is a listener notification therefore needs no entry guard of its
     * own. onTLSHandshakeFailed and onUDPConnectionError are closed here, and a guard on top of
     * this would be code no test could tell apart from its absence.
     */
    private fun notifyListener(callback: HumlaConnectionListener.() -> Unit) {
        mainHandler.post { if (!disconnectReported) listener.callback() }
    }

    /**
     * Creates a socket factory using this connection's certificate and trust store configuration.
     */
    @Throws(HumlaException::class)
    private fun createSocketFactory(): HumlaSSLSocketFactory {
        try {
            var keyStore: KeyStore? = null
            val cert = certificate
            if (cert != null) {
                keyStore = Pkcs12Certificates.load(cert, certificatePassword)
            }
            return HumlaSSLSocketFactory(
                keyStore, certificatePassword, trustStorePath, trustStorePassword, trustStoreFormat
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
    fun sendTCPMessage(message: Message, messageType: HumlaTCPMessageType) {
        if (!isConnected) return
        tcp?.sendMessage(message, messageType)
    }

    /**
     * Sends a datagram over UDP, or tunnels it through TCP unless [force].
     *
     * The [isConnected] check here is not the one [sendTCPMessage] makes: both branches below hand
     * the bytes to a transport directly, so this is the only gate on the voice path.
     */
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
        // No isConnected check: the send below is this method's only effect and makes that same
        // decision, so a check here would be a branch nothing could tell from its absence.
        val utb = Mumble.UDPTunnel.newBuilder()
        utb.packet = ByteString.copyFrom(ByteArray(3))
        sendTCPMessage(utb.build(), HumlaTCPMessageType.UDPTunnel)
    }

    fun sendAccessTokens(tokens: Collection<String>) {
        // Same as enableForceTCP: sendTCPMessage is the only effect and the only real guard.
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
        // Frames the read thread completed while this connection was being torn down: by the time
        // the disconnect is delivered the consumer has shut its audio path down, and the handler
        // list is not cleared here, so without this a straggler would be decoded into it.
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
        // Attempt to start the UDP transport once connected.
        if (!shouldForceTCP()) startUdp()
        notifyListener { onConnectionEstablished() }
    }

    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>) {
        notifyListener { onConnectionHandshakeFailed(chain) }
        // Posted first, so the certificate prompt is queued ahead of the disconnect that follows
        // it; disconnect() posts the report to the same looper.
        disconnect()
    }

    override fun onTCPConnectionFailed(e: HumlaException) {
        // Without this the failure would become this connection's error *after* the disconnect had
        // already been reported as clean: the consumer gets a null reason and a non-null error()
        // for the same connection.
        if (disconnectRequested) return
        handleFatalException(e)
    }

    override fun onTCPConnectionDisconnect() {
        // No guard: the operation this asks for is the one already in progress, and disconnect()
        // is idempotent. A guard here would have no effect any test could tell apart from its
        // absence, which is the kind of guard this class has been bitten by.
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
        warn(ConnectionWarning.UDP_THREAD_FAILED)
        enableForceTCP()
        // TODO recover UDP thread automagically
    }

    override fun resyncCryptState() {
        // Through sendTCPMessage, not through the transport: sending directly was the one path that
        // skipped the connected check, so it could still put bytes on a socket the user had already
        // asked to close. No disconnectRequested guard on top of it - measured, the two mask each
        // other: with either one present, removing the other leaves the suite green.
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

        /**
         * Called if the host's certificate failed verification. Typically you would use this
         * callback to prompt the user to authorize the certificate. Note that
         * [onConnectionDisconnected] will still be called.
         */
        fun onConnectionHandshakeFailed(chain: Array<X509Certificate>)

        /**
         * Called when the connection was lost, with the error that caused termination, or null if
         * the disconnect was clean. Exactly once per started connection, and last: no other method
         * of this interface is called afterwards, including one whose event was already in flight
         * when the disconnect happened. A connection disconnected before it was started says
         * nothing here - see [HumlaConnection.disconnect].
         */
        fun onConnectionDisconnected(e: HumlaException?)

        /** Called if the user should be notified of a connection-related warning. */
        fun onConnectionWarning(warning: ConnectionWarning)
    }

    companion object {
        private val TAG: String = HumlaConnection::class.java.name
        private const val PROTOCOL_THREAD_NAME = "humla-protocol"
        private const val PING_INTERVAL_MILLIS = 5_000L

        /** Message types that aren't shown in logcat, for annoying types like UDPTunnel. */
        @JvmField
        val UNLOGGED_MESSAGES: Set<HumlaTCPMessageType> =
            setOf(HumlaTCPMessageType.UDPTunnel, HumlaTCPMessageType.Ping)

        // Tor connection details
        const val TOR_HOST = "localhost"
        const val TOR_PORT = 9050

        /**
         * Calculates the bandwidth in bps required to send audio with the given parameters,
         * including packet overhead.
         */
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
        fun getProtobufMessage(data: ByteArray, messageType: HumlaTCPMessageType): Message = when (messageType) {
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
        private fun broadcastTCPMessage(handler: HumlaTCPMessageListener, msg: Message, messageType: HumlaTCPMessageType) {
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
                HumlaTCPMessageType.VoiceTarget -> handler.messageVoiceTarget(msg as Mumble.VoiceTarget)
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
