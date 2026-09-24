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
import android.content.Context
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
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.model.Channel
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
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.session.AndroidCommunicationDevices
import se.lublin.humla.session.AudioConfig
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.humla.session.AudioRouter
import se.lublin.humla.session.AudioController
import se.lublin.humla.session.AudioHandlerFactory
import se.lublin.humla.session.AudioSessionParams
import se.lublin.humla.session.CommunicationDevice
import se.lublin.humla.session.CommunicationDevices
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.DefaultAudioHandlerFactory
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.session.SessionStateMachine
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
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
    private var mConfig = SessionConfig()

    /** Current audio settings: [mConfig]'s audio half plus what the route decides. */
    private var mAudioConfig = AudioConfig()

    /** Held by identity: the audio thread and `isTalking` must see the same toggle object. */
    @VisibleForTesting
    internal lateinit var mInputMode: IInputMode
        private set

    private var mVoiceTargetId: Byte = 0
    private lateinit var mWhisperTargetList: WhisperTargetList

    private lateinit var mWakeLock: PowerManager.WakeLock
    private lateinit var mHandler: Handler

    /**
     * Emitted from any thread without suspending. Beyond [EVENT_BUFFER] events not yet collected
     * by the slowest collector, the oldest are dropped.
     */
    @VisibleForTesting
    internal val mEvents = MutableSharedFlow<HumlaEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Written on the main thread, read on the protocol thread (emit() checks it).
    @Volatile
    @VisibleForTesting
    internal var mConnection: HumlaConnection? = null

    @Volatile
    @VisibleForTesting
    internal var mModelHandler: ModelHandler? = null
    private var mLocalVolumes: LocalVolumes? = null
    /** Owns the audio pipeline's lifecycle on its own thread, so nothing here joins on main. */
    @VisibleForTesting
    internal lateinit var mAudioController: AudioController
        private set

    /** Engaged only while a session is synchronized. */
    @VisibleForTesting
    internal lateinit var mRouter: AudioRouter
        private set

    /** Last warning logged, so a refusal repeated per reconnect attempt is logged once. */
    @Volatile
    private var mLastWarning: String? = null

    @VisibleForTesting
    internal lateinit var mActivityInputMode: ActivityInputMode
        private set
    private lateinit var mToggleInputMode: ToggleInputMode
    private lateinit var mContinuousInputMode: ContinuousInputMode

    /**
     * The session lifecycle. Confined to the main thread, where binder calls, connection callbacks,
     * the reconnect timer and the network callback all run; other threads collect [sessionState].
     */
    @VisibleForTesting
    internal lateinit var mStateMachine: SessionStateMachine
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
    private val mNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Registered with mHandler, so this runs on the main thread like every other mutator.
            unregisterNetworkCallback()
            if (mStateMachine.current !is SessionState.ConnectionLost) return
            Log.v(TAG, "Connectivity restored, attempting reconnect.")
            if (mStateMachine.connectivityRestored()) mHandler.post(mReconnectRunnable)
        }
    }
    private var mNetworkCallbackRegistered = false

    /**
     * The backoff timer. Pending posts are never cancelled: the state machine refuses a retry in
     * every state but ConnectionLost. A stale post can therefore retry a later loss early, once.
     */
    private val mReconnectRunnable = Runnable {
        if (mStateMachine.reconnectTimerFired()) startSession()
    }

    private val mRouterListener = object : AudioRouter.Listener {
        override fun onRouteChanged(type: Int?) = setRoutedDevice(type)

        override fun onRouteRefused() = logWarningOnce(getString(R.string.audio_route_refused))
    }

    private val mAudioControllerListener = object : AudioController.Listener {
        override fun onAudioStarted() = Unit

        /** A pipeline that cannot start becomes a chat-log warning. */
        override fun onAudioFailed(message: String) = logWarning(message)

        /** Microphone silencing and decoder errors reach the chat log. */
        override fun onAudioWarning(message: String) = logWarning(message)
    }

    private val mAudioInputListener: AudioHandler.AudioEncodeListener =
        object : AudioHandler.AudioEncodeListener {
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                val connection = mConnection
                if (connection != null && connection.isSynchronized) {
                    connection.sendUDPMessage(data, length, false)
                }
            }

            override fun onTalkingStateChanged(talking: Boolean) {
                mHandler.post {
                    // A leftover from a terminated connection when the session is inactive.
                    if (!isSynchronized()) return@post
                    val modelHandler = mModelHandler ?: return@post
                    val connection = mConnection ?: return@post
                    val currentUser = modelHandler.getUser(connection.getSession()) ?: return@post

                    currentUser.talkState = if (talking) TalkState.TALKING else TalkState.PASSIVE
                    emit(HumlaEvent.UserTalkStateUpdated(currentUser))
                }
            }
        }

    private val mAudioOutputListener: AudioOutput.AudioOutputListener =
        object : AudioOutput.AudioOutputListener {
            override fun onUserTalkStateUpdated(user: User) {
                emit(HumlaEvent.UserTalkStateUpdated(user))
            }

            override fun getUser(session: Int): User? = mModelHandler?.getUser(session)
        }

    /** Only keeps the service started; connecting goes through [configure] and [connect]. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService")
        mHandler = Handler(mainLooper)
        mStateMachine = SessionStateMachine(reconnectPolicy)
        // One instance per service life, so a platform refusal is reported once rather than on
        // every route decision.
        val devices = communicationDevices ?: AndroidCommunicationDevices(
            getSystemService(AUDIO_SERVICE) as AudioManager,
            mHandler,
        ) { logWarningOnce(getString(R.string.bluetooth_sco_denied)) }
        communicationDevices = devices
        mRouter = AudioRouter(devices, mRouterListener)
        mToggleInputMode = ToggleInputMode()
        mActivityInputMode = ActivityInputMode(VoiceActivityDetector(mConfig.vadConfig))
        mContinuousInputMode = ContinuousInputMode()
        mInputMode = mActivityInputMode
        mWhisperTargetList = WhisperTargetList()
        // Eagerly, and for the life of the service: one controller, one thread, quit in onDestroy.
        // `{ audioFactory }` and not `audioFactory`, so a factory set after onCreate still takes.
        mAudioController = AudioController(
            this, this, { audioFactory }, mAudioInputListener, mAudioOutputListener,
            mAudioControllerListener, mHandler,
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
        mRouter.disengage()
        mRouter.release()
        // Posts the teardown and quits the looper without waiting for either.
        mAudioController.quit()
    }

    override fun onBind(intent: Intent?): IBinder = HumlaBinder(this)

    /**
     * User-initiated connect. Ignored by the state machine while an attempt is in flight or a
     * session is up.
     */
    override fun connect() {
        if (!mStateMachine.connectRequested()) return
        startSession()
    }

    /** Builds and starts one connection attempt; called from [connect] and [mReconnectRunnable]. */
    private fun startSession() {
        // Whisper slots are cleared when a session ends. The voice target can be set while
        // disconnected, so it is reset here.
        mVoiceTargetId = 0

        // Checked before anything is built, so a misconfigured start allocates nothing and is
        // reported as a failed attempt.
        val config = mConfig
        val server = config.server
        if (server == null) {
            Log.e(TAG, "connect() without a target server")
            mStateMachine.disconnectRequested()
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
        mConnection = connection
        connection.setForceTCP(config.forceTcp)
        connection.setUseTor(config.useTor)
        connection.setKeys(config.certificate?.pkcs12, config.certificate?.password)
        connection.setTrustStore(config.trustStorePath, config.trustStorePassword, config.trustStoreFormat)

        val localVolumes = LocalVolumes(server, config.localVolumes)
        mLocalVolumes = localVolumes
        val modelHandler =
            ModelHandler(::emit, config.localMuteHistory, config.localIgnoreHistory, localVolumes)
        mModelHandler = modelHandler
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
            mStateMachine.disconnectRequested()
            emit(HumlaEvent.Disconnected(HumlaException(e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)))
        }
    }

    /**
     * Ends the session for good. While a reconnect waits out its backoff there is no live
     * connection to report the end, so the wake lock and network callback are released here.
     */
    override fun disconnect() {
        val waiting = mStateMachine.current is SessionState.ConnectionLost
        mStateMachine.disconnectRequested()
        if (waiting) releaseSessionResources()
        mConnection?.disconnect()
    }

    fun isConnectionEstablished(): Boolean = mConnection?.isConnected == true

    /**
     * @return true if Humla has received the ServerSync message, indicating synchronization with
     * the server's model and settings. This is the main state of the service.
     */
    fun isSynchronized(): Boolean = mConnection?.isSynchronized == true

    override fun onConnectionEstablished() {
        val version = MumbleVersion.clientVersion(mConfig.clientName, "Android", Build.VERSION.RELEASE)

        val auth = Mumble.Authenticate.newBuilder()
        val server = checkNotNull(mConfig.server) { "Connected without a target server" }
        auth.setUsername(server.username)
        auth.setPassword(server.password)
        auth.setOpus(true)
        auth.addAllTokens(mConfig.accessTokens)

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
        val modelHandler = mModelHandler
        if (modelHandler == null) {
            Log.e(TAG, "onConnectionSynchronized: model handler is null")
            return
        }

        mStateMachine.synchronized()

        Log.v(TAG, "Connected")
        // The lock is reference counted and taken once per session, but released only when the
        // session ends for good.
        if (!mWakeLock.isHeld) mWakeLock.acquire()

        // Restore the route the user asked for; onConnectionDisconnected drops it.
        mRouter.engage()

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
            targetId = mVoiceTargetId,
            inputMode = mInputMode,
        )
        mAudioController.start(mAudioConfig, params, connection)
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
        mToggleInputMode.setTalkingOn(false)

        if (e != null) {
            Log.e(TAG, "Error: " + e.message + " (reason: " + e.reason.name + ")")
        } else {
            Log.v(TAG, "Disconnected")
        }

        val autoReconnect = mConfig.autoReconnect && e != null &&
            e.reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
        val next = mStateMachine.lost(autoReconnect, e)

        // The route is a session resource and the wish is not, so this runs on every disconnect,
        // auto-reconnect included; onConnectionSynchronized is where it comes back.
        mRouter.disengage()
        // Asynchronous: the audio threads are joined on humla-audio-control, never on main.
        mAudioController.shutdown()

        // Readers throw once disconnected; this only lets the channel tree and users be collected.
        mModelHandler = null
        mVoiceTargetId = 0
        mWhisperTargetList.clear()

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
        mLastWarning = message
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, message))
    }

    /**
     * Delivers [message] unless it repeats the last warning. Compared with the last line rather
     * than per type, so the log never ends on a line that contradicts the current state.
     */
    protected fun logWarningOnce(message: String) {
        if (message == mLastWarning) return
        logWarning(message)
    }

    override fun logError(message: String) {
        emit(HumlaEvent.LogMessage(HumlaEvent.Level.ERROR, message))
    }

    /** Publishes [event]; info notices only once synchronized. Any thread. */
    @VisibleForTesting
    internal fun emit(event: HumlaEvent) {
        if (event is HumlaEvent.Notice && event.level == HumlaEvent.Level.INFO && !isSynchronized()) return
        mEvents.tryEmit(event)
    }

    override val events: SharedFlow<HumlaEvent>
        get() = mEvents.asSharedFlow()

    private fun scheduleReconnect(delayMillis: Long) {
        if (isOnline()) {
            Log.v(TAG, "Reconnecting in $delayMillis ms")
            mHandler.postDelayed(mReconnectRunnable, delayMillis)
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
        mRouter.forgetChoice()
        if (mWakeLock.isHeld) mWakeLock.release()
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
        if (mNetworkCallbackRegistered) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(mNetworkCallback, mHandler)
            mNetworkCallbackRegistered = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Error registering the network callback: " + e.message)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!mNetworkCallbackRegistered) return
        mNetworkCallbackRegistered = false
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(mNetworkCallback)
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
            Constants.TRANSMIT_PUSH_TO_TALK -> mToggleInputMode
            Constants.TRANSMIT_CONTINUOUS -> mContinuousInputMode
            Constants.TRANSMIT_VOICE_ACTIVITY -> mActivityInputMode
            else -> throw IllegalArgumentException("Unknown transmit mode ${config.transmitMode}")
        }
        val previous = mConfig
        mConfig = config
        mInputMode = inputMode
        // Applied live: the input mode outlives pipeline rebuilds.
        mActivityInputMode.setVadConfig(config.vadConfig)

        if (config.accessTokens != previous.accessTokens) {
            mConnection?.takeIf { it.isConnected }?.sendAccessTokens(config.accessTokens)
        }
        if (config.earpieceByDefault != previous.earpieceByDefault) {
            // Live: the next apply routes it, and a user's explicit choice is left standing.
            mRouter.earpieceByDefault = config.earpieceByDefault
            mRouter.apply()
        }

        mAudioConfig = mAudioConfig.copy(
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
            echoCancellation = echoCancellationFor(mAudioConfig.routedDeviceType),
            speexNoiseSuppressDb = config.speexNoiseSuppressDb,
            androidNoiseSuppressor = config.androidNoiseSuppressor,
            androidAgc = config.androidAgc,
        )
        // Unconditional: AudioController skips a config equal by value and an input mode equal
        // by identity.
        mAudioController.reconfigure(mAudioConfig, mInputMode)
        return config.needsReconnectAfter(previous)
    }

    override val sessionConfig: SessionConfig
        get() = mConfig

    /**
     * The routed device changed, so the pipeline is rebuilt for its stream (and, for SCO, its
     * sample rate). A redundant event is dropped by [AudioController.reconfigure].
     */
    private fun setRoutedDevice(type: Int?) {
        mAudioConfig = mAudioConfig.copy(
            routedDeviceType = type,
            echoCancellation = echoCancellationFor(type),
        )
        // Posts to humla-audio-control; never joins on main.
        mAudioController.reconfigure(mAudioConfig, mInputMode)
        onAudioRouteChanged(type)
    }

    /**
     * The echo canceller for a routed device of [type]: the user's override for its kind of
     * device, else the kind's default. No route, no canceller - nothing plays that could echo.
     */
    private fun echoCancellationFor(type: Int?): Boolean {
        val category = AudioDeviceCategory.of(type ?: return false)
        return mConfig.echoCancellationOverrides[category] ?: category.echoCancellationByDefault
    }

    /**
     * The routed device changed: its `AudioDeviceInfo` type, or null when nothing is routed. For
     * subclasses that couple something else to the route - the proximity sensor to the earpiece.
     * Main thread.
     */
    protected open fun onAudioRouteChanged(type: Int?) = Unit

    /**
     * The connection of the latest attempt. Set when an attempt starts and kept after it ends, so
     * the terminated connection can still be inspected.
     */
    fun getConnection(): HumlaConnection? = mConnection

    /** The live connection; [IllegalStateException] when no attempt was ever started. */
    private fun conn(): HumlaConnection = checkNotNull(mConnection) { "Not connected" }

    /** The synchronized session's model; [IllegalStateException] outside of one. */
    private fun model(): ModelHandler {
        check(isSynchronized()) { "Not synchronized with the server" }
        return checkNotNull(mModelHandler) { "No model for the synchronized session" }
    }

    override val connectionState: ConnectionState
        get() = when (val state = mStateMachine.current) {
            SessionState.Connecting, is SessionState.Reconnecting -> ConnectionState.CONNECTING
            SessionState.Connected -> ConnectionState.CONNECTED
            is SessionState.ConnectionLost -> ConnectionState.CONNECTION_LOST
            is SessionState.Disconnected ->
                if (state.error != null) ConnectionState.CONNECTION_LOST else ConnectionState.DISCONNECTED
        }

    /** The session lifecycle as a flow. */
    override val sessionState: StateFlow<SessionState>
        get() = mStateMachine.state

    /**
     * Why the last session ended. Read from the state machine, which carries the error across
     * reconnect attempts that replace the connection object.
     */
    override val connectionError: HumlaException?
        get() = when (val state = mStateMachine.current) {
            is SessionState.Disconnected -> state.error
            is SessionState.ConnectionLost -> state.error
            is SessionState.Reconnecting -> state.error
            else -> null
        }

    override val isReconnecting: Boolean
        get() = when (mStateMachine.current) {
            is SessionState.ConnectionLost, is SessionState.Reconnecting -> true
            else -> false
        }

    /**
     * Gives up on the automatic reconnect. An attempt in flight is disconnected too, so it cannot
     * reach onConnectionSynchronized for a session the user has just ended.
     */
    override fun cancelReconnect() {
        if (mStateMachine.cancelReconnect()) {
            releaseSessionResources()
            mConnection?.disconnect()
        }
    }

    /** Test seam: whether the wake lock is held. */
    fun isWakeLockHeldForTest(): Boolean = mWakeLock.isHeld

    override val targetServer: Server?
        get() = mConfig.server

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
        get() = mAudioController.currentBandwidth

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
        get() = mConfig.transmitMode

    override val codec: HumlaUDPMessageType?
        get() = conn().getCodec()

    /**
     * What the user asked for, independent of the current route. Survives a lost connection, a
     * headset going away and a platform refusal.
     */
    override fun usingBluetoothSco(): Boolean = mRouter.bluetoothAutomatic

    /** What the platform actually routes right now. */
    override val isBluetoothScoActive: Boolean
        get() = mRouter.isBluetoothActive

    override fun enableBluetoothSco() {
        mRouter.bluetoothAutomatic = true
        mRouter.apply()
    }

    override fun disableBluetoothSco() {
        mRouter.bluetoothAutomatic = false
        mRouter.apply()
    }

    override val audioDevices: List<CommunicationDevice>
        get() = mRouter.availableDevices()

    override val isEchoCancellationEnabled: Boolean
        get() = mAudioConfig.echoCancellation

    override val activeAudioDevice: CommunicationDevice?
        get() = mRouter.activeDevice()

    override fun selectAudioDevice(id: Int) = mRouter.choose(id)

    override val isTalking: Boolean
        get() = mToggleInputMode.isTalkingOn()

    override fun setTalkingState(talking: Boolean) {
        mToggleInputMode.setTalkingOn(talking)
    }

    override fun joinChannel(channel: Int) {
        moveUserToChannel(sessionId, channel)
    }

    override fun setLocalVolume(session: Int, volume: Float) {
        val user = mModelHandler?.getUser(session) ?: return
        mLocalVolumes?.set(user, volume)
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
        get() = mStateMachine.current == SessionState.Connected

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
        val id = mWhisperTargetList.append(target)
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
        mWhisperTargetList.free(targetId)
    }

    /** Test seam: the settings the next pipeline would be built with (public for the app's tests). */
    fun getAudioConfigForTest(): AudioConfig = mAudioConfig

    override var voiceTargetId: Byte
        get() = mVoiceTargetId
        set(targetId) {
            // `!= 0` rather than `> 0`: a negative byte masks to a negative value.
            require((targetId.toInt() and 0x1F.inv()) == 0) { "Target ID must be at most 5 bits." }
            mVoiceTargetId = targetId
            // Also reaches the running pipeline, so the next rebuild keeps targeting it.
            mAudioController.setVoiceTargetId(targetId)
            emit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.fromId(targetId)))
        }

    override val voiceTargetMode: VoiceTargetMode
        get() = VoiceTargetMode.fromId(mVoiceTargetId)

    override val whisperTarget: WhisperTarget?
        get() {
            if (VoiceTargetMode.fromId(mVoiceTargetId) == VoiceTargetMode.WHISPER) {
                return mWhisperTargetList.get(mVoiceTargetId)
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

    class HumlaBinder internal constructor(private val mService: IHumlaService) : Binder() {
        fun getService(): IHumlaService = mService
    }

    companion object {
        private val TAG: String = HumlaService::class.java.name

        /** Events a collector may fall behind before the oldest are dropped. */
        const val EVENT_BUFFER = 8_192
    }
}
