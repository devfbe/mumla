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
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver
import se.lublin.mumla.util.HtmlUtils
import java.util.Collections

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
    private var mNotification: MumlaConnectionNotification? = null
    private lateinit var mMessageNotification: MumlaMessageNotification
    private var mReconnectNotification: MumlaReconnectNotification? = null

    /** Headset / AVRCP media buttons while connected (stream P). */
    private var mMediaSession: MumlaMediaSession? = null

    /** Channel view overlay. */
    private lateinit var mChannelOverlay: MumlaOverlay

    /** Proximity lock for handset mode. */
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
    private var mMessageLog: MutableList<IChatMessage>? = null
    private var mSuppressNotifications = false

    /** Set once a device has refused to route SCO, so the chat log says it once and not per reconnect. */
    private var mBluetoothScoRefused = false

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

    private val mObserver = object : HumlaObserver() {
        override fun onConnecting() {
            // Remove old notification left from reconnect,
            mReconnectNotification?.let {
                it.hide()
                mReconnectNotification = null
            }

            val tor = if (mSettings.isTorEnabled()) " (Tor)" else ""
            mNotification = MumlaConnectionNotification.create(
                this@MumlaService,
                getString(R.string.mumlaConnecting) + tor,
                this@MumlaService,
            ).also { it.show() }

            mErrorShown = false
        }

        override fun onConnected() {
            mNotification?.let {
                val tor = if (mSettings.isTorEnabled()) " (Tor)" else ""
                it.customContentText = getString(R.string.connected) + tor
                it.actionsShown = true
                it.show()
            }
        }

        override fun onDisconnected(e: HumlaException?) {
            mNotification?.let {
                it.hide()
                mNotification = null
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification = MumlaReconnectNotification.show(
                    this@MumlaService,
                    e.message + (if (mSettings.isTorEnabled()) " (Tor)" else ""),
                    isReconnecting(),
                    this@MumlaService,
                )
            }
        }

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
                mNotification?.let {
                    val contentText = if (user.isSelfMuted() && user.isSelfDeafened()) {
                        getString(R.string.status_notify_muted_and_deafened)
                    } else if (user.isSelfMuted()) {
                        getString(R.string.status_notify_muted)
                    } else {
                        getString(R.string.connected)
                    }
                    it.customContentText = contentText
                    it.show()
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

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            val tts = mTTS
            if (mSettings.isTextToSpeechEnabled() &&
                tts != null &&
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

            mMessageLog!!.add(IChatMessage.TextMessage(message))
        }

        override fun onLogInfo(message: String) {
            mMessageLog!!.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message))
        }

        override fun onLogWarning(message: String) {
            mMessageLog!!.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message))
        }

        override fun onLogError(message: String) {
            mMessageLog!!.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message))
        }

        override fun onPermissionDenied(reason: String?) {
            val notification = mNotification
            if (notification != null && !mSuppressNotifications) {
                notification.show()
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
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
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

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla)

        mMessageLog = ArrayList()
        mMessageNotification = MumlaMessageNotification(this@MumlaService)

        // Instantiate overlay view
        mChannelOverlay = MumlaOverlay(this)
        mHotCorner = MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener)

        // Set up TTS
        if (mSettings.isTextToSpeechEnabled()) mTTS = TextToSpeech(this, mTTSInitListener)

        mTalkReceiver = TalkBroadcastReceiver(this)

        mMediaSession = MumlaMediaSession(this, HumlaMediaKeyTarget(this), mSettings).also { it.attach(this) }
    }

    override fun onBind(intent: Intent?): IBinder = MumlaBinder(this)

    override fun onDestroy() {
        mNotification?.let {
            it.hide()
            mNotification = null
        }
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
        mMessageLog = null
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

        // The Bluetooth headset is a stored wish, not a live state (spec P2): SCO is torn down
        // by onConnectionDisconnected on every dropped connection, auto-reconnect included, so
        // this is where it comes back. It sits beside the other restore and ahead of the overlay
        // and sensor work on purpose: WindowManager.addView and the proximity wake lock can both
        // throw, and anything that throws in front of this line reproduces the complaint this
        // task exists to close.
        if (mSettings.isBluetoothScoEnabled()) {
            applyBluetoothSco(true)
        }

        ContextCompat.registerReceiver(
            this, mTalkReceiver,
            IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), ContextCompat.RECEIVER_EXPORTED,
        )

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true)
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true)
        }
    }

    /**
     * Start or stop the headset link, and survive a device that enforces BLUETOOTH_CONNECT on
     * android.media although the platform's own annotation database declares it on
     * android.bluetooth.* only (spec 4.1: keep asking, stop gating). Absent from the database is
     * not "never thrown anywhere", so the call is wrapped rather than trusted.
     *
     * Reported once per service lifetime: this runs on every synchronization, and auto-reconnect
     * can run it many times over one broken network. A chat log that repeats the same line after
     * every reconnect buries the message it is trying to deliver.
     */
    private fun applyBluetoothSco(wanted: Boolean) {
        try {
            if (wanted) {
                enableBluetoothSco()
            } else {
                disableBluetoothSco()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "bluetooth sco refused by the platform: $e")
            if (!mBluetoothScoRefused) {
                mBluetoothScoRefused = true
                logWarning(getString(R.string.bluetooth_sco_refused))
            }
        }
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

        clearMessageLog()
        mMessageNotification.dismiss()
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
            Settings.PREF_HANDSET_MODE ->
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode())
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
            Settings.PREF_BLUETOOTH_SCO ->
                if (isSynchronized()) {
                    applyBluetoothSco(mSettings.isBluetoothScoEnabled())
                }
            Settings.PREF_CERT_ID,
            Settings.PREF_FORCE_TCP,
            Settings.PREF_USE_TOR,
            Settings.PREF_DISABLE_OPUS,
            ->
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true
        }
        if (changedExtras.size() > 0) {
            // Reconfigure the service appropriately.
            requiresReconnect = requiresReconnect or configureExtras(changedExtras)
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show()
        }
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

    override fun onReconnectNotificationDismissed() {
        mErrorShown = true
    }

    override fun reconnect() {
        connect()
    }

    override fun cancelReconnect() {
        mReconnectNotification?.let {
            it.hide()
            mReconnectNotification = null
        }
        super.cancelReconnect()
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
        if (notification != null && !isReconnecting()) {
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
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
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
            } else if (isTalking()) {
                setTalkingState(false) // Stop talking
            }
        }
    }

    override fun getMessageLog(): List<IChatMessage> = Collections.unmodifiableList(mMessageLog!!)

    override fun clearMessageLog() {
        mMessageLog?.clear()
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

        mMessageLog!!.add(IChatMessage.TextMessage(msg))
        return msg
    }

    override fun sendChannelTextMessage(channel: Int, message: String?, tree: Boolean): Message {
        val msg = super.sendChannelTextMessage(channel, message, tree)

        mMessageLog!!.add(IChatMessage.TextMessage(msg))
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
