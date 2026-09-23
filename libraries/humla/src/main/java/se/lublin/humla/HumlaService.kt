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
import kotlinx.coroutines.flow.StateFlow
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.encoder.CELT7Encoder
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
import se.lublin.humla.session.AudioController
import se.lublin.humla.session.AudioHandlerFactory
import se.lublin.humla.session.AudioSessionParams
import se.lublin.humla.session.CommunicationDevices
import se.lublin.humla.session.DefaultAudioHandlerFactory
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.session.ScoRouter
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
 *    null - `getConnection().getTCPLatency()`, the `self` and
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
    HumlaConnection.HumlaConnectionListener, HumlaLogger {

    // Service settings
    private var mServer: Server? = null
    private var mAutoReconnect = false
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
    private var mTransmitMode = 0

    /** Current audio settings; rebuilt wholesale by [configureExtras] (spec section 4). */
    private var mAudioConfig = AudioConfig()

    /**
     * The input mode in force. Held by identity rather than derived from [mTransmitMode] at every
     * read, because the audio thread and `isTalking()` must see the *same object*: the toggle a key
     * press writes is the toggle the capture loop consults.
     */
    private lateinit var mInputMode: IInputMode

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
    /** Owns the pipeline's lifecycle on its own thread; nothing here ever joins on main (spec A2). */
    private lateinit var mAudioController: AudioController

    /** Reconciles the user's wish for a headset with the route the platform holds (spec A4). */
    private lateinit var mScoRouter: ScoRouter

    /**
     * The last warning delivered to the chat log, for the one de-duplication this service owes.
     * [ScoRouter.Listener.onScoUnavailable] fires per `apply()` rather than per state, on purpose,
     * so an auto-reconnect over a link with no headset would otherwise write the same line once
     * per attempt.
     */
    @Volatile
    private var mLastWarning: String? = null

    private lateinit var mActivityInputMode: ActivityInputMode
    private lateinit var mToggleInputMode: ToggleInputMode
    private lateinit var mContinuousInputMode: ContinuousInputMode

    /**
     * The session lifecycle (spec A3). Confined to the main thread, which is the one thread every
     * mutator here runs on: `connect`/`disconnect`/`cancelReconnect` arrive through the binder,
     * the connection's own callbacks are posted to the main looper by [HumlaConnection], and the
     * reconnect timer and the connectivity receiver both run on [mHandler]. Any other thread that
     * wants the state collects [getSessionState] instead (task 1 contract).
     */
    private lateinit var mStateMachine: SessionStateMachine

    /** Test seam: builds the connection used by [connect]. Set before `onCreate`. */
    var connectionFactory: (HumlaConnection.HumlaConnectionListener) -> HumlaConnection =
        { HumlaConnection(it) }

    /** Test seam: the backoff the session state machine uses (spec A3). Set before `onCreate`. */
    var reconnectPolicy: ReconnectPolicy = ReconnectPolicy()

    /** Test seam: builds the audio pipeline; stream B swaps in its own factory. */
    var audioFactory: AudioHandlerFactory = DefaultAudioHandlerFactory()

    /**
     * The communication-device seam: null before `onCreate`, and from `onCreate` on **the** handle
     * to the platform's routing API for this service life. A test sets it to a fake beforehand;
     * [onCreate] fills it with an [AndroidCommunicationDevices] when nothing did.
     *
     * Kept reachable on purpose. [ScoRouter] asks it for one device type, but the seam itself is
     * generic - `availableIdsOfType(type)` plus `select(id)` is the whole of what
     * `AudioManager.getAvailableCommunicationDevices()`/`setCommunicationDevice()` offer - so a
     * later "pick the output the way the phone app does" chooser docks here, beside the router,
     * without re-plumbing onCreate. See contracts.md.
     */
    var communicationDevices: CommunicationDevices? = null

    /**
     * Test seam: the CELT 0.7 bitstream versions announced in `Authenticate`.
     *
     * The default asks `libhumla_celt7`, which is in the APK and not on the JVM, so
     * `CELT7Encoder.getBitstreamVersion()` throws `UnsatisfiedLinkError` under Robolectric. It sits
     * on the handshake path, which means that without this seam **no** unit test can reach a
     * synchronized session - and the session, the audio pipeline and the SCO route are what tasks
     * A2, A3, A4 and A8 are about. Deliberately not a `try/catch` in production: a device without
     * the library does not exist, so the catch arm would be a branch nothing can reach.
     */
    var celtVersions: () -> IntArray = { intArrayOf(CELT7Encoder.getBitstreamVersion()) }

    /**
     * Listen for connectivity changes while waiting to reconnect, and retry immediately.
     */
    private val mConnectivityReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mStateMachine.current !is SessionState.ConnectionLost) {
                unregisterConnectivityReceiver()
                return
            }
            if (!isOnline()) return
            Log.v(TAG, "Connectivity restored, attempting reconnect.")
            unregisterConnectivityReceiver()
            if (mStateMachine.connectivityRestored()) mHandler.post(mReconnectRunnable)
        }
    }

    /**
     * The backoff timer. One Runnable instance for the lifetime of the service, because
     * `removeCallbacks` identifies the pending post by it: a fresh lambda per schedule would leave
     * a retry queued that nothing can cancel.
     */
    /**
     * The backoff timer.
     *
     * Nothing cancels a pending post, and that is deliberate: whether a retry may run is one
     * decision and it belongs to the state machine, which answers false in every state but
     * ConnectionLost. Four `removeCallbacks` call sites stood here -- in startSession, disconnect,
     * cancelReconnect and releaseSessionResources -- and all four survived their mutations,
     * because this guard already refuses what they were removing (spec 4.04: two guards over one
     * observable are one guard and a lie). The price of keeping only the guard is that a stale
     * post can arrive while a *later* loss is waiting out its backoff and retry it early, once;
     * the price of keeping the copies was four lines no test could distinguish.
     */
    private val mReconnectRunnable = Runnable {
        if (mStateMachine.reconnectTimerFired()) startSession()
    }

    private val mScoRouterListener = object : ScoRouter.Listener {
        override fun onScoActiveChanged(active: Boolean) = setScoRouteActive(active)

        override fun onScoUnavailable() = logWarningOnce(getString(R.string.sco_unavailable))
    }

    private val mAudioControllerListener = object : AudioController.Listener {
        override fun onAudioStarted() = Unit

        /** Spec A8: a pipeline that cannot start is a chat-log warning, not a silent failure. */
        override fun onAudioFailed(message: String) = logWarning(message)

        /** Spec A8: microphone silencing and decoder errors reach the chat log. */
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
        mStateMachine = SessionStateMachine(reconnectPolicy)
        mConnectionState = ConnectionState.DISCONNECTED
        // One instance for one service life, which is what makes AndroidCommunicationDevices report
        // a platform refusal once rather than on every route decision (task 8 contract). The
        // callback has no default so that this line cannot be left out in silence.
        val devices = communicationDevices ?: AndroidCommunicationDevices(
            getSystemService(AUDIO_SERVICE) as AudioManager,
            mHandler,
        ) { logWarningOnce(getString(R.string.bluetooth_sco_denied)) }
        communicationDevices = devices
        mScoRouter = ScoRouter(devices, mScoRouterListener)
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
        unregisterConnectivityReceiver()
        stopScoRoute()
        mScoRouter.release()
        // Posts the teardown and then quits the looper; it does not wait for either (spec A2).
        mAudioController.quit()
    }

    override fun onBind(intent: Intent?): IBinder = HumlaBinder(this)

    /**
     * User-initiated connect. A connect while an attempt is in flight or a session is up is
     * ignored by the state machine rather than by a field write here.
     *
     * Public, where the Java original was `protected`: the state machine is what the wiring tests
     * drive, and `MumlaService.reconnect()` already called it from a subclass.
     */
    open fun connect() {
        if (!mStateMachine.connectRequested()) return
        startSession()
    }

    /**
     * Builds and starts one connection attempt. The only caller besides [connect] is
     * [mReconnectRunnable], so the missing-server check below is one guard covering both entry
     * points rather than one per entry point (spec 4.04).
     */
    private fun startSession() {
        mConnectionState = ConnectionState.CONNECTING
        // The whisper slots are cleared where the session ends, not where the next one starts:
        // registerWhisperTarget needs a live connection, so there is no window between the two in
        // which they could differ, and the copy here survived its mutation for that reason
        // (spec 4.04). `mVoiceTargetId` does have such a window -- setVoiceTargetId works while
        // disconnected -- so its reset stays.
        mVoiceTargetId = 0

        // Read before anything is built, so a misconfigured start allocates nothing. Repair of a
        // pre-existing crash characterized by A9a: the Java service handed a null mServer straight
        // to HumlaConnection.connect(Server), i.e. died on a parameter check on the main looper
        // where the caller had asked for a connection attempt and expects a reported failure.
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
        mStateMachine.disconnectRequested()
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
        for (celtVersion in celtVersions()) auth.addCeltVersions(celtVersion)
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
            Log.e(TAG, "onConnectionSynchronized: model handler is null")
            return
        }

        mStateMachine.synchronized()
        mConnectionState = ConnectionState.CONNECTED

        Log.v(TAG, "Connected")
        // `if (!isHeld)`: the lock is reference counted, and with a reconnect it is now taken once
        // per session but released only when the session ends for good. Acquiring unconditionally
        // would leave a count behind that no release balances.
        if (!mWakeLock.isHeld) mWakeLock.acquire()

        // Spec A4: a session exists again, so restore the route the user asked for. The wish
        // outlives the connection; the route does not, because onConnectionDisconnected drops it.
        mScoRouter.apply()

        startAudio(connection, modelHandler)

        mCallbacks.onConnected()
    }

    /**
     * Hands the session's inputs to the audio controller, which builds the pipeline on its own
     * thread. Nothing is thrown at the caller any more: a pipeline that cannot start arrives as
     * [AudioController.Listener.onAudioFailed] and becomes a chat-log warning (spec A8), where the
     * Java original caught `AudioException` here and logged it from the main thread.
     */
    private fun startAudio(connection: HumlaConnection, modelHandler: ModelHandler) {
        val params = try {
            val self = modelHandler.getUser(connection.getSession())
            if (self == null) {
                // A ServerSync whose session id names no user. The Java original handed the null
                // to the builder and died inside it; there is nothing to send voice as, so the
                // session stays up without a microphone and says so.
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

    override fun onConnectionDisconnected(e: HumlaException?) {
        // Before anything else, and in code rather than through an observer: mToggleInputMode
        // outlives every connection, and nothing else ever clears it. Left set, an auto-reconnect
        // resumes transmitting from its first second with no key press and nothing on screen --
        // reachable with a headset media key while the screen is off. An observer cannot do this:
        // mConnectionState is set below before mCallbacks.onDisconnected(e) fires, and both
        // isConnected() and HumlaSession() read that field, so the reset would be a no-op.
        // Clearing it here also signals the toggle's condition, which releases the input thread
        // waiting in waitForInput() before the pipeline's shutdown() has to.
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
        stopScoRoute()
        // Asynchronous: the capture and playback threads are joined on humla-audio-control,
        // never on the main thread (spec A2). This call is the one the ANR came from.
        mAudioController.shutdown()

        // The one line in this method with no behavioural observable, and it is measured rather
        // than assumed: deleting it alone leaves all 95 service tests green, because every reader
        // goes through getModelHandler(), which throws NotSynchronizedException once the
        // connection is down - so the null-out can never be the reason an answer differs. It stays
        // as a retention measure: a ModelHandler holds the whole channel tree, every user and
        // their textures, and a service that keeps one after the session is over keeps all of it
        // until the next connection replaces it. Do not read this line as protection.
        mModelHandler = null
        mVoiceTargetId = 0
        mWhisperTargetList.clear()

        if (next is SessionState.ConnectionLost) {
            // Spec A3: the wake lock, the Bluetooth wish, the mute/deafen state and (in
            // MumlaService) the foreground notification all survive this transition. Releasing
            // them here is what made the microphone die with the screen off.
            mConnectionState = ConnectionState.CONNECTION_LOST
            scheduleReconnect(next.reconnectInMillis)
        } else {
            // Disconnected: either no reconnect was wanted, the attempts are spent, or the session
            // had already ended. `lost()` returns nothing else. The state's error counts as well
            // as `e`: when the session had already ended -- cancelReconnect disconnecting the
            // attempt in flight -- this late, error-free report must not turn the cancelled
            // session's CONNECTION_LOST into DISCONNECTED, nor claim to have given up.
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
     * A warning that would only repeat the last line delivered is dropped. Against the *last line*
     * rather than per message type, so the log can never end on a line that contradicts the state
     * (task 6 contract, point 1); the same shape as `HumlaConnection.warn`.
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
            registerConnectivityReceiver()
        }
    }

    /**
     * Stops routing voice to a Bluetooth headset **without forgetting that the user asked for it**:
     * spec A4 says only [enableBluetoothSco]/[disableBluetoothSco] and EXTRAS_BLUETOOTH_WANTED
     * change the wish. Idempotent - with no route to clear, `apply()` does nothing.
     */
    private fun stopScoRoute() {
        val wanted = mScoRouter.wanted
        mScoRouter.wanted = false
        mScoRouter.apply()
        mScoRouter.wanted = wanted
    }

    /** Gives back everything a live session holds. Only a Disconnected state reaches this. */
    private fun releaseSessionResources() {
        unregisterConnectivityReceiver()
        if (mWakeLock.isHeld) mWakeLock.release()
    }

    /**
     * Whether a default network is up.
     *
     * `getActiveNetwork() != null` is the modern spelling of the `activeNetworkInfo.isConnected`
     * this replaced - the platform returns null exactly when no default data network is active.
     * Deliberately *not* also a `NET_CAPABILITY_INTERNET` test: measured against Robolectric's
     * ShadowConnectivityManager, `getNetworkCapabilities` answers from a map that is empty unless
     * a test fills it, so the capability form makes the "online" corner unreachable in the suite
     * unless every test pins it open - a dimension closed by the fake rather than by the code
     * (spec 4.04).
     */
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun registerConnectivityReceiver() {
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

    private fun unregisterConnectivityReceiver() {
        try {
            unregisterReceiver(mConnectivityReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered; nothing to do.
        }
    }

    /**
     * Loads all defined settings from the given bundle into the HumlaService.
     * Some settings may only take effect after a reconnect.
     * @param extras A bundle with settings.
     * @return true if a reconnect is required for changes to take effect.
     * @see se.lublin.humla.HumlaService
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
        // EXTRAS_AUTO_RECONNECT_DELAY is accepted and ignored: ReconnectPolicy owns the backoff
        // now (spec A3), and a fixed delay is exactly what an exponential one replaces. The key
        // stays because it is public API of this library and MumlaService still writes it.
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
            // Stored as requested; AudioConfig.halfDuplex applies the push-to-talk rule against
            // the mode in force, so a later EXTRAS_TRANSMIT_MODE change alone re-evaluates it
            // (spec A7). The Java original read EXTRAS_TRANSMIT_MODE out of *this* bundle, which
            // answers 0 - voice activity - when the bundle does not carry it, so a settings write
            // that changed only half duplex always resolved to false.
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
        if (extras.containsKey(EXTRAS_ECHO_CANCELLATION_METHOD)) {
            config = config.copy(
                legacyEchoCancellationMethod = extras.getString(EXTRAS_ECHO_CANCELLATION_METHOD) ?: "none"
            )
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
            // Spec A4/P2: the persisted preference is the one carrier of the wish, and this is
            // where it reaches the router. No permission is consulted, and none can be.
            mScoRouter.wanted = extras.getBoolean(EXTRAS_BLUETOOTH_WANTED)
            mScoRouter.apply()
        }
        if (extras.containsKey(EXTRAS_VAD_CONFIG)) {
            // The one object that outlives a rebuild, which is why this needs no rebuild at all.
            mActivityInputMode.setVadConfig(
                VadConfigBundle.fromBundle(extras.getBundle(EXTRAS_VAD_CONFIG) ?: Bundle())
            )
        }

        mAudioConfig = config
        // Unconditional, and that is the point (task 7 contract, spec 4.04). Both halves of the
        // old `if` - "did anything change" and "is a pipeline up" - are decisions AudioController
        // already makes, by value for the config and by identity for the input mode. A copy here
        // could not kill a mutation, because the copy inside the controller masks it; and the SCO
        // listener calls reconfigure unconditionally, so a check here would guard one of two call
        // sites and not the other. This also replaces requiresAudioRebuild(), which answered by
        // key: re-writing a setting the pipeline already had rebuilt it, i.e. 110 ms with the
        // microphone dead, for a change that was not one.
        mAudioController.reconfigure(mAudioConfig, mInputMode)
        return reconnectNeeded
    }

    /**
     * An SCO route came or went, so the pipeline has to be rebuilt for the other sample rate and
     * stream. Unconditional: a route event that reports the state the pipeline already has is
     * dropped by [AudioController.reconfigure], which compares by value, and a doubled event is
     * otherwise an audible gap.
     */
    private fun setScoRouteActive(active: Boolean) {
        mAudioConfig = mAudioConfig.copy(bluetoothActive = active)
        // Posts to humla-audio-control; never joins on the main thread (spec A2).
        mAudioController.reconfigure(mAudioConfig, mInputMode)
    }

    /**
     * Exposes the current connection. The current connection is set once an attempt to connect to
     * a server is made, and remains set until a subsequent connection. It remains available
     * after disconnection to provide information regarding the terminated connection.
     * @return The active [HumlaConnection].
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

    /** The session lifecycle as a flow, for clients that render it (spec A3). */
    override fun getSessionState(): StateFlow<SessionState> = mStateMachine.state

    /**
     * Why the last session ended. Read from the state machine rather than from the connection: a
     * reconnect replaces the connection object, and the state machine is what carries the error
     * forward through ConnectionLost and Reconnecting to the Disconnected that ends the attempt.
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
     * Gives up on the automatic reconnect. In Reconnecting an attempt is in flight, so its
     * connection is disconnected as well: left running, a successful attempt would reach
     * onConnectionSynchronized, which takes the wake lock and starts the microphone for a session
     * the user has just ended. In ConnectionLost the connection is already down and the call is a
     * no-op. The attempt's own disconnect report then finds the state machine in Disconnected.
     */
    override fun cancelReconnect() {
        if (mStateMachine.cancelReconnect()) {
            mConnectionState = ConnectionState.CONNECTION_LOST
            releaseSessionResources()
            mConnection?.disconnect()
        }
    }

    /** Test seam: spec A3 requires the wake lock to survive a ConnectionLost. */
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
     * The running pipeline's bandwidth in bps, or **-1** while none runs.
     *
     * The Java original threw IllegalStateException while disconnected, by way of a
     * `getAudioHandler()` that demanded synchronization. The pipeline is now asynchronous: it
     * exists a short moment after ServerSync and a short moment after the session ends, so
     * "connected" and "a pipeline is up" are no longer the same statement and a caller that reads
     * this on a timer would see the exception rather than the gap.
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
     * Spec A4: what the user asked for, independent of what the platform currently routes. It
     * survives a lost connection, a headset that walks away and a platform refusal - which is why
     * the three answered IllegalStateException while disconnected before and answer the wish now.
     */
    override fun usingBluetoothSco(): Boolean = mScoRouter.wanted

    /** Spec A4: what the platform actually routes right now. */
    override fun isBluetoothScoActive(): Boolean = mScoRouter.isActive

    override fun enableBluetoothSco() {
        mScoRouter.wanted = true
        mScoRouter.apply()
    }

    override fun disableBluetoothSco() {
        mScoRouter.wanted = false
        mScoRouter.apply()
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
        // `!= 0`, where the Java original wrote `> 0`: for a *negative* byte the masked value is
        // negative too, so 0x80 passed a guard that says "at most 5 bits" and became a whisper
        // target id nothing had registered. A9a pinned the defect; this is the repair.
        if ((targetId.toInt() and 0x1F.inv()) != 0) {
            throw IllegalArgumentException("Target ID must be at most 5 bits.")
        }
        mVoiceTargetId = targetId
        // Reaches the running pipeline and the session behind it, so the next rebuild starts out
        // targeting it. Setting one while disconnected was a NullPointerException before.
        mAudioController.setVoiceTargetId(targetId)
        mCallbacks.onVoiceTargetChanged(VoiceTargetMode.fromId(targetId))
    }

    /**
     * Test seam: the settings the next pipeline would be built with. Public rather than `internal`,
     * because the app module's tests cannot see Kotlin's `internal` across a module boundary.
     */
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
        const val EXTRAS_NOISE_SUPPRESSION_METHOD = "noise_suppression_method"
        const val EXTRAS_ECHO_CANCELLATION_METHOD = "echo_cancellation_method"

        /**
         * A [Bundle] carrying a whole [se.lublin.humla.audio.capture.VadConfig], see
         * [se.lublin.humla.audio.capture.VadConfigBundle].
         *
         * One extra rather than one per slider, and it supersedes [EXTRAS_DETECTION_THRESHOLD] for
         * any caller that knows about it: the threshold alone cannot express a mode, a hold, an
         * onset or a hand-set floor, and `setThreshold` is deliberately a no-op outside
         * [se.lublin.humla.audio.capture.VadMode.AMPLITUDE]. The older extra stays because it is
         * public API of this library and because it still means exactly what it always meant.
         */
        const val EXTRAS_VAD_CONFIG = "vad_config"

        /** One of `SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB` (spec B9). */
        const val EXTRAS_SPEEX_NOISE_SUPPRESS_DB = "speex_noise_suppress_db"

        /** `android.media.audiofx.NoiseSuppressor` on the recorder's session (spec B6). */
        const val EXTRAS_ANDROID_NOISE_SUPPRESSOR = "android_noise_suppressor"

        /** `android.media.audiofx.AutomaticGainControl` on the recorder's session (spec B6). */
        const val EXTRAS_ANDROID_AGC = "android_agc"

        /**
         * Spec A4/P2: the persisted Bluetooth preference, i.e. the user's wish for a headset. The
         * **only** carrier of that wish; `ScoRouter.wanted` is derived from it and nothing in the
         * UI reads the router. Never a reconnect: the route is reconciled in place.
         */
        const val EXTRAS_BLUETOOTH_WANTED = "bluetooth_wanted"

    }
}
