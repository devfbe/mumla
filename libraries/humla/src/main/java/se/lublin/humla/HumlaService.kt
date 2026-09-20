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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.BluetoothScoReceiver
import se.lublin.humla.audio.encoder.CELT7Encoder
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.exception.AudioException
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
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate

/**
 * Converted from Java in task A9a, **without a behaviour change**. The characterization suite in
 * `HumlaServiceCharacterizationTest` was written and run green against the Java file first and runs
 * unchanged against this one; `HumlaServiceConnectCancellationTest`, `HumlaServiceDestroyTest` and
 * `HumlaServiceTransmitResetTest` predate both.
 *
 * Three rules this file follows because the conversion is the risk, not the code:
 *
 * 1. **Every accessor stays a function.** `getConnectionState()`, `isConnected()`, `isTalking()`,
 *    `isSynchronized()` - not `val`s. `IHumlaService`/`IHumlaSession` declare them as methods and a
 *    Kotlin `var`/`val` of the matching name produces a platform declaration clash (spec 4.05),
 *    which has cost this project four test scaffolds. It also keeps every existing caller, Java and
 *    Kotlin, compiling against the same call syntax.
 * 2. **Nullability is preserved, not repaired.** Where the Java dereferenced a field that can be
 *    null - `getConnection().getTCPLatency()`, `mAudioHandler.setVoiceTargetId(...)`, the `self` and
 *    `user` of a text message - this file writes `!!` and throws the same NullPointerException at
 *    the same point. Replacing those with `require`/`checkNotNull` or with `?.` changes an
 *    observable, and `everySessionCallThrowsItsOwnExceptionWhileDisconnected` is the test that
 *    says which one each call throws.
 * 3. **Nothing is tidied.** `(targetId and 0x1F.inv()) > 0` keeps its `> 0` (a negative id passes -
 *    see `aNegativeVoiceTargetIdPassesTheFiveBitGuard`), `EXTRAS_HALF_DUPLEX` keeps reading the
 *    transmit mode out of its own bundle, and the `BuildConfig.DEBUG` assertion in
 *    [createAudioHandler] stays. All three are handed to A9b as riders.
 */
