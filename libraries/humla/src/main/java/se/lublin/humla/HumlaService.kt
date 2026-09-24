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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.StateFlow
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.capture.VadConfigBundle
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.exception.NotConnectedException
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.Channel
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
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
import se.lublin.humla.session.DefaultAudioHandlerFactory
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.session.SessionState
import se.lublin.humla.session.SessionStateMachine
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate

/**
 * Owns one server session (connection, model, audio pipeline and audio routing) and exposes it
 * through [IHumlaService] and [IHumlaSession]. Accessors stay functions to match the Java interfaces.
 */
open class HumlaService : Service(), IHumlaService, IHumlaSession,
    HumlaConnection.HumlaConnectionListener, HumlaLogger {

    // Service settings
    private var mServer: Server? = null
    private var mAutoReconnect = false
    private var mCertificate: ByteArray? = null
    private var mCertificatePassword: String? = null
    private var mForceTcpSetting = false

    /** Voice goes over TCP when the user forces it or when Tor is on, which cannot carry UDP. */
    private val mForceTcp: Boolean get() = mForceTcpSetting || mUseTor

    @get:VisibleForTesting
    internal val isTcpForced: Boolean get() = mForceTcp
    private var mUseTor = false
    private var mClientName: String? = null
    @VisibleForTesting
    internal var mAccessTokens: List<String>? = null
        private set
    private var mTrustStore: String? = null
    private var mTrustStorePassword: String? = null
    private var mTrustStoreFormat: String? = null
    private var mLocalMuteHistory: List<Int>? = null
    private var mLocalIgnoreHistory: List<Int>? = null
    private var mTransmitMode = 0

    /** Current audio settings; rebuilt wholesale by [configureExtras]. */
    private var mAudioConfig = AudioConfig()

    /** The user's echo-cancellation choices per kind of device; see EXTRAS_ECHO_CANCELLATION_BY_DEVICE. */
    @VisibleForTesting
    internal var mEchoOverrides: Map<AudioDeviceCategory, Boolean> = emptyMap()
        private set

    /** Held by identity: the audio thread and `isTalking()` must see the same toggle object. */
    @VisibleForTesting
    internal lateinit var mInputMode: IInputMode
        private set

    private var mVoiceTargetId: Byte = 0
    private lateinit var mWhisperTargetList: WhisperTargetList

    private lateinit var mWakeLock: PowerManager.WakeLock
    private lateinit var mHandler: Handler
    @VisibleForTesting
    internal lateinit var mCallbacks: HumlaCallbacks
        private set

    // Written on the main thread, read on the protocol thread (ModelHandler logs through this).
    @Volatile
    @VisibleForTesting
    internal var mConnection: HumlaConnection? = null
    @VisibleForTesting
    internal var mConnectionState: ConnectionState = ConnectionState.DISCONNECTED

    @Volatile
    @VisibleForTesting
    internal var mModelHandler: ModelHandler? = null
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
     * the reconnect timer and the network callback all run; other threads collect [getSessionState].
     */
    private lateinit var mStateMachine: SessionStateMachine

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
                    try {
                        // If the server session is inactive, ignore this message.
                        // It's likely that this is leftover from a terminated connection.
                        if (!isSynchronized()) return@post

                        val modelHandler = mModelHandler
                        val connection = mConnection
                        if (modelHandler == null || connection == null) return@post

                        val currentUser = modelHandler.getUser(connection.getSession())
                            ?: return@post

                        currentUser.setTalkState(
                            if (talking) TalkState.TALKING else TalkState.PASSIVE
                        )
                        mCallbacks.onUserTalkStateUpdated(currentUser)
                    } catch (e: NotSynchronizedException) {
                        e.printStackTrace()
                    }
                }
            }
        }

    private val mAudioOutputListener: AudioOutput.AudioOutputListener =
        object : AudioOutput.AudioOutputListener {
            override fun onUserTalkStateUpdated(user: User) {
                mCallbacks.onUserTalkStateUpdated(user)
            }

            override fun getUser(session: Int): User? = mModelHandler?.getUser(session)
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val extras = intent.extras
            if (extras != null) {
                configureExtras(extras)
            }

            if (ACTION_CONNECT == intent.action) {
                if (extras == null || !extras.containsKey(EXTRAS_SERVER)) {
                    throw RuntimeException("$ACTION_CONNECT requires a server provided in extras.")
                }
                connect()
            }
        }

        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Humla:HumlaService")
        mHandler = Handler(mainLooper)
        mCallbacks = HumlaCallbacks()
        mStateMachine = SessionStateMachine(reconnectPolicy)
        mConnectionState = ConnectionState.DISCONNECTED
        // One instance per service life, so a platform refusal is reported once rather than on
        // every route decision.
        val devices = communicationDevices ?: AndroidCommunicationDevices(
            getSystemService(AUDIO_SERVICE) as AudioManager,
            mHandler,
        ) { logWarningOnce(getString(R.string.bluetooth_sco_denied)) }
        communicationDevices = devices
        mRouter = AudioRouter(devices, mRouterListener)
        mToggleInputMode = ToggleInputMode()
        mActivityInputMode = ActivityInputMode(0f) // FIXME: reasonable default
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
    open fun connect() {
        if (!mStateMachine.connectRequested()) return
        startSession()
    }

    /** Builds and starts one connection attempt; called from [connect] and [mReconnectRunnable]. */
    private fun startSession() {
        mConnectionState = ConnectionState.CONNECTING
        // Whisper slots are cleared when a session ends. The voice target can be set while
        // disconnected, so it is reset here.
        mVoiceTargetId = 0

        // Checked before anything is built, so a misconfigured start allocates nothing and is
        // reported as a failed attempt.
        val server = mServer
        if (server == null) {
            Log.e(TAG, "connect() without a target server")
            mStateMachine.disconnectRequested()
            mConnectionState = ConnectionState.DISCONNECTED
            mCallbacks.onDisconnected(
                HumlaException(
                    getString(R.string.no_target_server),
                    HumlaException.HumlaDisconnectReason.OTHER_ERROR,
                )
            )
            return
        }

        val connection = connectionFactory(this)
        mConnection = connection
        connection.setForceTCP(mForceTcp)
        connection.setUseTor(mUseTor)
        connection.setKeys(mCertificate, mCertificatePassword)
        connection.setTrustStore(mTrustStore, mTrustStorePassword, mTrustStoreFormat)

        val modelHandler =
            ModelHandler(this, mCallbacks, this, mLocalMuteHistory, mLocalIgnoreHistory)
        mModelHandler = modelHandler
        connection.addTCPMessageHandlers(modelHandler)

        mCallbacks.onConnecting()

        try {
            // Resolves the host (SRV lookup included) and opens the socket on the protocol thread;
            // every failure, certificate errors included, arrives at onConnectionDisconnected.
            connection.connect(server)
        } catch (e: IllegalStateException) {
            // onConnecting() above is delivered inline, so an observer may already have
            // disconnected this single-use connection and connect() refuses. Report a failed
            // attempt instead of throwing out of onStartCommand or the reconnect runnable.
            Log.w(TAG, "Connection was cancelled before it could start", e)
            mConnectionState = ConnectionState.DISCONNECTED
            mCallbacks.onDisconnected(
                HumlaException(e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
            )
        }
    }

    /**
     * Ends the session for good. While a reconnect waits out its backoff there is no live
     * connection to report the end, so the wake lock and network callback are released here.
     */
    override fun disconnect() {
        val waiting = mStateMachine.current is SessionState.ConnectionLost
        mStateMachine.disconnectRequested()
        if (waiting) {
            mConnectionState = ConnectionState.DISCONNECTED
            releaseSessionResources()
        }
        mConnection?.disconnect()
    }

    fun isConnectionEstablished(): Boolean = mConnection?.isConnected == true

    /**
     * @return true if Humla has received the ServerSync message, indicating synchronization with
     * the server's model and settings. This is the main state of the service.
     */
    fun isSynchronized(): Boolean = mConnection?.isSynchronized == true

    override fun onConnectionEstablished() {
        val version = Mumble.Version.newBuilder()
        version.setRelease(mClientName)
        version.setVersion(Constants.PROTOCOL_VERSION)
        version.setOs("Android")
        version.setOsVersion(Build.VERSION.RELEASE)

        val auth = Mumble.Authenticate.newBuilder()
        auth.setUsername(mServer!!.username)
        auth.setPassword(mServer!!.password)
        auth.setOpus(true)
        auth.addAllTokens(mAccessTokens)

        val connection = mConnection!!
        connection.sendTCPMessage(version.build(), HumlaTCPMessageType.Version)
        connection.sendTCPMessage(auth.build(), HumlaTCPMessageType.Authenticate)
    }

    override fun onConnectionSynchronized() {
        val connection = mConnection!!
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
        mConnectionState = ConnectionState.CONNECTED

        Log.v(TAG, "Connected")
        // The lock is reference counted and taken once per session, but released only when the
        // session ends for good.
        if (!mWakeLock.isHeld) mWakeLock.acquire()

        // Restore the route the user asked for; onConnectionDisconnected drops it.
        mRouter.engage()

        startAudio(connection, modelHandler)

        mCallbacks.onConnected()
    }

    /**
     * Hands the session's inputs to the audio controller, which builds the pipeline on its own
     * thread. A pipeline that cannot start arrives as [AudioController.Listener.onAudioFailed].
     */
    private fun startAudio(connection: HumlaConnection, modelHandler: ModelHandler) {
        val params = try {
            val self = modelHandler.getUser(connection.getSession())
            if (self == null) {
                // ServerSync named no known user: keep the session up without a microphone.
                Log.e(TAG, "No session user after ServerSync; audio not started")
                logWarning(getString(R.string.no_session_user))
                return
            }
            AudioSessionParams(
                self = self,
                maxBandwidth = connection.getMaxBandwidth(),
                codec = connection.getCodec(),
                targetId = mVoiceTargetId,
                inputMode = mInputMode,
            )
        } catch (e: NotSynchronizedException) {
            throw RuntimeException(
                "Connection should be synchronized in callback for synchronization!", e
            )
        }
        mAudioController.start(mAudioConfig, params, connection)
    }

    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) {
        mCallbacks.onTLSHandshakeFailed(chain)
    }

    override fun onConnectionCertificateChanged(chain: Array<X509Certificate>) {
        mCallbacks.onTLSCertificateChanged(chain)
    }

    override fun onConnectionDisconnected(e: HumlaException?) {
        // Clear push-to-talk first: the toggle outlives the connection, so an auto-reconnect would
        // otherwise transmit without a key press. This also releases the input thread waiting in
        // waitForInput(). An observer can't do it: isConnected() is already false by then.
        mToggleInputMode.setTalkingOn(false)

        if (e != null) {
            Log.e(TAG, "Error: " + e.message + " (reason: " + e.reason.name + ")")
        } else {
            Log.v(TAG, "Disconnected")
        }

        val autoReconnect = mAutoReconnect && e != null &&
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
            mConnectionState = ConnectionState.CONNECTION_LOST
            scheduleReconnect(next.reconnectInMillis)
        } else {
            // Disconnected. The state's error counts as well as `e`: a late, error-free report
            // after cancelReconnect must not turn CONNECTION_LOST into DISCONNECTED or claim to
            // have given up.
            val ended = next as SessionState.Disconnected
            mConnectionState = if (e != null || ended.error != null) {
                ConnectionState.CONNECTION_LOST
            } else {
                ConnectionState.DISCONNECTED
            }
            if (autoReconnect && ended.error === e) logWarning(getString(R.string.reconnect_gave_up))
            releaseSessionResources()
        }

        mCallbacks.onDisconnected(e)
    }

    override fun onConnectionWarning(warning: ConnectionWarning) {
        logWarning(getString(warning.messageRes))
    }

    override fun logInfo(message: String?) {
        val connection = mConnection
        if (connection == null || !connection.isSynchronized) {
            return // don't log info prior to synchronization
        }
        mCallbacks.onLogInfo(message)
    }

    override fun logWarning(message: String?) {
        mLastWarning = message
        mCallbacks.onLogWarning(message)
    }

    /**
     * Delivers [message] unless it repeats the last warning. Compared with the last line rather
     * than per type, so the log never ends on a line that contradicts the current state.
     */
    protected fun logWarningOnce(message: String) {
        if (message == mLastWarning) return
        logWarning(message)
    }

    override fun logError(message: String?) {
        mCallbacks.onLogError(message)
    }

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
     * Loads all defined settings from the given bundle into the HumlaService.
     * Some settings may only take effect after a reconnect.
     * @param extras A bundle with settings.
     * @return true if a reconnect is required for changes to take effect.
     */
    fun configureExtras(extras: Bundle): Boolean {
        var reconnectNeeded = false
        var config = mAudioConfig
        if (extras.containsKey(EXTRAS_SERVER)) {
            @Suppress("DEPRECATION")
            mServer = extras.getParcelable(EXTRAS_SERVER)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT)) {
            mAutoReconnect = extras.getBoolean(EXTRAS_AUTO_RECONNECT)
        }
        if (extras.containsKey(EXTRAS_CERTIFICATE)) {
            mCertificate = extras.getByteArray(EXTRAS_CERTIFICATE)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_CERTIFICATE_PASSWORD)) {
            mCertificatePassword = extras.getString(EXTRAS_CERTIFICATE_PASSWORD)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_DETECTION_THRESHOLD)) {
            mActivityInputMode.setThreshold(extras.getFloat(EXTRAS_DETECTION_THRESHOLD))
        }
        if (extras.containsKey(EXTRAS_AMPLITUDE_BOOST)) {
            config = config.copy(amplitudeBoost = extras.getFloat(EXTRAS_AMPLITUDE_BOOST))
        }
        if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
            mTransmitMode = extras.getInt(EXTRAS_TRANSMIT_MODE)
            mInputMode = when (mTransmitMode) {
                Constants.TRANSMIT_PUSH_TO_TALK -> mToggleInputMode
                Constants.TRANSMIT_CONTINUOUS -> mContinuousInputMode
                Constants.TRANSMIT_VOICE_ACTIVITY -> mActivityInputMode
                else -> throw IllegalArgumentException()
            }
            // Into the config as well, because AudioConfig.halfDuplex is derived from it.
            config = config.copy(transmitMode = mTransmitMode)
        }
        if (extras.containsKey(EXTRAS_INPUT_RATE)) {
            config = config.copy(inputSampleRate = extras.getInt(EXTRAS_INPUT_RATE))
        }
        if (extras.containsKey(EXTRAS_INPUT_QUALITY)) {
            config = config.copy(targetBitrate = extras.getInt(EXTRAS_INPUT_QUALITY))
        }
        if (extras.containsKey(EXTRAS_USE_TOR)) {
            mUseTor = extras.getBoolean(EXTRAS_USE_TOR)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_FORCE_TCP)) {
            mForceTcpSetting = extras.getBoolean(EXTRAS_FORCE_TCP)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_CLIENT_NAME)) {
            mClientName = extras.getString(EXTRAS_CLIENT_NAME)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_ACCESS_TOKENS)) {
            val tokens = extras.getStringArrayList(EXTRAS_ACCESS_TOKENS)
            mAccessTokens = tokens
            val connection = mConnection
            if (connection != null && connection.isConnected) {
                connection.sendAccessTokens(tokens!!)
            }
        }
        if (extras.containsKey(EXTRAS_AUDIO_SOURCE)) {
            config = config.copy(audioSource = extras.getInt(EXTRAS_AUDIO_SOURCE))
        }
        if (extras.containsKey(EXTRAS_AUDIO_STREAM)) {
            config = config.copy(audioStream = extras.getInt(EXTRAS_AUDIO_STREAM))
        }
        if (extras.containsKey(EXTRAS_FRAMES_PER_PACKET)) {
            config = config.copy(targetFramesPerPacket = extras.getInt(EXTRAS_FRAMES_PER_PACKET))
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE)) {
            mTrustStore = extras.getString(EXTRAS_TRUST_STORE)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE_PASSWORD)) {
            mTrustStorePassword = extras.getString(EXTRAS_TRUST_STORE_PASSWORD)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE_FORMAT)) {
            mTrustStoreFormat = extras.getString(EXTRAS_TRUST_STORE_FORMAT)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_HALF_DUPLEX)) {
            // Stored as requested; AudioConfig.halfDuplex applies it against the mode in force.
            config = config.copy(halfDuplexRequested = extras.getBoolean(EXTRAS_HALF_DUPLEX))
        }
        if (extras.containsKey(EXTRAS_LOCAL_MUTE_HISTORY)) {
            mLocalMuteHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_MUTE_HISTORY)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_LOCAL_IGNORE_HISTORY)) {
            mLocalIgnoreHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_IGNORE_HISTORY)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_ENABLE_PREPROCESSOR)) {
            config = config.copy(preprocessorEnabled = extras.getBoolean(EXTRAS_ENABLE_PREPROCESSOR))
        }
        if (extras.containsKey(EXTRAS_NOISE_SUPPRESSION_METHOD)) {
            config = config.copy(
                noiseSuppression = extras.getString(EXTRAS_NOISE_SUPPRESSION_METHOD) ?: "none"
            )
        }
        if (extras.containsKey(EXTRAS_ECHO_CANCELLATION_BY_DEVICE)) {
            val overrides = extras.getBundle(EXTRAS_ECHO_CANCELLATION_BY_DEVICE) ?: Bundle()
            mEchoOverrides = AudioDeviceCategory.entries
                .filter { overrides.containsKey(it.name) }
                .associateWith { overrides.getBoolean(it.name) }
            config = config.copy(echoCancellation = echoCancellationFor(config.routedDeviceType))
        }
        if (extras.containsKey(EXTRAS_SPEEX_NOISE_SUPPRESS_DB)) {
            config = config.copy(speexNoiseSuppressDb = extras.getInt(EXTRAS_SPEEX_NOISE_SUPPRESS_DB))
        }
        if (extras.containsKey(EXTRAS_ANDROID_NOISE_SUPPRESSOR)) {
            config = config.copy(androidNoiseSuppressor = extras.getBoolean(EXTRAS_ANDROID_NOISE_SUPPRESSOR))
        }
        if (extras.containsKey(EXTRAS_ANDROID_AGC)) {
            config = config.copy(androidAgc = extras.getBoolean(EXTRAS_ANDROID_AGC))
        }
        if (extras.containsKey(EXTRAS_BLUETOOTH_WANTED)) {
            // The persisted preference is the only carrier of the Bluetooth wish.
            mRouter.bluetoothAutomatic = extras.getBoolean(EXTRAS_BLUETOOTH_WANTED)
            mRouter.apply()
        }
        if (extras.containsKey(EXTRAS_EARPIECE_BY_DEFAULT)) {
            // Live: the next apply routes it, and a user's explicit choice is left standing.
            mRouter.earpieceByDefault = extras.getBoolean(EXTRAS_EARPIECE_BY_DEFAULT)
            mRouter.apply()
        }
        if (extras.containsKey(EXTRAS_VAD_CONFIG)) {
            // Applied live: the input mode outlives pipeline rebuilds.
            mActivityInputMode.setVadConfig(
                VadConfigBundle.fromBundle(extras.getBundle(EXTRAS_VAD_CONFIG) ?: Bundle())
            )
        }

        mAudioConfig = config
        // Unconditional: AudioController skips a config equal by value and an input mode equal
        // by identity.
        mAudioController.reconfigure(mAudioConfig, mInputMode)
        return reconnectNeeded
    }

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
        return mEchoOverrides[category] ?: category.echoCancellationByDefault
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

    /**
     * Returns the current [ModelHandler], containing the channel tree. A model handler is
     * valid for the lifetime of a connection.
     * @return the active ModelHandler, or null if there is no active connection.
     */
    @Throws(NotSynchronizedException::class)
    private fun getModelHandler(): ModelHandler? {
        if (!isSynchronized()) throw NotSynchronizedException()
        if (mModelHandler == null && mConnectionState == ConnectionState.CONNECTED) {
            throw RuntimeException("Model handler should always be instantiated while connected!")
        }
        return mModelHandler
    }

    override fun getConnectionState(): ConnectionState = mConnectionState

    /** The session lifecycle as a flow. */
    override fun getSessionState(): StateFlow<SessionState> = mStateMachine.state

    /**
     * Why the last session ended. Read from the state machine, which carries the error across
     * reconnect attempts that replace the connection object.
     */
    override fun getConnectionError(): HumlaException? = when (val state = mStateMachine.current) {
        is SessionState.Disconnected -> state.error
        is SessionState.ConnectionLost -> state.error
        is SessionState.Reconnecting -> state.error
        else -> null
    }

    override fun isReconnecting(): Boolean = when (mStateMachine.current) {
        is SessionState.ConnectionLost, is SessionState.Reconnecting -> true
        else -> false
    }

    /**
     * Gives up on the automatic reconnect. An attempt in flight is disconnected too, so it cannot
     * reach onConnectionSynchronized for a session the user has just ended.
     */
    override fun cancelReconnect() {
        if (mStateMachine.cancelReconnect()) {
            mConnectionState = ConnectionState.CONNECTION_LOST
            releaseSessionResources()
            mConnection?.disconnect()
        }
    }

    /** Test seam: whether the wake lock is held. */
    fun isWakeLockHeldForTest(): Boolean = mWakeLock.isHeld

    override fun getTargetServer(): Server? = mServer

    @Throws(HumlaDisconnectedException::class)
    override fun HumlaSession(): IHumlaSession {
        if (mConnectionState != ConnectionState.CONNECTED) {
            throw HumlaDisconnectedException()
        }
        return this
    }

    override fun getTCPLatency(): Long = try {
        getConnection()!!.getTCPLatency()
    } catch (e: NotConnectedException) {
        throw IllegalStateException(e)
    }

    override fun getUDPLatency(): Long = try {
        getConnection()!!.getUDPLatency()
    } catch (e: NotConnectedException) {
        throw IllegalStateException(e)
    }

    override fun getMaxBandwidth(): Int = try {
        getConnection()!!.getMaxBandwidth()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    /**
     * The running pipeline's bandwidth in bps, or -1 while none runs. The pipeline starts and
     * stops asynchronously around the session, so this does not throw while disconnected.
     */
    override fun getCurrentBandwidth(): Int = mAudioController.currentBandwidth

    override fun getServerVersion(): Int = try {
        getConnection()!!.getServerVersion()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getServerRelease(): String? = try {
        getConnection()!!.getServerRelease()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getServerOSName(): String? = try {
        getConnection()!!.getServerOSName()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getServerOSVersion(): String? = try {
        getConnection()!!.getServerOSVersion()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getSessionId(): Int = try {
        getConnection()!!.getSession()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getSessionUser(): IUser? = try {
        getModelHandler()!!.getUser(getSessionId())
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getSessionChannel(): IChannel {
        val user = getSessionUser()
        if (user != null) return user.getChannel()
        throw IllegalStateException("Session user should be set post-synchronization!")
    }

    override fun getUser(session: Int): IUser? = try {
        getModelHandler()!!.getUser(session)
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getChannel(id: Int): IChannel? = try {
        getModelHandler()!!.getChannel(id)
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getRootChannel(): IChannel? = getChannel(0)

    override fun getPermissions(): Int = try {
        getModelHandler()!!.permissions
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun getTransmitMode(): Int = mTransmitMode

    override fun getCodec(): HumlaUDPMessageType? = try {
        getConnection()!!.getCodec()
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    /**
     * What the user asked for, independent of the current route. Survives a lost connection, a
     * headset going away and a platform refusal.
     */
    override fun usingBluetoothSco(): Boolean = mRouter.bluetoothAutomatic

    /** What the platform actually routes right now. */
    override fun isBluetoothScoActive(): Boolean = mRouter.isBluetoothActive

    override fun enableBluetoothSco() {
        mRouter.bluetoothAutomatic = true
        mRouter.apply()
    }

    override fun disableBluetoothSco() {
        mRouter.bluetoothAutomatic = false
        mRouter.apply()
    }

    override fun getAudioDevices(): List<CommunicationDevice> = mRouter.availableDevices()

    override fun isEchoCancellationEnabled(): Boolean = mAudioConfig.echoCancellation

    override fun getActiveAudioDevice(): CommunicationDevice? = mRouter.activeDevice()

    override fun selectAudioDevice(id: Int) = mRouter.choose(id)

    override fun isTalking(): Boolean = mToggleInputMode.isTalkingOn()

    override fun setTalkingState(talking: Boolean) {
        mToggleInputMode.setTalkingOn(talking)
    }

    override fun joinChannel(channel: Int) {
        moveUserToChannel(getSessionId(), channel)
    }

    override fun moveUserToChannel(session: Int, channel: Int) {
        val usb = Mumble.UserState.newBuilder()
        usb.setSession(session)
        usb.setChannelId(channel)
        getConnection()!!.sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun createChannel(
        parent: Int,
        name: String?,
        description: String?,
        position: Int,
        temporary: Boolean
    ) {
        val csb = Mumble.ChannelState.newBuilder()
        csb.setParent(parent)
        csb.setName(name)
        csb.setDescription(description)
        csb.setPosition(position)
        csb.setTemporary(temporary)
        getConnection()!!.sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    override fun sendAccessTokens(tokens: List<String>) {
        getConnection()!!.sendAccessTokens(tokens)
    }

    override fun requestPermissions(channel: Int) {
        val pqb = Mumble.PermissionQuery.newBuilder()
        pqb.setChannelId(channel)
        getConnection()!!.sendTCPMessage(pqb.build(), HumlaTCPMessageType.PermissionQuery)
    }

    override fun requestComment(session: Int) {
        val rbb = Mumble.RequestBlob.newBuilder()
        rbb.addSessionComment(session)
        getConnection()!!.sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob)
    }

    override fun requestAvatar(session: Int) {
        val rbb = Mumble.RequestBlob.newBuilder()
        rbb.addSessionTexture(session)
        getConnection()!!.sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob)
    }

    override fun requestChannelDescription(channel: Int) {
        val rbb = Mumble.RequestBlob.newBuilder()
        rbb.addChannelDescription(channel)
        getConnection()!!.sendTCPMessage(rbb.build(), HumlaTCPMessageType.RequestBlob)
    }

    override fun registerUser(session: Int) {
        val usb = Mumble.UserState.newBuilder()
        usb.setSession(session)
        usb.setUserId(0)
        getConnection()!!.sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun kickBanUser(session: Int, reason: String?, ban: Boolean) {
        val urb = Mumble.UserRemove.newBuilder()
        urb.setSession(session)
        urb.setReason(reason)
        urb.setBan(ban)
        getConnection()!!.sendTCPMessage(urb.build(), HumlaTCPMessageType.UserRemove)
    }

    override fun sendUserTextMessage(session: Int, message: String?): Message = try {
        if (!isSynchronized()) throw NotSynchronizedException()

        val tmb = Mumble.TextMessage.newBuilder()
        tmb.addSession(session)
        tmb.setMessage(message)
        getConnection()!!.sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

        val self = getModelHandler()!!.getUser(getSessionId())
        val user = getModelHandler()!!.getUser(session)
        // A message to an unknown session carries a null user.
        val users = ArrayList<User?>(1)
        users.add(user)
        Message(getSessionId(), self!!.getName(), ArrayList<Channel?>(0), ArrayList<Channel?>(0), users, message)
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message = try {
        if (!isSynchronized()) throw NotSynchronizedException()

        val tmb = Mumble.TextMessage.newBuilder()
        if (tree) tmb.addTreeId(channel) else tmb.addChannelId(channel)
        tmb.setMessage(message)
        getConnection()!!.sendTCPMessage(tmb.build(), HumlaTCPMessageType.TextMessage)

        val self = getModelHandler()!!.getUser(getSessionId())
        val targetChannel = getModelHandler()!!.getChannel(channel)
        // An unknown channel is added as null, as above.
        val targetChannels = ArrayList<Channel?>()
        targetChannels.add(targetChannel)
        Message(
            getSessionId(), self!!.getName(), targetChannels,
            if (tree) targetChannels else ArrayList<Channel?>(0), ArrayList<User?>(0), message
        )
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun setUserComment(session: Int, comment: String?) {
        val usb = Mumble.UserState.newBuilder()
        usb.setSession(session)
        usb.setComment(comment)
        getConnection()!!.sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun setPrioritySpeaker(session: Int, priority: Boolean) {
        val usb = Mumble.UserState.newBuilder()
        usb.setSession(session)
        usb.setPrioritySpeaker(priority)
        getConnection()!!.sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun removeChannel(channel: Int) {
        val crb = Mumble.ChannelRemove.newBuilder()
        crb.setChannelId(channel)
        getConnection()!!.sendTCPMessage(crb.build(), HumlaTCPMessageType.ChannelRemove)
    }

    override fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean) {
        val usb = Mumble.UserState.newBuilder()
        usb.setSession(session)
        usb.setMute(mute)
        usb.setDeaf(deaf)
        if (!mute) usb.setSuppress(false)
        getConnection()!!.sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean) {
        val usb = Mumble.UserState.newBuilder()
        usb.setSelfMute(mute)
        usb.setSelfDeaf(deaf)
        getConnection()!!.sendTCPMessage(usb.build(), HumlaTCPMessageType.UserState)
    }

    override fun registerObserver(observer: IHumlaObserver) {
        mCallbacks.registerObserver(observer)
    }

    override fun unregisterObserver(observer: IHumlaObserver) {
        mCallbacks.unregisterObserver(observer)
    }

    override fun isConnected(): Boolean = mConnectionState == ConnectionState.CONNECTED

    override fun linkChannels(channelA: IChannel, channelB: IChannel) {
        val csb = Mumble.ChannelState.newBuilder()
        csb.setChannelId(channelA.getId())
        csb.addLinksAdd(channelB.getId())
        getConnection()!!.sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    override fun unlinkChannels(channelA: IChannel, channelB: IChannel) {
        val csb = Mumble.ChannelState.newBuilder()
        csb.setChannelId(channelA.getId())
        csb.addLinksRemove(channelB.getId())
        getConnection()!!.sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    override fun unlinkAllChannels(channel: IChannel) {
        val csb = Mumble.ChannelState.newBuilder()
        csb.setChannelId(channel.getId())
        for (linked in channel.getLinks()) {
            csb.addLinksRemove(linked.getId())
        }
        getConnection()!!.sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState)
    }

    override fun registerWhisperTarget(target: WhisperTarget): Byte {
        val id = mWhisperTargetList.append(target)
        if (id < 0) {
            return -1
        }

        val voiceTarget = target.createTarget()
        val vtb = Mumble.VoiceTarget.newBuilder()
        vtb.setId(id.toInt())
        vtb.addTargets(voiceTarget)
        getConnection()!!.sendTCPMessage(vtb.build(), HumlaTCPMessageType.VoiceTarget)
        return id
    }

    override fun unregisterWhisperTarget(targetId: Byte) {
        mWhisperTargetList.free(targetId)
    }

    override fun setVoiceTargetId(targetId: Byte) {
        // `!= 0` rather than `> 0`: a negative byte masks to a negative value.
        if ((targetId.toInt() and 0x1F.inv()) != 0) {
            throw IllegalArgumentException("Target ID must be at most 5 bits.")
        }
        mVoiceTargetId = targetId
        // Also reaches the running pipeline, so the next rebuild keeps targeting it.
        mAudioController.setVoiceTargetId(targetId)
        mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(targetId))
    }

    /** Test seam: the settings the next pipeline would be built with (public for the app's tests). */
    fun getAudioConfigForTest(): AudioConfig = mAudioConfig

    override fun getVoiceTargetId(): Byte = mVoiceTargetId

    override fun getVoiceTargetMode(): VoiceTargetMode = VoiceTargetMode.fromId(mVoiceTargetId)

    override fun getWhisperTarget(): WhisperTarget? {
        if (VoiceTargetMode.fromId(mVoiceTargetId) == VoiceTargetMode.WHISPER) {
            return mWhisperTargetList.get(mVoiceTargetId)
        }
        return null
    }

    override fun getServerSettings(): ServerSettings? = try {
        getModelHandler()!!.serverSettings
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    /** The current connection state of the service. */
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

        /**
         * An action to immediately connect to a given Mumble server.
         * Requires that [EXTRAS_SERVER] is provided.
         */
        const val ACTION_CONNECT = "se.lublin.humla.CONNECT"

        /** A [Server] specifying the server to connect to. */
        const val EXTRAS_SERVER = "server"
        const val EXTRAS_AUTO_RECONNECT = "auto_reconnect"
        const val EXTRAS_CERTIFICATE = "certificate"
        const val EXTRAS_CERTIFICATE_PASSWORD = "certificate_password"
        const val EXTRAS_DETECTION_THRESHOLD = "detection_threshold"
        const val EXTRAS_AMPLITUDE_BOOST = "amplitude_boost"
        const val EXTRAS_TRANSMIT_MODE = "transmit_mode"
        const val EXTRAS_INPUT_RATE = "input_frequency"
        const val EXTRAS_INPUT_QUALITY = "input_quality"
        const val EXTRAS_FORCE_TCP = "force_tcp"
        const val EXTRAS_USE_TOR = "use_tor"
        const val EXTRAS_CLIENT_NAME = "client_name"
        const val EXTRAS_ACCESS_TOKENS = "access_tokens"
        const val EXTRAS_AUDIO_SOURCE = "audio_source"
        const val EXTRAS_AUDIO_STREAM = "audio_stream"
        const val EXTRAS_FRAMES_PER_PACKET = "frames_per_packet"

        /** An optional path to a trust store for CA certificates. */
        const val EXTRAS_TRUST_STORE = "trust_store"

        /** The trust store's password. */
        const val EXTRAS_TRUST_STORE_PASSWORD = "trust_store_password"

        /** The trust store's format. */
        const val EXTRAS_TRUST_STORE_FORMAT = "trust_store_format"
        const val EXTRAS_HALF_DUPLEX = "half_duplex"

        /** A list of users that should be local muted upon connection. */
        const val EXTRAS_LOCAL_MUTE_HISTORY = "local_mute_history"

        /** A list of users that should be local ignored upon connection. */
        const val EXTRAS_LOCAL_IGNORE_HISTORY = "local_ignore_history"
        const val EXTRAS_ENABLE_PREPROCESSOR = "enable_preprocessor"
        const val EXTRAS_NOISE_SUPPRESSION_METHOD = "noise_suppression_method"
        /**
         * Bundle: the user's echo-cancellation overrides, one boolean per [AudioDeviceCategory]
         * name. A category without an entry keeps its default (on for the speaker and the
         * earpiece, off on a headset). Applied live to the routed device.
         */
        const val EXTRAS_ECHO_CANCELLATION_BY_DEVICE = "echo_cancellation_by_device"

        /**
         * A [Bundle] carrying a whole [se.lublin.humla.audio.capture.VadConfig], see
         * [se.lublin.humla.audio.capture.VadConfigBundle].
         *
         * Supersedes [EXTRAS_DETECTION_THRESHOLD], which can only express an amplitude threshold
         * and is kept for compatibility.
         */
        const val EXTRAS_VAD_CONFIG = "vad_config"

        /** One of `SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB`. */
        const val EXTRAS_SPEEX_NOISE_SUPPRESS_DB = "speex_noise_suppress_db"

        /** `android.media.audiofx.NoiseSuppressor` on the recorder's session. */
        const val EXTRAS_ANDROID_NOISE_SUPPRESSOR = "android_noise_suppressor"

        /** `android.media.audiofx.AutomaticGainControl` on the recorder's session. */
        const val EXTRAS_ANDROID_AGC = "android_agc"

        /**
         * The persisted Bluetooth preference, i.e. the user's wish for a headset. Reconciled in
         * place, never a reconnect.
         */
        const val EXTRAS_BLUETOOTH_WANTED = "bluetooth_wanted"

        /**
         * Boolean: without a headset, route voice to the earpiece rather than the speaker. The
         * chooser overrides it per session.
         */
        const val EXTRAS_EARPIECE_BY_DEFAULT = "earpiece_by_default"

    }
}
