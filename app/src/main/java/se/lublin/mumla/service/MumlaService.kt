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

package se.lublin.mumla.service

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.jsoup.Jsoup
import se.lublin.humla.Constants
import se.lublin.humla.HumlaService
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.SessionState
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver
import se.lublin.mumla.util.HtmlUtils
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
class MumlaService : HumlaService(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MumlaConnectionNotification.OnActionListener,
    MumlaReconnectNotification.OnActionListener,
    IMumlaService {

    private lateinit var mSettings: Settings
    /**
     * One per service life. Whether the service is in the foreground is its [isForeground]; a
     * fresh object per connect would be a second place holding that answer.
     */
    private lateinit var mNotification: MumlaConnectionNotification
    private lateinit var mMessageNotification: MumlaMessageNotification
    private var mReconnectNotification: MumlaReconnectNotification? = null

    /** Headset / AVRCP media buttons while connected (stream P). */
    private var mMediaSession: MumlaMediaSession? = null

    /** Channel view overlay. */
    private lateinit var mChannelOverlay: MumlaOverlay

    /** Proximity lock, held while voice goes to the earpiece. */
    private var mProximityLock: PowerManager.WakeLock? = null

    /** Play sound when push to talk key is pressed */
    private var mPTTSoundEnabled = false

    /** Try to shorten spoken messages when using TTS */
    private var mShortTtsMessagesEnabled = false

    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private var mErrorShown = false
    /** Bounded (spec D5). */
    private val mMessageLog = ChatMessageLog()
    private var mSuppressNotifications = false

    private var mTTS: TextToSpeech? = null
    private val mTTSInitListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.ERROR) logWarning(getString(R.string.tts_failed))
    }

    /** The view representing the hot corner. */
    private lateinit var mHotCorner: MumlaHotCorner
    private val mHotCornerListener = object : MumlaHotCorner.MumlaHotCornerListener {
        override fun onHotCornerDown() {
            onTalkKeyDown()
        }

        override fun onHotCornerUp() {
            onTalkKeyUp()
        }
    }

    private lateinit var mTalkReceiver: BroadcastReceiver

    /**
     * Test seam: the push-to-talk click. Robolectric's AudioManager records no sound effect, so
     * without it nothing could read back the five-clause condition in onUserTalkStateUpdated.
     */
    internal var keyClickSound: () -> Unit = {
        (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    /**
     * Collects the session state for the lifetime of the service. Main.immediate, because the state
     * machine is mutated on the main thread: every transition is rendered inline, before the call
     * that caused it returns, so no later line in HumlaService can observe a stale notification.
     */
    private val mServiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mObserver = object : HumlaObserver() {
        override fun onUserConnected(user: IUser) {
            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession())
            }
        }

        override fun onUserStateUpdated(user: IUser?) {
            if (user == null) {
                return
            }

            val selfSession = try {
                getSessionId()
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserStateUpdated: $e")
                return
            }

            if (user.getSession() == selfSession) {
                // Update settings mute/deafen state
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened())
                if (mNotification.isForeground) {
                    val contentText = if (user.isSelfMuted() && user.isSelfDeafened()) {
                        getString(R.string.status_notify_muted_and_deafened)
                    } else if (user.isSelfMuted()) {
                        getString(R.string.status_notify_muted)
                    } else {
                        getString(R.string.connected)
                    }
                    mNotification.customContentText = contentText
                    mNotification.show()
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession())
            }
        }

        override fun onMessageLogged(message: IMessage) {
            // Split on / strip all HTML tags.
            val parsedMessage = Jsoup.parseBodyFragment(message.getMessage())
            val strippedMessage = parsedMessage.text()

            val ttsMessage = if (mShortTtsMessagesEnabled) {
                for (anchor in parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    val href = anchor.attr("href")
                    // Only shorten anchors without custom text
                    if (href == anchor.text()) {
                        val urlHostname = HtmlUtils.getHostnameFromLink(href)
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname))
                        }
                    }
                }
                parsedMessage.text()
            } else {
                strippedMessage
            }

            val formattedTtsMessage = getString(R.string.notification_message, message.getActorName(), ttsMessage)

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not
            // deafened. "Enabled" is `mTTS != null`: the preference listener creates and shuts it
            // down with the setting, so a second check of the setting here could never differ
            // (removing it alone left the suite green, spec 4.04).
            val tts = mTTS
            if (tts != null &&
                formattedTtsMessage.length <= TTS_THRESHOLD &&
                getSessionUser() != null &&
                !getSessionUser()!!.isSelfDeafened()
            ) {
                @Suppress("DEPRECATION")
                tts.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null)
            }

            // TODO: create a customizable notification sieve
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message)
            }

            mMessageLog.add(IChatMessage.TextMessage(message))
        }

        override fun onLogInfo(message: String) {
            mMessageLog.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message))
        }

        override fun onLogWarning(message: String) {
            mMessageLog.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message))
        }

        override fun onLogError(message: String) {
            mMessageLog.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message))
        }

        override fun onPermissionDenied(reason: String?) {
            if (mNotification.isForeground && !mSuppressNotifications) {
                mNotification.show()
            }
        }

        override fun onUserTalkStateUpdated(user: IUser) {
            var selfSession = -1
            try {
                selfSession = getSessionId()
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: $e")
            }

            if (isConnectionEstablished() &&
                user.getSession() == selfSession &&
                getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                user.getTalkState() == TalkState.TALKING &&
                mPTTSoundEnabled
            ) {
                keyClickSound()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerObserver(mObserver)

        // Register for preference changes
        mSettings = Settings.getInstance(this)
        mPTTSoundEnabled = mSettings.isPttSoundEnabled()
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        applyBluetoothPreference()

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla)

        mNotification = MumlaConnectionNotification.create(this, "", this)
        mMessageNotification = MumlaMessageNotification(this@MumlaService)

        // Instantiate overlay view
        mChannelOverlay = MumlaOverlay(this)
        mHotCorner = MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener)

        // Set up TTS
        if (mSettings.isTextToSpeechEnabled()) mTTS = TextToSpeech(this, mTTSInitListener)

        mTalkReceiver = TalkBroadcastReceiver(this)

        mMediaSession = MumlaMediaSession(this, HumlaMediaKeyTarget(this), mSettings).also { it.attach(this) }

        // Spec A3/A6: the notification is a function of the session state, and only Disconnected
        // tears the foreground down.
        mServiceScope.launch { getSessionState().collect { renderSessionState(it) } }
    }

    /**
     * Renders one session state into the foreground notification (spec A3, A6) -- the only place
     * that decides whether the service is in the foreground.
     *
     * `Connecting` enters the foreground; everything up to `Disconnected` only changes its text.
     * In particular `ConnectionLost` and `Reconnecting` keep it: leaving the foreground on a loss
     * meant the reconnect had to start it again, from the background with the screen off, which
     * Android 12+ refuses -- and the microphone stayed dead after every reconnect.
     */
    internal fun renderSessionState(state: SessionState) {
        when (state) {
            SessionState.Connecting -> {
                // Remove old notification left from reconnect,
                mReconnectNotification?.let {
                    it.hide()
                    mReconnectNotification = null
                }
                mErrorShown = false
                showConnectionNotification(getString(R.string.mumlaConnecting) + torSuffix())
            }
            SessionState.Connected ->
                showConnectionNotification(getString(R.string.connected) + torSuffix(), actions = true)
            is SessionState.ConnectionLost, is SessionState.Reconnecting ->
                showConnectionNotification(getString(R.string.connection_lost_reconnecting), cancelReconnect = true)
            is SessionState.Disconnected -> {
                mNotification.hide()
                // Session-visible state: spec A3 keeps it across a ConnectionLost, so it goes here
                // and not in onConnectionDisconnected, which runs on every loss.
                clearMessageLog()
                mMessageNotification.dismiss()
                val error = state.error
                if (error != null && !mSuppressNotifications) {
                    mReconnectNotification?.hide()
                    mReconnectNotification =
                        MumlaReconnectNotification.show(this, error.message + torSuffix(), false, this)
                }
            }
        }
    }

    private fun torSuffix(): String = if (mSettings.isTorEnabled()) " (Tor)" else ""

    private fun showConnectionNotification(
        contentText: String,
        actions: Boolean = false,
        cancelReconnect: Boolean = false,
    ) {
        mNotification.customContentText = contentText
        mNotification.actionsShown = actions
        mNotification.cancelReconnectShown = cancelReconnect
        if (!mNotification.show()) {
            // Spec A6: the platform refused the foreground start. Say so instead of dying -- once
            // while the refusal repeats, since every later state change tries again.
            logWarningOnce(getString(R.string.foreground_start_failed))
            if (!mSuppressNotifications) {
                mReconnectNotification?.hide()
                mReconnectNotification = MumlaReconnectNotification.show(
                    this, getString(R.string.foreground_start_failed), false, this,
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = MumlaBinder(this)

    override fun onDestroy() {
        // Stops rendering: super.onDestroy() below disconnects, and nothing may enter the
        // foreground for a service that is going away.
        mServiceScope.cancel()
        mNotification.hide()
        mReconnectNotification?.let {
            it.hide()
            mReconnectNotification = null
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(mTalkReceiver)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }

        // Null-checked like every other teardown in this method: it is the last thing onCreate
        // builds, so anything that throws earlier -- the TTS constructor above it, say -- gets
        // here with the field still null.
        mMediaSession?.detach(this)
        unregisterObserver(mObserver)
        mTTS?.shutdown()
        mMessageNotification.dismiss()
        super.onDestroy()
    }

    override fun onConnectionSynchronized() {
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        try {
            super.onConnectionSynchronized()
        } catch (e: RuntimeException) {
            Log.d(TAG, "exception in onConnectionSynchronized: $e")
            return
        }

        // Restore mute/deafen state
        if (mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened())
        }

        // No Bluetooth restore here any more. The stored wish reaches the router from onCreate
        // and on every change (applyBluetoothPreference), and the superclass hook above takes the
        // route when it engages the router -- before any line of this method, so nothing here can
        // throw in front of it.

        ContextCompat.registerReceiver(
            this, mTalkReceiver,
            IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), ContextCompat.RECEIVER_EXPORTED,
        )

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true)
        }
        // The proximity sensor follows the earpiece route (onAudioRouteChanged), not this hook.
    }

    override fun onConnectionDisconnected(e: HumlaException?) {
        super.onConnectionDisconnected(e)
        try {
            unregisterReceiver(mTalkReceiver)
        } catch (iae: IllegalArgumentException) {
        }

        // Remove overlay if present.
        mChannelOverlay.hide()

        mHotCorner.setShown(false)

        setProximitySensorOn(false)
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Everything that is an audio extra lives in one function, so that "is this switch on the
        // settings screen connected to anything?" has an answer a test can read. What stays below
        // is the cases whose effect is not an extra.
        val changedExtras = AudioPreferenceExtras.extrasFor(key!!, mSettings)
        var requiresReconnect = false
        when (key) {
            Settings.PREF_INPUT_METHOD ->
                mChannelOverlay.setPushToTalkShown(mSettings.getHumlaInputMethod() == Constants.TRANSMIT_PUSH_TO_TALK)
            Settings.PREF_HOT_CORNER_KEY -> {
                mHotCorner.setGravity(mSettings.getHotCornerGravity())
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled())
            }
            Settings.PREF_USE_TTS -> {
                val tts = mTTS
                if (tts == null && mSettings.isTextToSpeechEnabled()) {
                    mTTS = TextToSpeech(this, mTTSInitListener)
                } else if (tts != null && !mSettings.isTextToSpeechEnabled()) {
                    tts.shutdown()
                    mTTS = null
                }
            }
            Settings.PREF_SHORT_TTS_MESSAGES ->
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled()
            Settings.PREF_PTT_SOUND ->
                mPTTSoundEnabled = mSettings.isPttSoundEnabled()
            Settings.PREF_BLUETOOTH_SCO -> applyBluetoothPreference()
            Settings.PREF_CERT_ID,
            Settings.PREF_FORCE_TCP,
            Settings.PREF_USE_TOR,
            Settings.PREF_DISABLE_OPUS,
            ->
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true
        }
        if (changedExtras.size() > 0) {
            // Reconfigure the service appropriately. The result is not read: configureExtras asks
            // for a reconnect only for server, certificate, codec, transport and history keys,
            // and AudioPreferenceExtras produces none of them (ignoring it left the suite green).
            configureExtras(changedExtras)
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Hands the stored Bluetooth wish (spec P2, the one carrier) to the router, at any time. No
     * `isSynchronized()` check: the router routes only while a session is synchronized, and
     * dropping a change made while disconnected - which the old check did - left the next session
     * routing the stale wish.
     */
    private fun applyBluetoothPreference() {
        if (mSettings.isBluetoothScoEnabled()) enableBluetoothSco() else disableBluetoothSco()
    }

    /**
     * The earpiece is the handset mode: routed there - chosen, or the default output - the screen
     * goes off at the ear; any other device, or no route at all, turns the sensor off again. The
     * superclass reports every change of the routed device, disconnects included.
     */
    override fun onAudioRouteChanged(type: Int?) {
        setProximitySensorOn(type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    private fun setProximitySensorOn(on: Boolean) {
        if (on) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity").also { it.acquire() }
        } else {
            mProximityLock?.release()
            mProximityLock = null
        }
    }

    override fun onMuteToggled() {
        val user = getSessionUser()
        if (isConnectionEstablished() && user != null) {
            val muted = !user.isSelfMuted()
            val deafened = user.isSelfDeafened() && muted
            setSelfMuteDeafState(muted, deafened)
        }
    }

    override fun onDeafenToggled() {
        val user = getSessionUser()
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened())
        }
    }

    override fun onOverlayToggled() {
        // Ditching the notification shade/panel to make the overlay permission request visible
        // used to happen here; Android 12 (API 31, our minSdk) no longer allows it.

        if (!mChannelOverlay.isShown()) {
            if (!android.provider.Settings.canDrawOverlays(applicationContext)) {
                val showSetting = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(showSetting)
                Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show()
                return
            }
            mChannelOverlay.show()
        } else {
            mChannelOverlay.hide()
        }
    }

    /**
     * The foreground notification's "Cancel reconnect". The same path as the app's dialog: the
     * session goes to Disconnected, which renders synchronously and leaves the foreground. A press
     * on a stale notification after the reconnect has already succeeded is a no-op, because
     * cancelReconnect only acts in ConnectionLost and Reconnecting.
     */
    override fun onReconnectCancelled() {
        cancelReconnect()
    }

    override fun onReconnectNotificationDismissed() {
        mErrorShown = true
    }

    override fun reconnect() {
        connect()
    }

    /**
     * The superclass first: it moves the session to Disconnected, which renders synchronously and
     * would post a prompt for the loss the user has just chosen to give up on. Hiding afterwards
     * removes that one as well as any earlier one.
     */
    override fun cancelReconnect() {
        super.cancelReconnect()
        mReconnectNotification?.let {
            it.hide()
            mReconnectNotification = null
        }
    }

    override fun setOverlayShown(showOverlay: Boolean) {
        if (!mChannelOverlay.isShown()) {
            mChannelOverlay.show()
        } else {
            mChannelOverlay.hide()
        }
    }

    override fun isOverlayShown(): Boolean = mChannelOverlay.isShown()

    override fun clearChatNotifications() {
        mMessageNotification.dismiss()
    }

    override fun markErrorShown() {
        mErrorShown = true
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        val notification = mReconnectNotification
        // No "unless reconnecting" any more: the prompt is only posted in Disconnected, or as the
        // fallback for a refused start, and neither is something to keep once acknowledged.
        if (notification != null) {
            notification.hide()
            mReconnectNotification = null
        }
    }

    override fun isErrorShown(): Boolean = mErrorShown

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    override fun onTalkKeyDown() {
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == mSettings.getInputMethod()) {
            // Idempotent, so no "unless already talking" (it had no observable, spec 4.04).
            if (!mSettings.isPushToTalkToggle()) {
                setTalkingState(true) // Start talking
            }
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    override fun onTalkKeyUp() {
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == mSettings.getInputMethod()) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking()) // Toggle talk state
            } else {
                setTalkingState(false) // Stop talking (idempotent)
            }
        }
    }

    override fun getMessageLog(): List<IChatMessage> = Collections.unmodifiableList(mMessageLog.snapshot())

    override fun clearMessageLog() {
        mMessageLog.clear()
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * **Chat notifications are NOT suppressed.** They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    override fun setSuppressNotifications(suppressNotifications: Boolean) {
        mSuppressNotifications = suppressNotifications
    }

    class MumlaBinder internal constructor(private val mService: MumlaService) : Binder() {
        fun getService(): IMumlaService = mService
    }

    override fun sendUserTextMessage(session: Int, message: String?): Message {
        val msg = super.sendUserTextMessage(session, message)

        mMessageLog.add(IChatMessage.TextMessage(msg))
        return msg
    }

    override fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message {
        val msg = super.sendChannelTextMessage(channel, message, tree)

        mMessageLog.add(IChatMessage.TextMessage(msg))
        return msg
    }

    companion object {
        private val TAG = MumlaService::class.java.name

        /** Undocumented constant that permits a proximity-sensing wake lock. */
        const val PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32
        const val TTS_THRESHOLD = 250 // Maximum number of characters to read
        const val RECONNECT_DELAY = 10000
    }
}
