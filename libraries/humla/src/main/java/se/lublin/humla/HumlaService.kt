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

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.protobuf.MessageLite
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import se.lublin.humla.audio.AudioConfig
import se.lublin.humla.audio.AudioController
import se.lublin.humla.audio.AudioHandler
import se.lublin.humla.audio.AudioHandlerFactory
import se.lublin.humla.audio.AudioHost
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.AudioSessionParams
import se.lublin.humla.audio.DefaultAudioHandlerFactory
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.audio.routing.AndroidCommunicationDevices
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.AudioRouter
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.CommunicationDevices
import se.lublin.humla.exception.HumlaDisconnectedException
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
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.ReconnectPolicy
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.session.SessionStateMachine
import se.lublin.humla.util.Constants
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.MumbleVersion
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate

/**
 * Owns one server session (connection, model, audio pipeline and audio routing) and exposes it
 * through [IHumlaService] and [IHumlaSession].
 */
open class HumlaService : Service(), IHumlaService, IHumlaSession,
    HumlaConnection.HumlaConnectionListener, HumlaLogger {

    /** What the client configured; see [configure]. */
    final override var sessionConfig = SessionConfig()
        private set

    /** Current audio settings: [sessionConfig]'s audio half plus what the route decides. */
    private var audioConfig = AudioConfig()

    /** Held by identity: the audio thread and `isTalking` must see the same toggle object. */
    @VisibleForTesting
    internal lateinit var inputMode: IInputMode
        private set

    private var _voiceTargetId: Byte = 0
    private lateinit var whisperTargetList: WhisperTargetList

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var handler: Handler

    /**
     * Emitted from any thread without suspending. Beyond [EVENT_BUFFER] events not yet collected
     * by the slowest collector, the oldest are dropped.
     */
    @VisibleForTesting
    internal val mutableEvents = MutableSharedFlow<HumlaEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The connection of the latest attempt, kept after it ends. Written on the main thread, read on
     * the protocol thread (emit() checks it).
     */
    @Volatile
    @VisibleForTesting
    internal var connection: HumlaConnection? = null

    @Volatile
    @VisibleForTesting
    internal var modelHandler: ModelHandler? = null
    private var localVolumes: LocalVolumes? = null
    /** Owns the audio pipeline's lifecycle on its own thread, so nothing here joins on main. */
    @VisibleForTesting
    internal lateinit var audioController: AudioController
        private set

    /** Engaged only while a session is synchronized. */
    @VisibleForTesting
    internal lateinit var router: AudioRouter
        private set

    /** Last warning logged, so a refusal repeated per reconnect attempt is logged once. */
    @Volatile
    private var lastWarning: String? = null

    @VisibleForTesting
    internal lateinit var activityInputMode: ActivityInputMode
        private set
    private lateinit var toggleInputMode: ToggleInputMode
    private lateinit var continuousInputMode: ContinuousInputMode

    /**
     * The session lifecycle. Confined to the main thread, where binder calls, connection callbacks,
     * the reconnect timer and the network callback all run; other threads collect [sessionState].
     */
    @VisibleForTesting
    internal lateinit var stateMachine: SessionStateMachine
        private set

    /** Test seam: builds the connection used by [connect]. Set before `onCreate`. */
    var connectionFactory: (HumlaConnection.HumlaConnectionListener) -> HumlaConnection =
        { HumlaConnection(it) }

    /** Test seam: the reconnect backoff. Set before `onCreate`. */
    var reconnectPolicy: ReconnectPolicy = ReconnectPolicy()

    /** Test seam: builds the audio pipeline. */
    var audioFactory: AudioHandlerFactory = DefaultAudioHandlerFactory()

    /** A test may set a fake before [onCreate]; otherwise [onCreate] creates the Android one. */
    var communicationDevices: CommunicationDevices? = null

    /** Waits for a default network while the reconnect is on hold, and retries as soon as one is up. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Registered with handler, so this runs on the main thread like every other mutator.
            unregisterNetworkCallback()
            if (stateMachine.current !is SessionState.ConnectionLost) return
            Log.v(TAG, "Connectivity restored, attempting reconnect.")
            if (stateMachine.connectivityRestored()) handler.post(reconnectRunnable)
        }
    }
    private var networkCallbackRegistered = false

    /**
     * The backoff timer. Pending posts are never cancelled: the state machine refuses a retry in
     * every state but ConnectionLost. A stale post can therefore retry a later loss early, once.
     */
    private val reconnectRunnable = Runnable {
        if (stateMachine.reconnectTimerFired()) startSession()
    }

    private val routerListener = object : AudioRouter.Listener {
        override fun onRouteChanged(type: Int?) = setRoutedDevice(type)

        override fun onRouteRefused() = logWarningOnce(getString(R.string.audio_route_refused))
    }

    private val audioControllerListener = object : AudioController.Listener {
        override fun onAudioStarted() = Unit

        /** A pipeline that cannot start becomes a chat-log warning. */
        override fun onAudioFailed(message: String) = logWarning(message)

        /** Microphone silencing and decoder errors reach the chat log. */
        override fun onAudioWarning(message: String) = logWarning(message)
    }

    private val audioInputListener: AudioHandler.AudioEncodeListener =
        object : AudioHandler.AudioEncodeListener {
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                val connection = connection
                if (connection != null && connection.isSynchronized) {
                    connection.sendUDPMessage(data, length, false)
                }
            }

            override fun onTalkingStateChanged(talking: Boolean) {
                handler.post {
                    // A leftover from a terminated connection when the session is inactive.
                    if (!isSynchronized) return@post
                    val modelHandler = modelHandler ?: return@post
                    val connection = connection ?: return@post
                    val currentUser = modelHandler.getUser(connection.getSession()) ?: return@post

                    currentUser.talkState = if (talking) TalkState.TALKING else TalkState.PASSIVE
                    emit(HumlaEvent.UserTalkStateUpdated(currentUser))
                }
            }
        }

    private val audioOutputListener: AudioOutput.AudioOutputListener =
        object : AudioOutput.AudioOutputListener {
            override fun onUserTalkStateUpdated(user: User) {
                emit(HumlaEvent.UserTalkStateUpdated(user))
            }

            override fun getUser(session: Int): User? = modelHandler?.getUser(session)
        }

    /** Only keeps the service started; connecting goes through [configure] and [connect]. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService")
        handler = Handler(mainLooper)
        stateMachine = SessionStateMachine(reconnectPolicy)
        // One instance per service life, so a platform refusal is reported once rather than on
        // every route decision.
        val devices = communicationDevices ?: AndroidCommunicationDevices(
            getSystemService(AUDIO_SERVICE) as AudioManager,
            handler,
        ) { logWarningOnce(getString(R.string.bluetooth_sco_denied)) }
        communicationDevices = devices
        router = AudioRouter(devices, routerListener)
        router.preferred = sessionConfig.preferredAudioDevice
        toggleInputMode = ToggleInputMode()
        activityInputMode = ActivityInputMode(VoiceActivityDetector(sessionConfig.vadConfig))
        continuousInputMode = ContinuousInputMode()
        inputMode = activityInputMode
        whisperTargetList = WhisperTargetList()
        // Eagerly, and for the life of the service: one controller, one thread, quit in onDestroy.
        // `{ audioFactory }` and not `audioFactory`, so a factory set after onCreate still takes.
        audioController = AudioController(
            AudioHost(this, this, audioInputListener, audioOutputListener),
            { audioFactory },
            audioControllerListener,
            handler,
        )

        // initialize minidns dns lookup mechanisms
        AndroidUsingLinkProperties.setup(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Without this, a service destroyed while connected would leave the non-daemon protocol
        // thread running. disconnect() only queues the teardown; onConnectionDisconnected arrives
        // on a later main-looper turn and disengages the already released router, which is harmless.
        disconnect()
        unregisterNetworkCallback()
        router.disengage()
        router.release()
        // Posts the teardown and quits the looper without waiting for either.
        audioController.quit()
    }

    override fun onBind(intent: Intent?): IBinder = HumlaBinder(this)

    /**
     * User-initiated connect. Ignored by the state machine while an attempt is in flight or a
     * session is up.
     */
    override fun connect() {
        if (!stateMachine.connectRequested()) return
        startSession()
    }

    /** Builds and starts one connection attempt; called from [connect] and [reconnectRunnable]. */
    private fun startSession() {
        // Whisper slots are cleared when a session ends. The voice target can be set while
        // disconnected, so it is reset here.
        _voiceTargetId = 0

        // Checked before anything is built, so a misconfigured start allocates nothing and is
        // reported as a failed attempt.
        val config = sessionConfig
        val server = config.server
        if (server == null) {
            Log.e(TAG, "connect() without a target server")
            stateMachine.disconnectRequested()
            emit(
                HumlaEvent.Disconnected(
                    HumlaException(
                        getString(R.string.no_target_server),
                        HumlaException.HumlaDisconnectReason.OTHER_ERROR,
                    )
                )
            )
            return
        }

        val connection = connectionFactory(this)
        this.connection = connection
        connection.setForceTCP(config.forceTcp)
        connection.setUseTor(config.useTor)
        connection.setKeys(config.certificate?.pkcs12, config.certificate?.password)
        connection.setTrustStore(config.trustStorePath, config.trustStorePassword, config.trustStoreFormat)

        val localVolumes = LocalVolumes(server, config.localVolumes)
        this.localVolumes = localVolumes
        val modelHandler =
            ModelHandler(::emit, config.localMuteHistory, config.localIgnoreHistory, localVolumes)
        this.modelHandler = modelHandler
        connection.addTcpHandler(modelHandler)

        emit(HumlaEvent.Connecting)

        try {
            // Resolves the host (SRV lookup included) and opens the socket on the protocol thread;
            // every failure, certificate errors included, arrives at onConnectionDisconnected.
            connection.connect(server)
        } catch (e: IllegalStateException) {
            // A main-thread collector sees Connecting inline, so it may already have
            // disconnected this single-use connection and connect() refuses. Report a failed
            // attempt instead of throwing out of onStartCommand or the reconnect runnable.
            Log.w(TAG, "Connection was cancelled before it could start", e)
            stateMachine.disconnectRequested()
            emit(HumlaEvent.Disconnected(HumlaException(e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)))
        }
    }

    /**
     * Ends the session for good. While a reconnect waits out its backoff there is no live
     * connection to report the end, so the wake lock and network callback are released here.
     */
    override fun disconnect() {
        val waiting = stateMachine.current is SessionState.ConnectionLost
        stateMachine.disconnectRequested()
        if (waiting) releaseSessionResources()
        connection?.disconnect()
    }

    val isConnectionEstablished: Boolean
        get() = connection?.isConnected == true

    /**
     * @return true if Humla has received the ServerSync message, indicating synchronization with
     * the server's model and settings. This is the main state of the service.
     */
    val isSynchronized: Boolean
        get() = connection?.isSynchronized == true

    override fun onConnectionEstablished() {
        val version = MumbleVersion.clientVersion(sessionConfig.clientName, "Android", Build.VERSION.RELEASE)

        val auth = Mumble.Authenticate.newBuilder()
        val server = checkNotNull(sessionConfig.server) { "Connected without a target server" }
        auth.setUsername(server.username)
        auth.setPassword(server.password)
        auth.setOpus(true)
        auth.addAllTokens(sessionConfig.accessTokens)

        val connection = conn()
        connection.sendTCPMessage(version, HumlaTCPMessageType.Version)
        connection.sendTCPMessage(auth.build(), HumlaTCPMessageType.Authenticate)
    }

    override fun onConnectionSynchronized() {
        val connection = conn()
        // early disconned?
        if (!connection.isConnected) {
            return
        }

        // TODO hackish, but this seems to happen?!
        val modelHandler = modelHandler
        if (modelHandler == null) {
            Log.e(TAG, "onConnectionSynchronized: model handler is null")
            return
        }

        stateMachine.synchronized()

        Log.v(TAG, "Connected")
        // The lock is reference counted and taken once per session, but released only when the
        // session ends for good.
        if (!wakeLock.isHeld) wakeLock.acquire()

        // Restore the route the user asked for; onConnectionDisconnected drops it.
        router.engage()

        startAudio(connection, modelHandler)

        emit(HumlaEvent.Connected)
    }

    /**
     * Hands the session's inputs to the audio controller, which builds the pipeline on its own
     * thread. A pipeline that cannot start arrives as [AudioController.Listener.onAudioFailed].
     */
    private fun startAudio(connection: HumlaConnection, modelHandler: ModelHandler) {
        val self = modelHandler.getUser(connection.getSession())
        if (self == null) {
            // ServerSync named no known user: keep the session up without a microphone.
            Log.e(TAG, "No session user after ServerSync; audio not started")
            logWarning(getString(R.string.no_session_user))
            return
        }
        val params = AudioSessionParams(
            self = self,
            maxBandwidth = connection.getMaxBandwidth(),
            codec = connection.getCodec(),
            targetId = _voiceTargetId,
            inputMode = inputMode,
            udpProtocol = connection.udpProtocol,
        )
        audioController.start(audioConfig, params, connection)
    }

    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) {
        emit(HumlaEvent.TlsHandshakeFailed(chain.toList()))
    }

    override fun onConnectionCertificateChanged(chain: Array<X509Certificate>) {
        emit(HumlaEvent.TlsCertificateChanged(chain.toList()))
    }

    override fun onConnectionDisconnected(e: HumlaException?) {
        // Clear push-to-talk first: the toggle outlives the connection, so an auto-reconnect would
        // otherwise transmit without a key press. An event collector can't do it: isConnected is
        // already false by then.
        toggleInputMode.setTalkingOn(false)

        if (e != null) {
            Log.e(TAG, "Error: " + e.message + " (reason: " + e.reason.name + ")")
        } else {
            Log.v(TAG, "Disconnected")
        }

        val autoReconnect = sessionConfig.autoReconnect && e != null &&
            e.reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
        val next = stateMachine.lost(autoReconnect, e)

        // The route is a session resource and the wish is not, so this runs on every disconnect,
        // auto-reconnect included; onConnectionSynchronized is where it comes back.
        router.disengage()
        // Asynchronous: the audio threads are joined on humla-audio-control, never on main.
        audioController.shutdown()

        // Readers throw once disconnected; this only lets the channel tree and users be collected.
        modelHandler = null
        _voiceTargetId = 0
        whisperTargetList.clear()

        if (next is SessionState.ConnectionLost) {
            // The wake lock, the Bluetooth wish, the mute/deafen state and the app's foreground
            // notification survive this transition.
            scheduleReconnect(next.reconnectInMillis)
        } else {
            // A late, error-free report after cancelReconnect keeps the cancelled session's error
            // and must not claim to have given up.
            val ended = next as SessionState.Disconnected
            if (autoReconnect && ended.error === e) logWarning(getString(R.string.reconnect_gave_up))
            releaseSessionResources()
        }

        emit(HumlaEvent.Disconnected(e))
    }

    override fun onConnectionWarning(warning: ConnectionWarning) {
        logWarning(getString(warning.messageRes))
    }

    override fun logInfo(message: String) {
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.INFO, message))
    }

    override fun logWarning(message: String) {
        lastWarning = message
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, message))
    }

    /**
     * Delivers [message] unless it repeats the last warning. Compared with the last line rather
     * than per type, so the log never ends on a line that contradicts the current state.
     */
    protected fun logWarningOnce(message: String) {
        if (message == lastWarning) return
        logWarning(message)
    }

    override fun logError(message: String) {
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, message))
    }

    /** Publishes [event]; info notices only once synchronized. Any thread. */
    @VisibleForTesting
    internal fun emit(event: HumlaEvent) {
        if (event is HumlaEvent.Notice && event.level == HumlaEvent.Level.INFO && !isSynchronized) return
        mutableEvents.tryEmit(event)
    }

    override val events: SharedFlow<HumlaEvent>
        get() = mutableEvents.asSharedFlow()

    private fun scheduleReconnect(delayMillis: Long) {
        if (isOnline()) {
            Log.v(TAG, "Reconnecting in $delayMillis ms")
            handler.postDelayed(reconnectRunnable, delayMillis)
        } else {
            // No point in burning attempts while there is no network; wait for it to come back.
            Log.v(TAG, "Offline; waiting for connectivity before reconnecting.")
            registerNetworkCallback()
        }
    }

    /** Gives back everything a live session holds. Only a Disconnected state reaches this. */
    private fun releaseSessionResources() {
        unregisterNetworkCallback()
        // The chooser's pick belongs to this session, as a pick in the phone app belongs to one
        // call; a dropped connection keeps it, the end of the session does not.
        router.forgetChoice()
        if (wakeLock.isHeld) wakeLock.release()
    }

    /**
     * Whether a default network is up. Deliberately not a `NET_CAPABILITY_INTERNET` check:
     * Robolectric's ShadowConnectivityManager reports no capabilities unless a test sets them.
     */
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(networkCallback, handler)
            networkCallbackRegistered = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Error registering the network callback: " + e.message)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Not registered; nothing to do.
        }
    }

    /**
     * Applies [config] as a whole. Audio, voice-activity and routing settings take effect live;
     * the connection settings only on the next connection.
     * @return true if a reconnect is required for the changes to take effect.
     */
    override fun configure(config: SessionConfig): Boolean {
        val inputMode = when (config.transmitMode) {
            Constants.TRANSMIT_PUSH_TO_TALK -> toggleInputMode
            Constants.TRANSMIT_CONTINUOUS -> continuousInputMode
            Constants.TRANSMIT_VOICE_ACTIVITY -> activityInputMode
            else -> throw IllegalArgumentException("Unknown transmit mode ${config.transmitMode}")
        }
        val previous = sessionConfig
        sessionConfig = config
        this.inputMode = inputMode
        // Applied live: the input mode outlives pipeline rebuilds.
        activityInputMode.setVadConfig(config.vadConfig)

        if (config.accessTokens != previous.accessTokens) {
            connection?.takeIf { it.isConnected }?.sendAccessTokens(config.accessTokens)
        }
        if (config.preferredAudioDevice != previous.preferredAudioDevice) {
            // Live: the next apply routes it, and a user's explicit choice is left standing.
            router.preferred = config.preferredAudioDevice
            router.apply()
        }

        audioConfig = audioConfig.copy(
            amplitudeBoost = config.amplitudeBoost,
            transmitMode = config.transmitMode,
            inputSampleRate = config.inputSampleRate,
            targetBitrate = config.inputQuality,
            audioSource = config.audioSource,
            audioStream = config.audioStream,
            targetFramesPerPacket = config.framesPerPacket,
            // Stored as requested; AudioConfig.halfDuplex applies it against the mode in force.
            halfDuplexRequested = config.halfDuplex,
            preprocessorEnabled = config.preprocessorEnabled,
            noiseSuppression = config.noiseSuppressionMethod,
            echoCancellation = echoCancellationFor(audioConfig.routedDeviceType),
            speexNoiseSuppressDb = config.speexNoiseSuppressDb,
            androidNoiseSuppressor = config.androidNoiseSuppressor,
            androidAgc = config.androidAgc,
        )
        // Unconditional: AudioController skips a config equal by value and an input mode equal
        // by identity.
        audioController.reconfigure(audioConfig, inputMode)
        return config.needsReconnectAfter(previous)
    }

    /**
     * The routed device changed, so the pipeline is rebuilt for its stream (and, for SCO, its
     * sample rate). A redundant event is dropped by [AudioController.reconfigure].
     */
    private fun setRoutedDevice(type: Int?) {
        audioConfig = audioConfig.copy(
            routedDeviceType = type,
            echoCancellation = echoCancellationFor(type),
        )
        // Posts to humla-audio-control; never joins on main.
        audioController.reconfigure(audioConfig, inputMode)
        onAudioRouteChanged(type)
    }

    /**
     * The echo canceller for a routed device of [type]: the user's override for its kind of
     * device, else the kind's default. No route, no canceller - nothing plays that could echo.
     */
    private fun echoCancellationFor(type: Int?): Boolean {
        val category = AudioDeviceCategory.of(type ?: return false)
        return sessionConfig.echoCancellationOverrides[category] ?: category.echoCancellationByDefault
    }

    /**
     * The routed device changed: its `AudioDeviceInfo` type, or null when nothing is routed. For
     * subclasses that couple something else to the route - the proximity sensor to the earpiece.
     * Main thread.
     */
    protected open fun onAudioRouteChanged(type: Int?) = Unit

    /** The live connection; [IllegalStateException] when no attempt was ever started. */
    private fun conn(): HumlaConnection = checkNotNull(connection) { "Not connected" }

    /** The synchronized session's model; [IllegalStateException] outside of one. */
    private fun model(): ModelHandler {
        check(isSynchronized) { "Not synchronized with the server" }
        return checkNotNull(modelHandler) { "No model for the synchronized session" }
    }

    override val connectionState: ConnectionState
        get() = when (val state = stateMachine.current) {
            SessionState.Connecting, is SessionState.Reconnecting -> ConnectionState.CONNECTING
            SessionState.Connected -> ConnectionState.CONNECTED
            is SessionState.ConnectionLost -> ConnectionState.CONNECTION_LOST
            is SessionState.Disconnected ->
                if (state.error != null) ConnectionState.CONNECTION_LOST else ConnectionState.DISCONNECTED
        }

    /** The session lifecycle as a flow. */
    override val sessionState: StateFlow<SessionState>
        get() = stateMachine.state

    /**
     * Why the last session ended. Read from the state machine, which carries the error across
     * reconnect attempts that replace the connection object.
     */
    override val connectionError: HumlaException?
        get() = when (val state = stateMachine.current) {
            is SessionState.Disconnected -> state.error
            is SessionState.ConnectionLost -> state.error
            is SessionState.Reconnecting -> state.error
            else -> null
        }

    override val isReconnecting: Boolean
        get() = when (stateMachine.current) {
            is SessionState.ConnectionLost, is SessionState.Reconnecting -> true
            else -> false
        }

    /**
     * Gives up on the automatic reconnect. An attempt in flight is disconnected too, so it cannot
     * reach onConnectionSynchronized for a session the user has just ended.
     */
    override fun cancelReconnect() {
        if (stateMachine.cancelReconnect()) {
            releaseSessionResources()
            connection?.disconnect()
        }
    }

    /** Test seam: whether the wake lock is held. */
    fun isWakeLockHeldForTest(): Boolean = wakeLock.isHeld

    override val targetServer: Server?
        get() = sessionConfig.server

    override val session: IHumlaSession
        get() {
            if (!isConnected) throw HumlaDisconnectedException()
            return this
        }

    override val tcpLatency: Long
        get() = conn().getTCPLatency()

    override val udpLatency: Long
        get() = conn().getUDPLatency()

    override val maxBandwidth: Int
        get() = conn().getMaxBandwidth()

    /**
     * The running pipeline's bandwidth in bps, or -1 while none runs. The pipeline starts and
     * stops asynchronously around the session, so this does not throw while disconnected.
     */
    override val currentBandwidth: Int
        get() = audioController.currentBandwidth

    override val serverVersion: Int
        get() = conn().getServerVersion()

    override val serverRelease: String?
        get() = conn().getServerRelease()

    override val serverOSName: String?
        get() = conn().getServerOSName()

    override val serverOSVersion: String?
        get() = conn().getServerOSVersion()

    override val sessionId: Int
        get() = conn().getSession()

    override val sessionUser: IUser?
        get() = model().getUser(sessionId)

    override val sessionChannel: IChannel?
        get() {
            val user = sessionUser
            if (user != null) return user.channel
            throw IllegalStateException("Session user should be set post-synchronization!")
        }

    override fun getUser(session: Int): IUser? = model().getUser(session)

    override fun getChannel(id: Int): IChannel? = model().getChannel(id)

    override val rootChannel: IChannel?
        get() = getChannel(0)

    override val permissions: Int
        get() = model().permissions

    override val transmitMode: Int
        get() = sessionConfig.transmitMode

    override val codec: HumlaUDPMessageType?
        get() = conn().getCodec()

    /**
     * What the user asked for, independent of the current route. Survives a lost connection, a
     * headset going away and a platform refusal.
     */
    override fun usingBluetoothSco(): Boolean = router.bluetoothAutomatic

    /** What the platform actually routes right now. */
    override val isBluetoothScoActive: Boolean
        get() = router.isBluetoothActive

    override fun enableBluetoothSco() {
        router.bluetoothAutomatic = true
        router.apply()
    }

    override fun disableBluetoothSco() {
        router.bluetoothAutomatic = false
        router.apply()
    }

    override val audioDevices: List<CommunicationDevice>
        get() = router.availableDevices()

    override val isEchoCancellationEnabled: Boolean
        get() = audioConfig.echoCancellation

    override val activeAudioDevice: CommunicationDevice?
        get() = router.activeDevice()

    override fun selectAudioDevice(id: Int) = router.choose(id)

    override fun selectAutomaticAudioDevice() = router.forgetChoice()

    override val isTalking: Boolean
        get() = toggleInputMode.isTalkingOn

    override fun setTalkingState(talking: Boolean) {
        toggleInputMode.setTalkingOn(talking)
    }

    override fun joinChannel(channel: Int) {
        moveUserToChannel(sessionId, channel)
    }

    override fun setLocalVolume(session: Int, volume: Float) {
        val user = modelHandler?.getUser(session) ?: return
        localVolumes?.set(user, volume)
    }

    override fun setListening(channel: Int, listen: Boolean) {
        val usb = Mumble.UserState.newBuilder().setSession(sessionId)
        if (listen) usb.addListeningChannelAdd(channel) else usb.addListeningChannelRemove(channel)
        send(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun moveUserToChannel(session: Int, channel: Int) = send(
        Mumble.UserState.newBuilder().setSession(session).setChannelId(channel).build(),
        HumlaTCPMessageType.UserState,
    )

    override fun createChannel(
        parent: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean
    ) = send(
        Mumble.ChannelState.newBuilder()
            .setParent(parent)
            .setName(name)
            .setDescription(description)
            .setPosition(position)
            .setTemporary(temporary)
            .build(),
        HumlaTCPMessageType.ChannelState,
    )

    override fun sendAccessTokens(tokens: List<String>) {
        conn().sendAccessTokens(tokens)
    }

    override fun requestPermissions(channel: Int) =
        send(Mumble.PermissionQuery.newBuilder().setChannelId(channel).build(), HumlaTCPMessageType.PermissionQuery)

    override fun requestComment(session: Int) =
        send(Mumble.RequestBlob.newBuilder().addSessionComment(session).build(), HumlaTCPMessageType.RequestBlob)

    override fun requestAvatar(session: Int) =
        send(Mumble.RequestBlob.newBuilder().addSessionTexture(session).build(), HumlaTCPMessageType.RequestBlob)

    override fun requestChannelDescription(channel: Int) =
        send(Mumble.RequestBlob.newBuilder().addChannelDescription(channel).build(), HumlaTCPMessageType.RequestBlob)

    override fun requestUserStats(session: Int) = send(
        Mumble.UserStats.newBuilder().setSession(session).setStatsOnly(false).build(),
        HumlaTCPMessageType.UserStats,
    )

    override fun registerUser(session: Int) =
        send(Mumble.UserState.newBuilder().setSession(session).setUserId(0).build(), HumlaTCPMessageType.UserState)

    override fun kickBanUser(session: Int, reason: String?, ban: Boolean) = send(
        Mumble.UserRemove.newBuilder().setSession(session).setReason(reason).setBan(ban).build(),
        HumlaTCPMessageType.UserRemove,
    )

    private fun send(message: MessageLite, type: HumlaTCPMessageType) = conn().sendTCPMessage(message, type)

    override fun sendUserTextMessage(session: Int, message: String): Message {
        val model = model()
        val tmb = Mumble.TextMessage.newBuilder()
        tmb.addSession(session)
        tmb.setMessage(message)
        conn().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

        // A message to an unknown session carries no user.
        val users = listOfNotNull(model.getUser(session))
        return Message(sessionId, selfName(model), emptyList(), emptyList(), users, message)
    }

    override fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message {
        val model = model()
        val tmb = Mumble.TextMessage.newBuilder()
        if (tree) tmb.addTreeId(channel) else tmb.addChannelId(channel)
        tmb.setMessage(message)
        conn().sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

        // An unknown channel is left out, as above.
        val targetChannels = listOfNotNull(model.getChannel(channel))
        return Message(
            sessionId, selfName(model), targetChannels,
            if (tree) targetChannels else emptyList(), emptyList(), message
        )
    }

    private fun selfName(model: ModelHandler): String? =
        checkNotNull(model.getUser(sessionId)) { "No user for our own session" }.name

    override fun setUserComment(session: Int, comment: String?) = send(
        Mumble.UserState.newBuilder().setSession(session).setComment(comment).build(),
        HumlaTCPMessageType.UserState,
    )

    override fun setPrioritySpeaker(session: Int, priority: Boolean) = send(
        Mumble.UserState.newBuilder().setSession(session).setPrioritySpeaker(priority).build(),
        HumlaTCPMessageType.UserState,
    )

    override fun removeChannel(channel: Int) =
        send(Mumble.ChannelRemove.newBuilder().setChannelId(channel).build(), HumlaTCPMessageType.ChannelRemove)

    override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        val usb = Mumble.UserState.newBuilder().setSession(session).setMute(mute).setDeaf(deaf)
        if (!mute) usb.setSuppress(false)
        send(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) =
        send(Mumble.UserState.newBuilder().setSelfMute(mute).setSelfDeaf(deaf).build(), HumlaTCPMessageType.UserState)

    override val isConnected: Boolean
        get() = stateMachine.current == SessionState.Connected

    override fun linkChannels(channelA: IChannel, channelB: IChannel) = send(
        Mumble.ChannelState.newBuilder().setChannelId(channelA.id).addLinksAdd(channelB.id).build(),
        HumlaTCPMessageType.ChannelState,
    )

    override fun unlinkChannels(channelA: IChannel, channelB: IChannel) = send(
        Mumble.ChannelState.newBuilder().setChannelId(channelA.id).addLinksRemove(channelB.id).build(),
        HumlaTCPMessageType.ChannelState,
    )

    override fun unlinkAllChannels(channel: IChannel) = send(
        Mumble.ChannelState.newBuilder()
            .setChannelId(channel.id)
            .addAllLinksRemove(channel.links.map { it.id })
            .build(),
        HumlaTCPMessageType.ChannelState,
    )

    override fun registerWhisperTarget(target: WhisperTarget): Byte {
        val id = whisperTargetList.append(target)
        if (id < 0) {
            return -1
        }

        val voiceTarget = target.createTarget()
        val vtb = Mumble.VoiceTarget.newBuilder()
        vtb.setId(id.toInt())
        vtb.addTargets(voiceTarget)
        conn().sendTCPMessage(vtb.build(), HumlaTCPMessageType.VoiceTarget)
        return id
    }

    override fun unregisterWhisperTarget(targetId: Byte) {
        whisperTargetList.free(targetId)
    }

    /** Test seam: the settings the next pipeline would be built with (public for the app's tests). */
    fun getAudioConfigForTest(): AudioConfig = audioConfig

    override var voiceTargetId: Byte
        get() = _voiceTargetId
        set(targetId) {
            // `!= 0` rather than `> 0`: a negative byte masks to a negative value.
            require((targetId.toInt() and 0x1F.inv()) == 0) { "Target ID must be at most 5 bits." }
            _voiceTargetId = targetId
            // Also reaches the running pipeline, so the next rebuild keeps targeting it.
            audioController.setVoiceTargetId(targetId)
            emit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.fromId(targetId)))
        }

    override val voiceTargetMode: VoiceTargetMode
        get() = VoiceTargetMode.fromId(_voiceTargetId)

    override val whisperTarget: WhisperTarget?
        get() {
            if (VoiceTargetMode.fromId(_voiceTargetId) == VoiceTargetMode.WHISPER) {
                return whisperTargetList.get(_voiceTargetId)
            }
            return null
        }

    override val serverSettings: ServerSettings?
        get() = model().serverSettings

    /** A coarse view of [sessionState] for clients that only tell these four apart. */
    enum class ConnectionState {
        /**
         * The default state of Humla, before connection to a server and after graceful/expected
         * disconnection from a server.
         */
        DISCONNECTED,

        /** A connection to the server is currently in progress. */
        CONNECTING,

        /** Humla has received all data necessary for normal protocol communication with the server. */
        CONNECTED,

        /**
         * The connection was lost due to either a kick/ban or socket I/O error.
         * Humla may be reconnecting in this state.
         * @see isReconnecting
         * @see cancelReconnect
         */
        CONNECTION_LOST
    }

    class HumlaBinder internal constructor(val service: IHumlaService) : Binder()

    companion object {
        private val TAG: String = HumlaService::class.java.name

        /** Events a collector may fall behind before the oldest are dropped. */
        const val EVENT_BUFFER = 8_192
    }
}
