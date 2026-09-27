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

package se.lublin.humla

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import se.lublin.humla.audio.AudioHandler
import se.lublin.humla.audio.AudioHandlerFactory
import se.lublin.humla.audio.AudioHost
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.AudioSessionParams
import se.lublin.humla.audio.DefaultAudioHandlerFactory
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.routing.AndroidCommunicationDevices
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.CommunicationDevices
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.LocalVolumes
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.net.ConnectionWarning
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.ReconnectPolicy
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.protocol.ServerCommands
import se.lublin.humla.session.AndroidNetworkMonitor
import se.lublin.humla.session.AndroidSessionWakeLock
import se.lublin.humla.session.AudioSession
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.NetworkMonitor
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionLifecycle
import se.lublin.humla.session.SessionState
import se.lublin.humla.session.SessionWakeLock
import se.lublin.humla.session.disconnectReasonOf
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One session with a server, as a plain object: the connection with its automatic reconnects
 * ([SessionLifecycle]), the server's model ([ModelHandler]), the requests to it ([ServerCommands])
 * and the audio pipeline and routing ([AudioSession]).
 *
 * Confined to [mainHandler]'s thread: every call, connection callback, reconnect timer and network
 * callback runs there. The model is written on the protocol thread and the pipeline runs on its
 * own threads; [state] and [events] may be collected anywhere. The parameters after [config] are
 * the platform, replaceable for tests.
 */
