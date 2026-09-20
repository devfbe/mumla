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
import android.os.Looper
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One connection to a Mumble server. */
class HumlaConnection(private val listener: HumlaConnectionListener) :
    HumlaTCP.TCPConnectionListener, HumlaUDP.UDPConnectionListener {

    // Authentication
    private var certificate: ByteArray? = null
    private var certificatePassword: String? = null
    private var trustStorePath: String? = null
    private var trustStorePassword: String? = null
    private var trustStoreFormat: String? = null

    // Threading
    private var pingExecutorService: ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Networking and protocols
    private var tcp: HumlaTCP? = null
    private var udp: HumlaUDP? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var usingUdp = true
    private var forceTcp = false
    private var useTor = false
    private var connected = false
    private var synchronizedWithServer = false
    private var lastError: HumlaException? = null
    private var exceptionHandled = false
    private var startTimestamp = 0L // Time that the connection was initiated in nanoseconds
    private val cryptState = CryptState()

    // Latency
    private var udpLatency = 0L
    private var tcpLatency = 0L

    // Server
    private var host: String? = null
    private var port = 0
    private var remoteVersion = 0
    private var remoteRelease: String? = null
    private var remoteOsName: String? = null
    private var remoteOsVersion: String? = null
    private var serverMaxBandwidth = 0
    private var serverCodec: HumlaUDPMessageType? = null

    // Session
    private var sessionId = 0

    // Message handlers
    private val tcpHandlers = ConcurrentLinkedQueue<HumlaTCPMessageListener>()
    private val udpHandlers = ConcurrentLinkedQueue<HumlaUDPMessageListener>()

    /** Handles packets received that are critical to the connection state. */
    private val connectionMessageHandler = object : HumlaTCPMessageListener.Stub() {
        override fun messageServerSync(msg: Mumble.ServerSync) {
            // Protocol says we're supposed to send a dummy UDPTunnel packet here to let the server know we don't like UDP.
            if (shouldForceTCP()) enableForceTCP()

            // Start TCP/UDP ping thread. FIXME is this the right place?
            try {
                pingTask = pingExecutorService?.scheduleAtFixedRate(pingRunnable, 0, 5, TimeUnit.SECONDS)
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "failed to start ping thread, in \"shutdown\"? ", e)
            }

            sessionId = msg.session
            serverMaxBandwidth = if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1
            synchronizedWithServer = true

            mainHandler.post { listener.onConnectionSynchronized() }
        }

        override fun messageCodecVersion(msg: Mumble.CodecVersion) {
            serverCodec = when {
                msg.hasOpus() && msg.opus -> HumlaUDPMessageType.UDPVoiceOpus
                msg.hasBeta() && !msg.preferAlpha -> HumlaUDPMessageType.UDPVoiceCELTBeta
                else -> HumlaUDPMessageType.UDPVoiceCELTAlpha
            }
        }

        override fun messageReject(msg: Mumble.Reject) {
            connected = false
            handleFatalException(HumlaException(msg))
        }

        override fun messageUserRemove(msg: Mumble.UserRemove) {
            if (msg.session == sessionId) {
                connected = false
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
                    if (cryptState.mUiRemoteGood == 0 && cryptState.mUiGood == 0) {
                        listener.onConnectionWarning("UDP packets cannot be sent to or received from the server. Switching to TCP mode.")
                    } else if (cryptState.mUiRemoteGood == 0) {
                        listener.onConnectionWarning("UDP packets cannot be sent to the server. Switching to TCP mode.")
                    } else {
                        listener.onConnectionWarning("UDP packets cannot be received from the server. Switching to TCP mode.")
                    }
                }
            } else if (!usingUdp && cryptState.mUiRemoteGood > 3 && cryptState.mUiGood > 3) {
                usingUdp = true
                if (!shouldForceTCP()) {
                    listener.onConnectionWarning("UDP packets can be sent to and received from the server. Switching back to UDP mode.")
                }
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

    private val pingRunnable = Runnable {
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

    @Throws(HumlaException::class)
    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port
        connected = false
        synchronizedWithServer = false
        lastError = null
        exceptionHandled = false
        usingUdp = !shouldForceTCP()
        startTimestamp = System.nanoTime()

        pingExecutorService = Executors.newSingleThreadScheduledExecutor()

        val socketFactory = createSocketFactory()
        try {
            val transport = HumlaTCP(socketFactory)
            transport.setTCPConnectionListener(this)
            tcp = transport
            transport.connect(host, port, useTor)
            // UDP thread is formally started after TCP connection.
        } catch (e: ConnectException) {
            throw HumlaException(e, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        }
    }

    val isConnected: Boolean get() = connected

    /**
     * Returns whether or not the service is fully synchronized with the remote server - this
     * happens when we get the ServerSync message. You shouldn't log any user actions until the
     * connection is synchronized.
     */
    val isSynchronized: Boolean get() = synchronizedWithServer

    /** Microseconds since connect(). */
    val elapsed: Long get() = (System.nanoTime() - startTimestamp) / 1000

    fun addTCPMessageHandlers(vararg handlers: HumlaTCPMessageListener) {
        tcpHandlers.addAll(handlers)
    }

    fun removeTCPMessageHandler(handler: HumlaTCPMessageListener) {
        tcpHandlers.remove(handler)
    }

    fun addUDPMessageHandlers(vararg handlers: HumlaUDPMessageListener) {
        udpHandlers.addAll(handlers)
    }

    fun removeUDPMessageHandler(handler: HumlaUDPMessageListener) {
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
        if (!synchronizedWithServer) throw NotSynchronizedException()
        return remoteVersion
    }

    @Throws(NotSynchronizedException::class)
    fun getServerRelease(): String? {
        if (!synchronizedWithServer) throw NotSynchronizedException()
        return remoteRelease
    }

    @Throws(NotSynchronizedException::class)
    fun getServerOSName(): String? {
        if (!synchronizedWithServer) throw NotSynchronizedException()
        return remoteOsName
    }

    @Throws(NotSynchronizedException::class)
    fun getServerOSVersion(): String? {
        if (!synchronizedWithServer) throw NotSynchronizedException()
        return remoteOsVersion
    }

    @Throws(NotConnectedException::class)
    fun getTCPLatency(): Long {
        if (!connected) throw NotConnectedException()
        return tcpLatency
    }

    @Throws(NotConnectedException::class)
    fun getUDPLatency(): Long {
        if (!connected) throw NotConnectedException()
        return udpLatency
    }

    @Throws(NotSynchronizedException::class)
    fun getSession(): Int {
        if (!synchronizedWithServer) throw NotSynchronizedException("Session is set during synchronization")
        return sessionId
    }

    /** Server-reported maximum input bandwidth in bps, or -1 if not set. */
    @Throws(NotSynchronizedException::class)
    fun getMaxBandwidth(): Int {
        if (!synchronizedWithServer) throw NotSynchronizedException()
        return serverMaxBandwidth
    }

    @Throws(NotSynchronizedException::class)
    fun getCodec(): HumlaUDPMessageType? {
        if (!synchronizedWithServer) throw NotSynchronizedException()
        return serverCodec
    }

    /** True if TCP is manually forced or Tor is enabled. */
    fun shouldForceTCP(): Boolean = forceTcp || useTor

    /** Gracefully shuts down all networking. */
    fun disconnect() {
        connected = false
        synchronizedWithServer = false
        host = null
        port = 0

        // Stop running network resources
        pingTask?.cancel(true)
        tcp?.disconnect()
        udp?.disconnect()
        // Null-safe where the Java was not: disconnect() before connect() threw a
        // NullPointerException there, because the executor is only created in connect().
        pingExecutorService?.shutdown()

        tcp = null
        udp = null
        pingTask = null
    }

    /** Handles an exception that would cause termination of the connection. */
    private fun handleFatalException(e: HumlaException) {
        if (exceptionHandled) return
        exceptionHandled = true
        lastError = e
        Log.e(TAG, "Fatal connection error: ${e.message}", e)
        listener.onConnectionDisconnected(e)
        disconnect()
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
        if (!connected) return
        tcp?.sendMessage(message, messageType)
    }

    /** Sends a datagram over UDP, or tunnels it through TCP unless [force]. */
    fun sendUDPMessage(data: ByteArray, length: Int, force: Boolean) {
        if (!connected) return
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
        if (!connected) return
        val utb = Mumble.UDPTunnel.newBuilder()
        utb.packet = ByteString.copyFrom(ByteArray(3))
        sendTCPMessage(utb.build(), HumlaTCPMessageType.UDPTunnel)
    }

    fun sendAccessTokens(tokens: Collection<String>) {
        if (!connected) return
        val ab = Mumble.Authenticate.newBuilder()
        ab.addAllTokens(tokens)
        sendTCPMessage(ab.build(), HumlaTCPMessageType.Authenticate)
    }

    override fun onTCPMessageReceived(type: HumlaTCPMessageType, length: Int, data: ByteArray) {
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
        }
    }

    override fun onTCPConnectionEstablished() {
        connected = true
        // Attempt to start UDP thread once connected.
        if (!shouldForceTCP()) {
            // The Java passed mHost straight through; a disconnect() racing the handshake nulls it,
            // and InetAddress.getByName(null) resolves to loopback rather than failing.
            val h = host
            if (h != null) {
                val transport = HumlaUDP(cryptState, this, mainHandler)
                udp = transport
                transport.connect(h, port)
            }
        }
        listener.onConnectionEstablished()
    }

    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>) {
        disconnect()
        listener.onConnectionHandshakeFailed(chain)
        listener.onConnectionDisconnected(null)
    }

    override fun onTCPConnectionFailed(e: HumlaException) {
        handleFatalException(e)
    }

    override fun onTCPConnectionDisconnect() {
        if (!exceptionHandled) listener.onConnectionDisconnected(lastError)
        disconnect()
    }

    override fun onUDPDataReceived(data: ByteArray) {
        if (remoteVersion == 0x10202) applyLegacyCodecWorkaround(data)
        val dataType = (data[0].toInt() shr 5) and 0x7
        val types = HumlaUDPMessageType.values()
        if (dataType < 0 || dataType >= types.size) return // Discard invalid data types
        val udpDataType = types[dataType]
        for (handler in udpHandlers) broadcastUDPMessage(handler, data, udpDataType)
    }

    override fun onUDPConnectionError(e: Exception) {
        Log.w(TAG, "UDP connection thread failed", e)
        listener.onConnectionWarning("UDP connection thread failed. Falling back to TCP.")
        enableForceTCP()
        // TODO recover UDP thread automagically
    }

    override fun resyncCryptState() {
        // Send an empty cryptstate message to resync.
        tcp?.sendMessage(Mumble.CryptSetup.newBuilder().build(), HumlaTCPMessageType.CryptSetup)
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
         * the disconnect was clean.
         */
        fun onConnectionDisconnected(e: HumlaException?)

        /** Called if the user should be notified of a connection-related warning. */
        fun onConnectionWarning(warning: String)
    }

    companion object {
        private val TAG: String = HumlaConnection::class.java.name

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