open class HumlaService : Service(), IHumlaService, IHumlaSession,
    HumlaConnection.HumlaConnectionListener, HumlaLogger, BluetoothScoReceiver.Listener {

    // Service settings
    private var mServer: Server? = null
    private var mAutoReconnect = false
    private var mAutoReconnectDelay = 0
    private var mCertificate: ByteArray? = null
    private var mCertificatePassword: String? = null
    private var mUseOpus = false
    private var mForceTcp = false
    private var mUseTor = false
    private var mClientName: String? = null
    private var mAccessTokens: List<String>? = null
    private var mTrustStore: String? = null
    private var mTrustStorePassword: String? = null
    private var mTrustStoreFormat: String? = null
    private var mLocalMuteHistory: List<Int>? = null
    private var mLocalIgnoreHistory: List<Int>? = null
    private lateinit var mAudioBuilder: AudioHandler.Builder
    private var mTransmitMode = 0

    private var mVoiceTargetId: Byte = 0
    private lateinit var mWhisperTargetList: WhisperTargetList

    private lateinit var mWakeLock: PowerManager.WakeLock
    private lateinit var mHandler: Handler
    private lateinit var mCallbacks: HumlaCallbacks

    // @Volatile: both are written on the main thread and read from the protocol thread, which now
    // calls logInfo/logWarning through ModelHandler. Without it a protocol-thread reader can see a
    // stale mConnection -- including the previous connection's -- or a null mModelHandler that the
    // main thread has already replaced.
    @Volatile
    private var mConnection: HumlaConnection? = null
    private var mConnectionState: ConnectionState = ConnectionState.DISCONNECTED

    @Volatile
    private var mModelHandler: ModelHandler? = null
    private var mAudioHandler: AudioHandler? = null
    private lateinit var mBluetoothReceiver: BluetoothScoReceiver

    private lateinit var mActivityInputMode: ActivityInputMode
    private lateinit var mToggleInputMode: ToggleInputMode
    private lateinit var mContinuousInputMode: ContinuousInputMode

    private var mReconnecting = false

    /**
     * Listen for connectivity changes in the reconnection state, and reconnect accordingly.
     */
    private val mConnectivityReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!mReconnecting) {
                try {
                    unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Error unregistering connectivity receiver: " + e.message)
                }
                return
            }

            val cm = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (info != null && info.isConnected) {
                Log.v(TAG, "Connectivity restored, attempting reconnect.")
                connect()
            }
        }
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
                try {
                    configureExtras(extras)
                } catch (e: AudioException) {
                    throw RuntimeException("Attempted to initialize audio in onStartCommand erroneously.")
                }
            }

            if (ACTION_CONNECT == intent.action) {
                if (extras == null || !extras.containsKey(EXTRAS_SERVER)) {
                    // Ensure that we have been provided all required attributes.
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
        mAudioBuilder = AudioHandler.Builder()
            .setContext(this)
            .setLogger(this)
            .setEncodeListener(mAudioInputListener)
            .setTalkingListener(mAudioOutputListener)
        mConnectionState = ConnectionState.DISCONNECTED
        mBluetoothReceiver = BluetoothScoReceiver(this, this)
        registerReceiver(mBluetoothReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        mToggleInputMode = ToggleInputMode()
        mActivityInputMode = ActivityInputMode(0f) // FIXME: reasonable default
        mContinuousInputMode = ContinuousInputMode()
        mWhisperTargetList = WhisperTargetList()

        // initialize minidns dns lookup mechanisms
        AndroidUsingLinkProperties.setup(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The protocol thread is non-daemon and only HumlaConnection.disconnect() quits its looper,
        // so a service destroyed while connected left "humla-protocol" running -- with the socket,
        // both transports and everything its queue still referenced -- for the life of the process.
        //
        // The order of the three statements: super.onDestroy() first because nothing below reads
        // anything it touches, and disconnect() before unregisterReceiver() because the two do not
        // meet. disconnect() only raises a flag, queues the teardown on the protocol looper and
        // posts the disconnect report to main; it runs no listener code inline, so nothing between
        // these lines can reach the receiver. Swapping them changes nothing observable.
        //
        // What the order does not fix, because no order can: onConnectionDisconnected is delivered
        // on a later turn of the main looper, i.e. after this method has returned. There it joins
        // the audio threads on main (AudioHandler.shutdown(), spec 4.1 "Take AudioHandler.shutdown()
        // off the main thread", tasks 7 and 9) and calls stopBluetoothSco() on a receiver that is no
        // longer registered -- harmless, since that only asks AudioManager to drop SCO and
        // registration decides nothing but whether the state broadcast is heard.
        disconnect()
        try {
            unregisterReceiver(mBluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error unregistering bluetooth receiver: " + e.message)
        }
    }

    override fun onBind(intent: Intent?): IBinder = HumlaBinder(this)

    protected open fun connect() {
        setReconnecting(false)
        mConnectionState = ConnectionState.DISCONNECTED
        mVoiceTargetId = 0
        mWhisperTargetList.clear()

        val connection = HumlaConnection(this)
        mConnection = connection
        connection.setForceTCP(mForceTcp)
        connection.setUseTor(mUseTor)
        connection.setKeys(mCertificate, mCertificatePassword)
        connection.setTrustStore(mTrustStore, mTrustStorePassword, mTrustStoreFormat)

        val modelHandler =
            ModelHandler(this, mCallbacks, this, mLocalMuteHistory, mLocalIgnoreHistory)
        mModelHandler = modelHandler
        connection.addTCPMessageHandlers(modelHandler)

        mConnectionState = ConnectionState.CONNECTING

        mCallbacks.onConnecting()

        try {
            // Resolves the host (SRV lookup included) and opens the socket on the protocol thread;
            // every failure, certificate errors included, arrives at onConnectionDisconnected.
            connection.connect(mServer!!)
        } catch (e: IllegalStateException) {
            // mCallbacks.onConnecting() above is raised on this handler's own thread with an empty
            // queue, so it is delivered inline: an observer can call disconnect() from inside it,
            // and the connection it marks as disconnected is the one this line is about to start.
            // HumlaConnection is single-use and refuses. Without this the refusal would be thrown
            // out of onStartCommand, or out of the reconnect runnable -- a crash where the old code
            // reported a failed connection attempt. HumlaConnection reports nothing itself here:
            // it was never started, so its own disconnect delivered nothing.
            //
            // Deliberately not narrowed. connect() raises IllegalStateException from two checks,
            // and the other one -- a connection used twice -- cannot fire here, because mConnection
            // was created a dozen lines above and is never handed out before this call. Telling the
            // two apart would mean a condition that no test can make true, i.e. a branch whose
            // removal nothing notices; spec 4.04 says not to write one. If connect() ever throws
            // IllegalStateException for a third reason, this comment is what has to be revisited.
            Log.w(TAG, "Connection was cancelled before it could start", e)
            mConnectionState = ConnectionState.DISCONNECTED
            mCallbacks.onDisconnected(
                HumlaException(e, HumlaException.HumlaDisconnectReason.OTHER_ERROR)
            )
        }
    }

    override fun disconnect() {
        mConnection?.disconnect()
    }

    fun isConnectionEstablished(): Boolean = mConnection?.isConnected == true

    /**
     * @return true if Humla has received the ServerSync message, indicating synchronization with
     * the server's model and settings. This is the main state of the service.
     */
    fun isSynchronized(): Boolean = mConnection?.isSynchronized == true

    override fun onConnectionEstablished() {
        // Send version information and authenticate.
        val version = Mumble.Version.newBuilder()
        version.setRelease(mClientName)
        version.setVersion(Constants.PROTOCOL_VERSION)
        version.setOs("Android")
        version.setOsVersion(Build.VERSION.RELEASE)

        val auth = Mumble.Authenticate.newBuilder()
        auth.setUsername(mServer!!.username)
        auth.setPassword(mServer!!.password)
        auth.addCeltVersions(CELT7Encoder.getBitstreamVersion())
        // FIXME: resolve issues with CELT 11 robot voices.
        //     auth.addCeltVersions(Constants.CELT_11_VERSION);
        auth.setOpus(mUseOpus)
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
            Log.e(TAG, "onConnectionSynchronized: mAudioHandler is null")
            return
        }

        mConnectionState = ConnectionState.CONNECTED

        Log.v(TAG, "Connected")
        mWakeLock.acquire()

        try {
            val audioHandler = mAudioBuilder.initialize(
                modelHandler.getUser(connection.getSession()),
                connection.getMaxBandwidth(), connection.getCodec(),
                mVoiceTargetId
            )
            mAudioHandler = audioHandler
            connection.addTCPMessageHandlers(audioHandler)
            connection.addUDPMessageHandlers(audioHandler)
        } catch (e: AudioException) {
            Log.w(TAG, "Could not initialize audio", e)
            logWarning(e.message)
        } catch (e: NotSynchronizedException) {
            throw RuntimeException(
                "Connection should be synchronized in callback for synchronization!", e
            )
        }

        mCallbacks.onConnected()
    }

    override fun onConnectionHandshakeFailed(chain: Array<X509Certificate>) {
        mCallbacks.onTLSHandshakeFailed(chain)
    }

    override fun onConnectionDisconnected(e: HumlaException?) {
        // Before anything else, and in code rather than through an observer: mToggleInputMode
        // outlives every connection, and nothing else ever clears it. Left set, an auto-reconnect
        // resumes transmitting from its first second with no key press and nothing on screen --
        // reachable with a headset media key while the screen is off. An observer cannot do this:
        // mConnectionState is set below before mCallbacks.onDisconnected(e) fires, and both
        // isConnected() and HumlaSession() read that field, so the reset would be a no-op.
        // Clearing it here also signals the toggle's condition, which releases the input thread
        // waiting in waitForInput() before mAudioHandler.shutdown() has to.
        mToggleInputMode.setTalkingOn(false)

        if (e != null) {
            Log.e(TAG, "Error: " + e.message + " (reason: " + e.reason.name + ")")
            mConnectionState = ConnectionState.CONNECTION_LOST

            setReconnecting(
                mAutoReconnect && e.reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
            )
        } else {
            Log.v(TAG, "Disconnected")
            mConnectionState = ConnectionState.DISCONNECTED
        }

        if (mWakeLock.isHeld) {
            mWakeLock.release()
        }

        mAudioHandler?.shutdown()

        mModelHandler = null
        mAudioHandler = null
        mVoiceTargetId = 0
        mWhisperTargetList.clear()

        // Halt SCO connection on shutdown.
        mBluetoothReceiver.stopBluetoothSco()

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
        mCallbacks.onLogWarning(message)
    }

    override fun logError(message: String?) {
        mCallbacks.onLogError(message)
    }

    fun setReconnecting(reconnecting: Boolean) {
        if (mReconnecting == reconnecting) {
            return
        }

        mReconnecting = reconnecting
        if (reconnecting) {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            val online = info != null && info.isConnected
            if (online) {
                Log.v(TAG, "Connection lost due to non-connectivity issue. Start reconnect polling.")
                val mainHandler = Handler(mainLooper)
                mainHandler.postDelayed({
                    if (mReconnecting) connect()
                }, mAutoReconnectDelay.toLong())
            } else {
                // In the event that we've lost connectivity, don't poll. Wait until network
                // returns before we resume connection attempts.
                Log.v(TAG, "Connection lost due to connectivity issue. Waiting until network returns.")
                try {
                    @Suppress("DEPRECATION")
                    registerReceiver(
                        mConnectivityReceiver,
                        IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                    )
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Error registering connectivity receiver: " + e.message)
                }
            }
        } else {
            try {
                unregisterReceiver(mConnectivityReceiver)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error unregistering connectivity receiver: " + e.message)
            }
        }
    }

    /**
     * Instantiates an audio handler with the current service settings, destroying any previous
     * handler. Requires synchronization with the server, as the maximum bandwidth and session must
     * be known.
     */
    @Throws(AudioException::class)
    private fun createAudioHandler() {
        if (BuildConfig.DEBUG && mConnectionState != ConnectionState.CONNECTED) {
            throw AssertionError("Attempted to instantiate audio handler when not connected!")
        }

        val connection = mConnection!!
        val audioHandler = mAudioHandler
        if (audioHandler != null) {
            connection.removeTCPMessageHandler(audioHandler)
            connection.removeUDPMessageHandler(audioHandler)
            audioHandler.shutdown()
        }

        try {
            val created = mAudioBuilder.initialize(
                mModelHandler!!.getUser(connection.getSession()),
                connection.getMaxBandwidth(), connection.getCodec(),
                mVoiceTargetId
            )
            mAudioHandler = created
            connection.addTCPMessageHandlers(created)
            connection.addUDPMessageHandlers(created)
        } catch (e: NotSynchronizedException) {
            throw RuntimeException("Attempted to create audio handler when not synchronized!")
        }
    }

    /**
     * Loads all defined settings from the given bundle into the HumlaService.
     * Some settings may only take effect after a reconnect.
     * @param extras A bundle with settings.
     * @return true if a reconnect is required for changes to take effect.
     * @see se.lublin.humla.HumlaService
     */
    @Throws(AudioException::class)
    fun configureExtras(extras: Bundle): Boolean {
        var reconnectNeeded = false
        if (extras.containsKey(EXTRAS_SERVER)) {
            @Suppress("DEPRECATION")
            mServer = extras.getParcelable(EXTRAS_SERVER)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT)) {
            mAutoReconnect = extras.getBoolean(EXTRAS_AUTO_RECONNECT)
        }
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT_DELAY)) {
            mAutoReconnectDelay = extras.getInt(EXTRAS_AUTO_RECONNECT_DELAY)
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
            mAudioBuilder.setAmplitudeBoost(extras.getFloat(EXTRAS_AMPLITUDE_BOOST))
        }
        if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
            mTransmitMode = extras.getInt(EXTRAS_TRANSMIT_MODE)
            val inputMode: IInputMode = when (mTransmitMode) {
                Constants.TRANSMIT_PUSH_TO_TALK -> mToggleInputMode
                Constants.TRANSMIT_CONTINUOUS -> mContinuousInputMode
                Constants.TRANSMIT_VOICE_ACTIVITY -> mActivityInputMode
                else -> throw IllegalArgumentException()
            }
            mAudioBuilder.setInputMode(inputMode)
        }
        if (extras.containsKey(EXTRAS_INPUT_RATE)) {
            mAudioBuilder.setInputSampleRate(extras.getInt(EXTRAS_INPUT_RATE))
        }
        if (extras.containsKey(EXTRAS_INPUT_QUALITY)) {
            mAudioBuilder.setTargetBitrate(extras.getInt(EXTRAS_INPUT_QUALITY))
        }
        if (extras.containsKey(EXTRAS_USE_OPUS)) {
            mUseOpus = extras.getBoolean(EXTRAS_USE_OPUS)
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_USE_TOR)) {
            mUseTor = extras.getBoolean(EXTRAS_USE_TOR)
            mForceTcp = mForceTcp or mUseTor // Tor requires TCP connections to work- if it's on, force TCP.
            reconnectNeeded = true
        }
        if (extras.containsKey(EXTRAS_FORCE_TCP)) {
            mForceTcp = mForceTcp or extras.getBoolean(EXTRAS_FORCE_TCP)
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
            mAudioBuilder.setAudioSource(extras.getInt(EXTRAS_AUDIO_SOURCE))
        }
        if (extras.containsKey(EXTRAS_AUDIO_STREAM)) {
            mAudioBuilder.setAudioStream(extras.getInt(EXTRAS_AUDIO_STREAM))
        }
        if (extras.containsKey(EXTRAS_FRAMES_PER_PACKET)) {
            mAudioBuilder.setTargetFramesPerPacket(extras.getInt(EXTRAS_FRAMES_PER_PACKET))
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
            mAudioBuilder.setHalfDuplexEnabled(
                extras.getInt(EXTRAS_TRANSMIT_MODE) == Constants.TRANSMIT_PUSH_TO_TALK &&
                    extras.getBoolean(EXTRAS_HALF_DUPLEX)
            )
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
            mAudioBuilder.setPreprocessorEnabled(extras.getBoolean(EXTRAS_ENABLE_PREPROCESSOR))
        }
        if (extras.containsKey(EXTRAS_ECHO_CANCELLATION_METHOD)) {
            mAudioBuilder.setEchoCancellationMethod(extras.getString(EXTRAS_ECHO_CANCELLATION_METHOD))
        }

        // Reload audio subsystem if initialized
        val audioHandler = mAudioHandler
        if (audioHandler != null && audioHandler.isInitialized) {
            createAudioHandler()
            Log.i(TAG, "Audio subsystem reloaded after settings change.")
        }
        return reconnectNeeded
    }

    override fun onBluetoothScoConnected() {
        // After an SCO connection is established, audio is rerouted to be compatible with SCO.
        mAudioBuilder.setBluetoothEnabled(true)
        if (mAudioHandler != null) {
            try {
                createAudioHandler()
            } catch (e: AudioException) {
                e.printStackTrace()
            }
        }
    }

    override fun onBluetoothScoDisconnected() {
        // Restore audio settings after disconnection.
        mAudioBuilder.setBluetoothEnabled(false)
        if (mAudioHandler != null) {
            try {
                createAudioHandler()
            } catch (e: AudioException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Exposes the current connection. The current connection is set once an attempt to connect to
     * a server is made, and remains set until a subsequent connection. It remains available
     * after disconnection to provide information regarding the terminated connection.
     * @return The active [HumlaConnection].
     */
    fun getConnection(): HumlaConnection? = mConnection

    /**
     * Returnes the current [AudioHandler]. An AudioHandler is instantiated upon connection
     * to a server, and destroyed upon disconnection.
     * @return the active AudioHandler, or null if there is no active connection.
     */
    @Throws(NotSynchronizedException::class)
    private fun getAudioHandler(): AudioHandler? {
        if (!isSynchronized()) throw NotSynchronizedException()
        if (mAudioHandler == null && mConnectionState == ConnectionState.CONNECTED) {
            throw RuntimeException("Audio handler should always be instantiated while connected!")
        }
        return mAudioHandler
    }

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

    /**
     * Returns the bluetooth service provider, established after synchronization.
     * @return The [BluetoothScoReceiver] attached to this service.
     */
    @Throws(NotSynchronizedException::class)
    private fun getBluetoothReceiver(): BluetoothScoReceiver {
        if (!isSynchronized()) throw NotSynchronizedException()
        return mBluetoothReceiver
    }

    override fun getConnectionState(): ConnectionState = mConnectionState

    override fun getConnectionError(): HumlaException? = getConnection()?.error

    override fun isReconnecting(): Boolean = mReconnecting

    override fun cancelReconnect() {
        setReconnecting(false)
    }

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

    override fun getCurrentBandwidth(): Int = try {
        getAudioHandler()!!.currentBandwidth
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

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

    override fun usingBluetoothSco(): Boolean = try {
        getBluetoothReceiver().isBluetoothScoOn
    } catch (e: NotSynchronizedException) {
        throw IllegalStateException(e)
    }

    override fun enableBluetoothSco() {
        try {
            getBluetoothReceiver().startBluetoothSco()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

    override fun disableBluetoothSco() {
        try {
            getBluetoothReceiver().stopBluetoothSco()
        } catch (e: NotSynchronizedException) {
            throw IllegalStateException(e)
        }
    }

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

    override fun requestBanList() {
        throw UnsupportedOperationException("Not yet implemented") // TODO
    }

    override fun requestUserList() {
        throw UnsupportedOperationException("Not yet implemented") // TODO
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
        // The Java original added `user` unconditionally, null included; a null element in the
        // list is what a message to an unknown session has always produced. Preserved.
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
        // As above: the Java original added the channel unconditionally, null included.
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
        // `> 0`, not `!= 0`: for a negative byte the masked value is negative and the guard does
        // not fire, so 0x80 is accepted. Pre-existing; pinned by
        // aNegativeVoiceTargetIdPassesTheFiveBitGuard and handed to A9b rather than repaired here.
        if ((targetId.toInt() and 0x1F.inv()) > 0) {
            throw IllegalArgumentException("Target ID must be at most 5 bits.")
        }
        mVoiceTargetId = targetId
        // Unconditional, as it was: setting a voice target while disconnected throws.
        mAudioHandler!!.setVoiceTargetId(targetId)
        mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(targetId))
    }

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

    /**
     * The current connection state of the service.
     */
    enum class ConnectionState {
        /**
         * The default state of Humla, before connection to a server and after graceful/expected
         * disconnection from a server.
         */
        DISCONNECTED,

        /**
         * A connection to the server is currently in progress.
         */
        CONNECTING,

        /**
         * Humla has received all data necessary for normal protocol communication with the server.
         */
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
        const val EXTRAS_AUTO_RECONNECT_DELAY = "auto_reconnect_delay"
        const val EXTRAS_CERTIFICATE = "certificate"
        const val EXTRAS_CERTIFICATE_PASSWORD = "certificate_password"
        const val EXTRAS_DETECTION_THRESHOLD = "detection_threshold"
        const val EXTRAS_AMPLITUDE_BOOST = "amplitude_boost"
        const val EXTRAS_TRANSMIT_MODE = "transmit_mode"
        const val EXTRAS_INPUT_RATE = "input_frequency"
        const val EXTRAS_INPUT_QUALITY = "input_quality"
        const val EXTRAS_USE_OPUS = "use_opus"
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
        const val EXTRAS_ECHO_CANCELLATION_METHOD = "echo_cancellation_method"
    }
}