@Suppress("TooManyFunctions", "LongParameterList") // The protocol's whole client API; the platform seams.
class HumlaSession(
    private val context: Context,
    config: SessionConfig,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    network: NetworkMonitor = AndroidNetworkMonitor(
        context.getSystemService(ConnectivityManager::class.java),
        mainHandler,
    ),
    wakeLock: SessionWakeLock = AndroidSessionWakeLock(context.getSystemService(PowerManager::class.java)),
    communicationDevices: CommunicationDevices? = null,
    private val connectionFactory: (HumlaConnection.HumlaConnectionListener) -> HumlaConnection =
        { HumlaConnection(it, mainHandler = mainHandler) },
    audioFactory: AudioHandlerFactory = DefaultAudioHandlerFactory,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) : IHumlaSession {

    override var config: SessionConfig = config
        private set

    /**
     * Emitted from any thread without suspending. Beyond [EVENT_BUFFER] events not yet collected
     * by the slowest collector, the oldest are dropped.
     */
    private val mutableEvents = MutableSharedFlow<HumlaEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<HumlaEvent> = mutableEvents.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + mainHandler.asCoroutineDispatcher())

    internal val lifecycle = SessionLifecycle(reconnectPolicy, network, wakeLock, scope, ::startAttempt)

    /**
     * The connection of the latest attempt, kept after it ends. Written on the main thread, read on
     * the protocol thread ([emit] checks it).
     */
    @Volatile
    internal var connection: HumlaConnection? = null
        private set

    private var commands: ServerCommands? = null

    /** The model of the live connection; dropped when it ends, so its tree can be collected. */
    @Volatile
    internal var modelHandler: ModelHandler? = null
        private set

    private var localVolumes: LocalVolumes? = null

    /** The certificate problem the connection reported ahead of its end, if any. */
    private var tlsFailure: DisconnectReason? = null

    private var currentVoiceTargetId: Byte = 0
    private val whisperTargetList = WhisperTargetList()

    /** Last warning logged, so a refusal repeated per reconnect attempt is logged once. */
    @Volatile
    private var lastWarning: String? = null

    internal val logger = object : HumlaLogger {
        override fun logInfo(message: String) = emit(HumlaEvent.LogMessage(HumlaEvent.Level.INFO, message))

        override fun logWarning(message: String) = warn(message)

        override fun logError(message: String) = emit(HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, message))
    }

    private val audioInputListener = object : AudioHandler.AudioEncodeListener {
        override fun onAudioEncoded(data: ByteArray, length: Int) {
            val connection = connection
            if (connection != null && connection.isSynchronized) connection.sendUDPMessage(data, length, false)
        }

        override fun onTalkingStateChanged(talking: Boolean) {
            mainHandler.post {
                // A leftover from a terminated connection when the session is inactive.
                val connection = connection
                if (connection == null || !connection.isSynchronized) return@post
                val self = modelHandler?.getUser(connection.getSession()) ?: return@post
                self.talkState = if (talking) TalkState.TALKING else TalkState.PASSIVE
                emit(HumlaEvent.UserTalkStateUpdated(self))
            }
        }
    }

    private val audioOutputListener = object : AudioOutput.AudioOutputListener {
        override fun onUserTalkStateUpdated(user: User) = emit(HumlaEvent.UserTalkStateUpdated(user))

        override fun getUser(session: Int): User? = modelHandler?.getUser(session)
    }

    internal val audio = AudioSession(
        AudioHost(context, logger, audioInputListener, audioOutputListener),
        audioFactory,
        // One instance per session, so a platform refusal is reported once rather than on every
        // route decision.
        communicationDevices ?: AndroidCommunicationDevices(
            context.getSystemService(AudioManager::class.java),
            mainHandler,
        ) { warnOnce(context.getString(R.string.bluetooth_sco_denied)) },
        mainHandler,
        config.audio,
        object : AudioSession.Listener {
            override fun onRouteRefused() = warnOnce(context.getString(R.string.audio_route_refused))

            override fun onAudioFailed(message: String) = warn(message)
        },
    )

    override val state: StateFlow<SessionState> get() = lifecycle.state

    override val audioRoute: StateFlow<Int?> get() = audio.route

    override val targetServer: Server? get() = config.connection.server

    init {
        // minidns keeps one process-wide list of lookup mechanisms, which every setup appends to.
        if (dnsLookupInstalled.compareAndSet(false, true)) AndroidUsingLinkProperties.setup(context.applicationContext)
    }

    override fun connect() {
        if (lifecycle.connectRequested()) startAttempt()
    }

    /** Builds and starts one connection attempt; for [connect] and every retry. */
    private fun startAttempt() {
        // A state collector may have ended the session inline, as the state moved on.
        val state = lifecycle.current
        if (state != SessionState.Connecting && state !is SessionState.Reconnecting) return
        // The voice target can be set while disconnected; whisper slots were cleared at the last end.
        currentVoiceTargetId = 0
        tlsFailure = null
        val config = config.connection
        val server = config.server
        if (server == null) {
            Log.e(TAG, "connect() without a target server")
            lost(DisconnectReason.Failed(context.getString(R.string.no_target_server), null))
            return
        }

        val callbacks = ConnectionCallbacks()
        val connection = connectionFactory(callbacks)
        callbacks.connection = connection
        this.connection = connection
        commands = ServerCommands(connection::sendTCPMessage)
        connection.setForceTCP(config.forceTcp)
        connection.setUseTor(config.useTor)
        connection.setKeys(config.certificate?.pkcs12, config.certificate?.password)
        connection.setTrustStore(config.trustStorePath, config.trustStorePassword, config.trustStoreFormat)

        val localVolumes = LocalVolumes(server, this.config.localVolumes)
        this.localVolumes = localVolumes
        val modelHandler = ModelHandler(::emit, config.localMuteHistory, config.localIgnoreHistory, localVolumes)
        this.modelHandler = modelHandler
        connection.addTcpHandler(modelHandler)

        // Resolves the host (SRV lookup included) and opens the socket on the protocol thread;
        // every failure, certificate errors included, arrives at onConnectionDisconnected.
        connection.connect(server)
    }

    override fun disconnect() {
        // While a reconnect waits out its backoff no connection will report the end.
        if (lifecycle.disconnectRequested()) audio.endSession()
        connection?.disconnect()
    }

    override fun cancelReconnect() {
        // An attempt in flight is stopped too, so it cannot synchronize a session the user ended.
        if (lifecycle.cancelReconnect()) {
            audio.endSession()
            connection?.disconnect()
        }
    }

    override fun close() {
        // disconnect() only queues the teardown; the report arrives on a later turn and finds the
        // released router, which is harmless.
        disconnect()
        lifecycle.release()
        scope.cancel()
        audio.release()
    }

    override fun configure(config: SessionConfig): Boolean {
        val previous = this.config
        this.config = config
        if (config.accessTokens != previous.accessTokens && connection?.isConnected == true) {
            commands?.sendAccessTokens(config.accessTokens)
        }
        audio.apply(config.audio)
        return config.needsReconnectAfter(previous)
    }

    internal fun onConnectionEstablished() {
        val connection = config.connection
        val server = checkNotNull(connection.server) { "Connected without a target server" }
        commands().handshake(
            connection.clientName,
            Build.VERSION.RELEASE,
            server.username,
            server.password,
            config.accessTokens,
        )
    }

    internal fun onConnectionSynchronized() {
        val connection = connection?.takeIf { it.isConnected }
        val modelHandler = modelHandler
        if (connection == null || modelHandler == null || !lifecycle.synchronized()) return
        Log.v(TAG, "Connected")
        startAudio(connection, modelHandler)
    }

    /**
     * Restores the route and builds the pipeline on the audio thread; a pipeline that cannot start
     * becomes a warning.
     */
    private fun startAudio(connection: HumlaConnection, modelHandler: ModelHandler) {
        val self = modelHandler.getUser(connection.getSession())
        if (self == null) {
            // ServerSync named no known user: keep the session up without a microphone.
            Log.e(TAG, "No session user after ServerSync; audio not started")
            audio.engageRoute()
            warn(context.getString(R.string.no_session_user))
            return
        }
        val params = AudioSessionParams(
            self = self,
            maxBandwidth = connection.getMaxBandwidth(),
            codec = connection.getCodec(),
            targetId = currentVoiceTargetId,
            inputMode = audio.inputMode,
            udpProtocol = connection.udpProtocol,
        )
        audio.start(params, connection)
    }

    internal fun onConnectionDisconnected(e: HumlaException?) {
        if (e != null) Log.e(TAG, "Error: ${e.message} (reason: ${e.reason.name})") else Log.v(TAG, "Disconnected")
        val reason = e?.let { error -> disconnectReasonOf(error) { modelHandler?.getUser(it)?.name } }
        lost(reason ?: tlsFailure)
    }

    /** The attempt ended with [reason]: tears the connection's share down and moves the lifecycle on. */
    private fun lost(reason: DisconnectReason?) {
        // First: push-to-talk must be off before anything could reconnect.
        audio.stop()
        val autoReconnect = config.autoReconnect && reason is DisconnectReason.Network
        val next = lifecycle.lost(autoReconnect, reason)
        modelHandler = null
        currentVoiceTargetId = 0
        whisperTargetList.clear()
        if (next is SessionState.Disconnected) {
            // A late, reason-free report after cancelReconnect keeps the cancelled session's reason
            // and must not claim to have given up.
            if (autoReconnect && next.reason === reason) warn(context.getString(R.string.reconnect_gave_up))
            audio.endSession()
        }
    }

    internal fun onConnectionWarning(warning: ConnectionWarning) = warn(context.getString(warning.messageRes))

    private fun warn(message: String) {
        lastWarning = message
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, message))
    }

    /**
     * Delivers [message] unless it repeats the last warning. Compared with the last line rather
     * than per type, so the log never ends on a line that contradicts the current state.
     */
    private fun warnOnce(message: String) {
        if (message != lastWarning) warn(message)
    }

    /** Publishes [event]; info notices only once synchronized. Any thread. */
    internal fun emit(event: HumlaEvent) {
        if (event is HumlaEvent.Notice && event.level == HumlaEvent.Level.INFO && connection?.isSynchronized != true) {
            return
        }
        mutableEvents.tryEmit(event)
    }

    private fun conn(): HumlaConnection = checkNotNull(connection) { "Not connected" }

    private fun commands(): ServerCommands = checkNotNull(commands) { "Not connected" }

    /** The synchronized session's model; [IllegalStateException] outside of one. */
    private fun model(): ModelHandler {
        check(connection?.isSynchronized == true) { "Not synchronized with the server" }
        return checkNotNull(modelHandler) { "No model for the synchronized session" }
    }

    override val tcpLatency: Long get() = conn().getTCPLatency()

    override val udpLatency: Long get() = conn().getUDPLatency()

    override val maxBandwidth: Int get() = conn().getMaxBandwidth()

    /** The pipeline starts and stops asynchronously around the session, so this does not throw. */
    override val currentBandwidth: Int get() = audio.currentBandwidth

    override val serverVersion: Int get() = conn().getServerVersion()

    override val serverRelease: String? get() = conn().getServerRelease()

    override val serverOSName: String? get() = conn().getServerOSName()

    override val serverOSVersion: String? get() = conn().getServerOSVersion()

    override val sessionId: Int get() = conn().getSession()

    override val sessionUser: IUser? get() = model().getUser(sessionId)

    override val sessionChannel: IChannel?
        get() = checkNotNull(sessionUser) { "Session user should be set post-synchronization!" }.channel

    override fun getUser(session: Int): IUser? = model().getUser(session)

    override fun getChannel(id: Int): IChannel? = model().getChannel(id)

    override val rootChannel: IChannel? get() = getChannel(0)

    override val permissions: Int get() = model().permissions

    override val serverSettings: ServerSettings? get() = model().serverSettings

    override val codec: HumlaUDPMessageType? get() = conn().getCodec()

    override val transmitMode: TransmitMode get() = config.audio.transmitMode

    override val audioDevices: List<CommunicationDevice> get() = audio.devices

    override val activeAudioDevice: CommunicationDevice? get() = audio.activeDevice

    override val isEchoCancellationEnabled: Boolean get() = audio.isEchoCancellationEnabled

    override fun selectAudioDevice(id: Int) = audio.router.choose(id)

    override fun selectAutomaticAudioDevice() = audio.router.forgetChoice()

    override val isTalking: Boolean get() = audio.isTalking

    override fun setTalkingState(talking: Boolean) = audio.setTalking(talking)

    override fun joinChannel(channel: Int) = moveUserToChannel(sessionId, channel)

    override fun setLocalVolume(session: Int, volume: Float) {
        val user = modelHandler?.getUser(session) ?: return
        localVolumes?.set(user, volume)
    }

    override fun setListening(channel: Int, listen: Boolean) = commands().setListening(sessionId, channel, listen)

    override fun moveUserToChannel(session: Int, channel: Int) = commands().moveUser(session, channel)

    override fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean) =
        commands().createChannel(parent, name, description, position, temporary)

    override fun sendAccessTokens(tokens: List<String>) = commands().sendAccessTokens(tokens)

    override fun requestPermissions(channel: Int) = commands().requestPermissions(channel)

    override fun requestComment(session: Int) = commands().requestComment(session)

    override fun requestAvatar(session: Int) = commands().requestAvatar(session)

    override fun requestChannelDescription(channel: Int) = commands().requestChannelDescription(channel)

    override fun requestUserStats(session: Int) = commands().requestUserStats(session)

    override fun registerUser(session: Int) = commands().registerUser(session)

    override fun kickBanUser(session: Int, reason: String?, ban: Boolean) = commands().kickBanUser(session, reason, ban)

    override fun setUserComment(session: Int, comment: String?) = commands().setComment(session, comment)

    override fun setPrioritySpeaker(session: Int, priority: Boolean) = commands().setPrioritySpeaker(session, priority)

    override fun removeChannel(channel: Int) = commands().removeChannel(channel)

    override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) =
        commands().setMuteDeaf(session, mute, deaf)

    override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) = commands().setSelfMuteDeaf(mute, deaf)

    override fun linkChannels(channelA: IChannel, channelB: IChannel) =
        commands().linkChannels(channelA.id, channelB.id)

    override fun unlinkChannels(channelA: IChannel, channelB: IChannel) =
        commands().unlinkChannels(channelA.id, listOf(channelB.id))

    override fun unlinkAllChannels(channel: IChannel) =
        commands().unlinkChannels(channel.id, channel.links.map { it.id })

    override fun sendUserTextMessage(session: Int, message: String): Message {
        val model = model()
        commands().textToUser(session, message)
        // A message to an unknown session carries no user.
        val users = listOfNotNull(model.getUser(session))
        return sent(Message(sessionId, selfName(model), emptyList(), emptyList(), users, message))
    }

    override fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message {
        val model = model()
        commands().textToChannel(channel, message, tree)
        // An unknown channel is left out, as above.
        val channels = listOfNotNull(model.getChannel(channel))
        val trees = if (tree) channels else emptyList()
        return sent(Message(sessionId, selfName(model), channels, trees, emptyList(), message))
    }

    private fun sent(message: Message): Message = message.also { emit(HumlaEvent.MessageSent(it)) }

    private fun selfName(model: ModelHandler): String? =
        checkNotNull(model.getUser(sessionId)) { "No user for our own session" }.name

    override fun registerWhisperTarget(target: WhisperTarget): Byte {
        val id = whisperTargetList.append(target)
        if (id < 0) return -1
        commands().registerVoiceTarget(id.toInt(), target.createTarget())
        return id
    }

    override fun unregisterWhisperTarget(targetId: Byte) = whisperTargetList.free(targetId)

    override var voiceTargetId: Byte
        get() = currentVoiceTargetId
        set(targetId) {
            // `!= 0` rather than `> 0`: a negative byte masks to a negative value.
            require((targetId.toInt() and 0x1F.inv()) == 0) { "Target ID must be at most 5 bits." }
            currentVoiceTargetId = targetId
            // Also reaches the running pipeline, so the next rebuild keeps targeting it.
            audio.setVoiceTargetId(targetId)
            emit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.fromId(targetId)))
        }

    override val voiceTargetMode: VoiceTargetMode get() = VoiceTargetMode.fromId(currentVoiceTargetId)

    override val whisperTarget: WhisperTarget?
        get() = if (voiceTargetMode == VoiceTargetMode.WHISPER) whisperTargetList.get(currentVoiceTargetId) else null

    /**
     * The callbacks of one connection. A connection replaced by a retry or a new attempt may still
     * report its end; only the current one reaches the session.
     */
    private inner class ConnectionCallbacks : HumlaConnection.HumlaConnectionListener {
        lateinit var connection: HumlaConnection

        private val isCurrent: Boolean get() = this@HumlaSession.connection === connection

        override fun onConnectionEstablished() {
            if (isCurrent) this@HumlaSession.onConnectionEstablished()
        }

        override fun onConnectionSynchronized() {
            if (isCurrent) this@HumlaSession.onConnectionSynchronized()
        }

        override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) {
            if (isCurrent) tlsFailure = DisconnectReason.TlsUntrusted(chain.toList())
        }

        override fun onConnectionCertificateChanged(chain: Array<X509Certificate>) {
            if (isCurrent) tlsFailure = DisconnectReason.TlsCertificateChanged(chain.toList())
        }

        override fun onConnectionDisconnected(e: HumlaException?) {
            if (isCurrent) this@HumlaSession.onConnectionDisconnected(e)
        }

        override fun onConnectionWarning(warning: ConnectionWarning) {
            if (isCurrent) this@HumlaSession.onConnectionWarning(warning)
        }
    }

    companion object {
        private const val TAG = "HumlaSession"
        private val dnsLookupInstalled = AtomicBoolean()

        /** Events a collector may fall behind before the oldest are dropped. */
        const val EVENT_BUFFER = 8_192
    }
}
