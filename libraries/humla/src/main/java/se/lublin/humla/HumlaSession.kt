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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import se.lublin.humla.audio.AudioHandler
import se.lublin.humla.audio.AudioHandlerFactory
import se.lublin.humla.audio.AudioHost
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.AudioSessionParams
import se.lublin.humla.audio.DefaultAudioHandlerFactory
import se.lublin.humla.audio.PlaybackParams
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.routing.AndroidCommunicationDevices
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.CommunicationDevices
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Latency
import se.lublin.humla.model.LocalUserSettings
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerInfo
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.model.localVolumeScope
import se.lublin.humla.net.ConnectionParams
import se.lublin.humla.net.ConnectionWarning
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.ReconnectPolicy
import se.lublin.humla.net.TrustStore
import se.lublin.humla.protocol.LocalInput
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
import se.lublin.humla.session.TalkStates
import se.lublin.humla.session.disconnectReasonOf
import se.lublin.humla.session.messageRes
import se.lublin.humla.util.HumlaLog
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
 * callback runs there. The model is written on the protocol context and the pipeline runs on its
 * own threads; [state] and [events] may be collected anywhere. The parameters after [config] are
 * the platform, replaceable for tests.
 */
@Suppress("TooManyFunctions", "LongParameterList") // The protocol's whole client API; the platform seams.
public class HumlaSession internal constructor(
    private val context: Context,
    config: SessionConfig,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    network: NetworkMonitor = AndroidNetworkMonitor(
        context.getSystemService(ConnectivityManager::class.java),
        mainHandler,
    ),
    wakeLock: SessionWakeLock = AndroidSessionWakeLock(context.getSystemService(PowerManager::class.java)),
    communicationDevices: CommunicationDevices? = null,
    private val connectionFactory: (ConnectionParams, HumlaConnection.Listener) -> HumlaConnection =
        { params, listener -> HumlaConnection(params, listener, mainHandler::post) },
    audioFactory: AudioHandlerFactory = DefaultAudioHandlerFactory,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) : IHumlaSession {

    public constructor(context: Context, config: SessionConfig) : this(context, config, Handler(Looper.getMainLooper()))

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
     * the protocol context ([emit] checks it).
     */
    @Volatile
    internal var connection: HumlaConnection? = null
        private set

    private var commands: ServerCommands? = null

    /** The model of the live connection; dropped when it ends, so its tree can be collected. */
    @Volatile
    internal var modelHandler: ModelHandler? = null
        private set

    /** Guards the check that a snapshot comes from the live [modelHandler] together with its publication. */
    private val modelLock = Any()

    private val mutableModel = MutableStateFlow<ServerState?>(null)

    override val model: StateFlow<ServerState?> = mutableModel.asStateFlow()

    /** What this device remembers about users, carried from one connection of the session to the next. */
    private var localUsers = LocalUserSettings(
        volumes = config.localVolumes,
        mutedUserIds = config.connection.localMuteHistory.toSet(),
        ignoredUserIds = config.connection.localIgnoreHistory.toSet(),
        serverScope = config.connection.server?.localVolumeScope,
    )

    /** Written on the protocol context with every snapshot, read by the network and playback threads. */
    @Volatile
    private var playbackParams = PlaybackParams.DEFAULT

    private val mutableTalkStates = TalkStates(mainHandler.looper)

    override val talkStates: StateFlow<Map<Int, TalkState>> get() = mutableTalkStates.states

    /** Waits for the snapshot that knows the own user, to build the pipeline for them. */
    private var audioStart: Job? = null

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
            val self = mutableModel.value?.selfSession ?: return
            mutableTalkStates.report(self, if (talking) TalkState.TALKING else TalkState.PASSIVE)
        }
    }

    private val audioOutputListener = object : AudioOutput.AudioOutputListener {
        override val playbackParams: PlaybackParams get() = this@HumlaSession.playbackParams

        override fun onTalkStateUpdated(session: Int, state: TalkState) = mutableTalkStates.report(session, state)
    }

    internal val audioSession = AudioSession(
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
            HumlaLog.e(TAG, "connect() without a target server")
            lost(DisconnectReason.Failed(context.getString(R.string.no_target_server), null))
            return
        }

        val params = ConnectionParams(
            server = server,
            forceTcp = config.forceTcp,
            useTor = config.useTor,
            certificate = config.certificate?.pkcs12,
            certificatePassword = config.certificate?.password,
            trustStore = config.trustStorePath?.let {
                TrustStore(it, config.trustStorePassword, config.trustStoreFormat)
            },
        )
        val callbacks = ConnectionCallbacks()
        val connection = connectionFactory(params, callbacks)
        callbacks.connection = connection
        this.connection = connection

        val publisher = SnapshotPublisher(connection)
        val commands = ServerCommands(connection::sendTCPMessage)
        this.commands = commands
        val modelHandler = ModelHandler(ServerState.empty(localUsers), ::emit, publisher, commands::requestAvatar)
        publisher.handler = modelHandler
        this.modelHandler = modelHandler
        connection.addTcpHandler(modelHandler)

        // Resolves the host (SRV lookup included) and opens the socket on the protocol context;
        // every failure, certificate errors included, arrives at onConnectionDisconnected.
        connection.connect()
    }

    override fun disconnect() {
        // While a reconnect waits out its backoff no connection will report the end.
        if (lifecycle.disconnectRequested()) audioSession.endSession()
        connection?.disconnect()
    }

    override fun cancelReconnect() {
        // An attempt in flight is stopped too, so it cannot synchronize a session the user ended.
        if (lifecycle.cancelReconnect()) {
            audioSession.endSession()
            connection?.disconnect()
        }
    }

    override fun close() {
        // disconnect() only queues the teardown; the report arrives on a later turn and finds the
        // released router, which is harmless.
        disconnect()
        lifecycle.release()
        scope.cancel()
        audioSession.release()
    }

    override fun configure(config: SessionConfig): Boolean {
        val previous = this.config
        this.config = config
        if (config.accessTokens != previous.accessTokens && connection?.isConnected == true) {
            commands?.sendAccessTokens(config.accessTokens)
        }
        audioSession.apply(config.audio)
        return config.needsReconnectAfter(previous)
    }

    internal fun onConnectionEstablished() {
        val connection = config.connection
        val server = checkNotNull(connection.server) { "Connected without a target server" }
        checkNotNull(commands) { "Connected without a connection" }.handshake(
            connection.clientName,
            Build.VERSION.RELEASE,
            server.username,
            server.password,
            config.accessTokens,
        )
    }

    internal fun onConnectionSynchronized() {
        val connection = connection?.takeIf { it.isConnected }
        if (connection == null || modelHandler == null || !lifecycle.synchronized()) return
        HumlaLog.v(TAG, "Connected")
        // The connection reports ServerSync before the model has read it; its snapshot follows.
        audioStart = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val synced = mutableModel.first { it?.selfSession != null }
            startAudio(connection, synced?.self)
        }
    }

    /**
     * Restores the route and builds the pipeline on the audio thread; a pipeline that cannot start
     * becomes a warning.
     */
    private fun startAudio(connection: HumlaConnection, self: UserState?) {
        if (self == null) {
            // ServerSync named no known user: keep the session up without a microphone.
            HumlaLog.e(TAG, "No session user after ServerSync; audio not started")
            audioSession.engageRoute()
            warn(context.getString(R.string.no_session_user))
            return
        }
        // Read on the main thread after ServerSync; a connection that ended since has no info.
        val info = connection.serverInfo ?: return
        val params = AudioSessionParams(
            self = self,
            maxBandwidth = info.maxBandwidth,
            codec = if (info.opus) HumlaUDPMessageType.UDPVoiceOpus else null,
            targetId = currentVoiceTargetId,
            inputMode = audioSession.inputMode,
            udpProtocol = connection.udpProtocol,
        )
        audioSession.start(params, connection)
    }

    internal fun onConnectionDisconnected(e: HumlaException?) {
        if (e != null) {
            HumlaLog.e(TAG, "Error: ${e.message} (reason: ${e.reason.name})")
        } else {
            HumlaLog.v(TAG, "Disconnected")
        }
        val reason = e?.let { error -> disconnectReasonOf(error) { mutableModel.value?.user(it)?.name } }
        lost(reason ?: tlsFailure)
    }

    /** The attempt ended with [reason]: tears the connection's share down and moves the lifecycle on. */
    private fun lost(reason: DisconnectReason?) {
        // First: push-to-talk must be off before anything could reconnect.
        audioStart?.cancel()
        audioSession.stop()
        val autoReconnect = config.autoReconnect && reason is DisconnectReason.Network
        val next = lifecycle.lost(autoReconnect, reason)
        synchronized(modelLock) {
            mutableModel.value?.let { localUsers = it.local }
            modelHandler = null
            mutableModel.value = null
            playbackParams = PlaybackParams.DEFAULT
        }
        mutableTalkStates.clear()
        currentVoiceTargetId = 0
        whisperTargetList.clear()
        if (next is SessionState.Disconnected) {
            // A late, reason-free report after cancelReconnect keeps the cancelled session's reason
            // and must not claim to have given up.
            if (autoReconnect && next.reason === reason) warn(context.getString(R.string.reconnect_gave_up))
            audioSession.endSession()
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

    /** Hands [input] to the model's writer; ignored without a connection. */
    private fun local(input: LocalInput) {
        val handler = modelHandler ?: return
        connection?.post { handler.onLocal(input) }
    }

    /** Publishes the snapshots of one connection's model, until another model replaces it. */
    private inner class SnapshotPublisher(private val connection: HumlaConnection) : ModelHandler.Publisher {
        lateinit var handler: ModelHandler

        override fun post(block: () -> Unit) = connection.post(block)

        override fun publish(state: ServerState) {
            synchronized(modelLock) {
                if (modelHandler !== handler) return
                val previous = mutableModel.value
                if (previous == null || previous.users !== state.users) {
                    playbackParams = PlaybackParams.of(state.users.values)
                }
                mutableModel.value = state
            }
        }
    }

    /**
     * The active voice target: 0 is normal speech, 1-30 are whisper targets, 31 is the server
     * loopback. Also reaches the running pipeline, so the next rebuild keeps targeting it.
     */
    internal var voiceTargetId: Byte
        get() = currentVoiceTargetId
        set(targetId) {
            // `!= 0` rather than `> 0`: a negative byte masks to a negative value.
            require((targetId.toInt() and 0x1F.inv()) == 0) { "Target ID must be at most 5 bits." }
            currentVoiceTargetId = targetId
            audioSession.setVoiceTargetId(targetId)
            emit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.fromId(targetId)))
        }

    override val serverInfo: ServerInfo? get() = connection?.serverInfo

    override val latency: Latency? get() = connection?.latency

    override val actions: SessionActions = Actions()

    override val audio: AudioControls = Audio()

    /** The requests of the synchronized connection; null outside of one. */
    private fun synced(): ServerCommands? = commands.takeIf { connection?.isSynchronized == true }

    @Suppress("TooManyFunctions") // SessionActions, one request each.
    private inner class Actions : SessionActions {
        private val model: ServerState? get() = mutableModel.value

        override fun joinChannel(channel: Int) {
            val self = model?.selfSession ?: return
            synced()?.moveUser(self, channel)
        }

        override fun moveUser(session: Int, channel: Int) {
            synced()?.moveUser(session, channel)
        }

        override fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean) {
            synced()?.createChannel(parent, name, description, position, temporary)
        }

        override fun removeChannel(channel: Int) {
            synced()?.removeChannel(channel)
        }

        override fun linkChannels(channel: Int, other: Int) {
            synced()?.linkChannels(channel, other)
        }

        override fun unlinkChannels(channel: Int, other: Int) {
            synced()?.unlinkChannels(channel, listOf(other))
        }

        override fun unlinkAllChannels(channel: Int) {
            val links = model?.channel(channel)?.links ?: return
            synced()?.unlinkChannels(channel, links.toList())
        }

        override fun setListening(channel: Int, listen: Boolean) {
            val self = model?.selfSession ?: return
            synced()?.setListening(self, channel, listen)
        }

        override fun sendAccessTokens(tokens: List<String>) {
            synced()?.sendAccessTokens(tokens)
        }

        override fun requestPermissions(channel: Int) {
            synced()?.requestPermissions(channel)
        }

        override fun requestComment(session: Int) {
            synced()?.requestComment(session)
        }

        override fun requestChannelDescription(channel: Int) {
            synced()?.requestChannelDescription(channel)
        }

        override fun requestUserStats(session: Int) {
            synced()?.requestUserStats(session)
        }

        override fun registerUser(session: Int) {
            synced()?.registerUser(session)
        }

        override fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
            synced()?.kickBanUser(session, reason, ban)
        }

        override fun setUserComment(session: Int, comment: String?) {
            synced()?.setComment(session, comment)
        }

        override fun setPrioritySpeaker(session: Int, priority: Boolean) {
            synced()?.setPrioritySpeaker(session, priority)
        }

        override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
            synced()?.setMuteDeaf(session, mute, deaf)
        }

        override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
            synced()?.setSelfMuteDeaf(mute, deaf)
        }

        override fun sendUserTextMessage(session: Int, message: String): Message? = send { model, commands ->
            commands.textToUser(session, message)
            // A message to an unknown session carries no user.
            val users = listOfNotNull(model.user(session))
            Message(model.selfSession!!, model.self?.name, emptyList(), emptyList(), users, message)
        }

        override fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message? =
            send { model, commands ->
                commands.textToChannel(channel, message, tree)
                // An unknown channel is left out, as above.
                val channels = listOfNotNull(model.channel(channel))
                val trees = if (tree) channels else emptyList()
                Message(model.selfSession!!, model.self?.name, channels, trees, emptyList(), message)
            }

        /** Sends and publishes what [build] makes, once the own session is known; null otherwise. */
        private inline fun send(build: (ServerState, ServerCommands) -> Message): Message? {
            val commands = synced()
            val model = model?.takeIf { it.selfSession != null }
            if (commands == null || model == null) return null
            return build(model, commands).also { emit(HumlaEvent.MessageSent(it)) }
        }

        override fun setLocalMuted(session: Int, muted: Boolean) = local(LocalInput.Mute(session, muted))

        override fun setLocalIgnored(session: Int, ignored: Boolean) = local(LocalInput.Ignore(session, ignored))

        override fun setLocalVolume(session: Int, volume: Float) = local(LocalInput.Volume(session, volume))

        override val voiceTargetMode: VoiceTargetMode get() = VoiceTargetMode.fromId(currentVoiceTargetId)

        override val whisperTarget: WhisperTarget?
            get() = if (voiceTargetMode == VoiceTargetMode.WHISPER) whisperTargetList[currentVoiceTargetId] else null

        override fun whisperTo(target: WhisperTarget): Boolean {
            val commands = synced() ?: return false
            if (voiceTargetMode == VoiceTargetMode.WHISPER) whisperTargetList.free(currentVoiceTargetId)
            val id = whisperTargetList.append(target)
            if (id >= 0) {
                commands.registerVoiceTarget(id.toInt(), target.createTarget())
                voiceTargetId = id
            }
            return id >= 0
        }

        override fun stopWhispering() {
            if (voiceTargetMode != VoiceTargetMode.WHISPER) return
            val target = currentVoiceTargetId
            voiceTargetId = 0
            whisperTargetList.free(target)
        }
    }

    private inner class Audio : AudioControls {
        override val route: StateFlow<Int?> get() = audioSession.route

        override val transmitMode: TransmitMode get() = config.audio.transmitMode

        override val isTalking: Boolean get() = audioSession.isTalking

        override fun setTalking(talking: Boolean) = audioSession.setTalking(talking)

        override val devices: List<CommunicationDevice> get() = audioSession.devices

        override val activeDevice: CommunicationDevice? get() = audioSession.activeDevice

        override val isEchoCancellationEnabled: Boolean get() = audioSession.isEchoCancellationEnabled

        override fun selectDevice(id: Int) = audioSession.router.choose(id)

        override fun selectAutomaticDevice() = audioSession.router.forgetChoice()

        override val currentBandwidth: Int get() = audioSession.currentBandwidth
    }

    /**
     * The callbacks of one connection. A connection replaced by a retry or a new attempt may still
     * report its end; only the current one reaches the session.
     */
    private inner class ConnectionCallbacks : HumlaConnection.Listener {
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

    private companion object {
        private const val TAG = "HumlaSession"
        private val dnsLookupInstalled = AtomicBoolean()

        /** Events a collector may fall behind before the oldest are dropped. */
        private const val EVENT_BUFFER = 8_192
    }
}
