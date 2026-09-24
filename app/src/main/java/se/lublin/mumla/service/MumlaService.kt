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

/** [HumlaService] plus Mumla's notifications, overlay, hot corner, TTS and media session. */
class MumlaService : HumlaService(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MumlaConnectionNotification.OnActionListener,
    MumlaReconnectNotification.OnActionListener,
    IMumlaService {

    private lateinit var mSettings: Settings
    /** One per service life; its [MumlaConnectionNotification.isForeground] is the foreground state. */
    private lateinit var mNotification: MumlaConnectionNotification
    private lateinit var mMessageNotification: MumlaMessageNotification
    private var mReconnectNotification: MumlaReconnectNotification? = null

    /** Headset / AVRCP media buttons while connected. */
    private var mMediaSession: MumlaMediaSession? = null

    private lateinit var mChannelOverlay: MumlaOverlay

    /** Proximity lock, held while voice goes to the earpiece. */
    private var mProximityLock: PowerManager.WakeLock? = null

    private var mPTTSoundEnabled = false

    private var mShortTtsMessagesEnabled = false

    /** An error causing disconnection was dismissed by the user; a hint not to bother them again. */
    private var mErrorShown = false
    private val mMessageLog = ChatMessageLog()
    private var mSuppressNotifications = false

    private var mTTS: TextToSpeech? = null
    private val mTTSInitListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.ERROR) logWarning(getString(R.string.tts_failed))
    }

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

    /** Test seam: Robolectric's AudioManager records no sound effect. */
    internal var keyClickSound: () -> Unit = {
        (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    /**
     * Main.immediate: the session state is mutated on the main thread, so every transition is
     * rendered before the call that caused it returns.
     */
    private val mServiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mObserver = object : HumlaObserver() {
        override fun onUserConnected(user: IUser) {
            if (user.getTextureHash() != null && user.getTexture() == null) {
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
                requestAvatar(user.getSession())
            }
        }

        override fun onMessageLogged(message: IMessage) {
            val strippedMessage = HtmlUtils.toPlainText(message.getMessage())
            val ttsMessage = if (mShortTtsMessagesEnabled) {
                HtmlUtils.toPlainTextWithShortLinks(message.getMessage()) { host ->
                    getString(R.string.chat_message_tts_short_link, host)
                }
            } else {
                strippedMessage
            }

            val formattedTtsMessage = getString(R.string.notification_message, message.getActorName(), ttsMessage)

            // mTTS is non-null exactly while the setting is on (the preference listener owns it).
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
                mMessageNotification.show(message.getActorName(), strippedMessage)
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

        mSettings = Settings.getInstance(this)
        mPTTSoundEnabled = mSettings.isPttSoundEnabled()
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        applyBluetoothPreference()

        // Overlay views need the theme set manually; the <application> theme does not apply.
        setTheme(R.style.Theme_Mumla)

        mNotification = MumlaConnectionNotification.create(this, "", this)
        mMessageNotification = MumlaMessageNotification(this@MumlaService)

        mChannelOverlay = MumlaOverlay(this)
        mHotCorner = MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener)

        if (mSettings.isTextToSpeechEnabled()) mTTS = TextToSpeech(this, mTTSInitListener)

        mTalkReceiver = TalkBroadcastReceiver(this) { mSettings.isExternalPushToTalkAllowed() }

        mMediaSession = MumlaMediaSession(this, HumlaMediaKeyTarget(this), mSettings).also { it.attach(this) }

        mServiceScope.launch { getSessionState().collect { renderSessionState(it) } }
    }

    /**
     * Renders the session state into the foreground notification; the only place that decides
     * whether the service is in the foreground. `Connecting` enters it and only `Disconnected`
     * leaves it: a reconnect restarting it from the background is refused on Android 12+, which
     * would leave the microphone dead.
     */
    internal fun renderSessionState(state: SessionState) {
        when (state) {
            SessionState.Connecting -> {
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
                // Not in onConnectionDisconnected: the log survives a ConnectionLost.
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
            // The platform refused the foreground start; warn once while the refusal repeats.
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
        // Stop rendering first: super.onDestroy() disconnects, and nothing may enter the foreground now.
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

        // Null-checked: built last in onCreate, so an earlier throw leaves it null.
        mMediaSession?.detach(this)
        unregisterObserver(mObserver)
        mTTS?.shutdown()
        mMessageNotification.dismiss()
        setProximitySensorOn(false)
        super.onDestroy()
    }

    override fun onConnectionSynchronized() {
        // TODO? The superclass sometimes throws NotSynchronizedException (rethrown as
        //  RuntimeException) here, presumably because connect()/disconnect() ran again between
        //  messageServerSync() and this posted callback.
        try {
            super.onConnectionSynchronized()
        } catch (e: RuntimeException) {
            Log.d(TAG, "exception in onConnectionSynchronized: $e")
            return
        }

        if (mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened())
        }

        // Bluetooth is not restored here: applyBluetoothPreference hands the wish to the router.
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

        mChannelOverlay.hide()

        mHotCorner.setShown(false)

        setProximitySensorOn(false)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Audio extras are derived in AudioPreferenceExtras; the cases below have other effects.
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
                requiresReconnect = true
        }
        if (changedExtras.size() > 0) {
            // The result is ignored: audio extras never require a reconnect.
            configureExtras(changedExtras)
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Hands the stored Bluetooth wish to the router at any time, connected or not; the router only
     * routes while a session is synchronized.
     */
    private fun applyBluetoothPreference() {
        if (mSettings.isBluetoothScoEnabled()) enableBluetoothSco() else disableBluetoothSco()
    }

    /** Earpiece route (chosen or default) turns the proximity sensor on; anything else turns it off. */
    override fun onAudioRouteChanged(type: Int?) {
        setProximitySensorOn(type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    private fun setProximitySensorOn(on: Boolean) {
        if (on) {
            if (mProximityLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            mProximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity")
                .also { it.acquire() }
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

    /** A press on a stale notification after the reconnect succeeded is a no-op. */
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
     * The superclass first: its Disconnected render would post a prompt for the loss the user just
     * gave up on; hiding afterwards removes that one too.
     */
    override fun cancelReconnect() {
        super.cancelReconnect()
        mReconnectNotification?.let {
            it.hide()
            mReconnectNotification = null
        }
    }

    override fun isOverlayShown(): Boolean = mChannelOverlay.isShown()

    override fun clearChatNotifications() {
        mMessageNotification.dismiss()
    }

    override fun markErrorShown() {
        mErrorShown = true
        val notification = mReconnectNotification
        // The prompt only exists in Disconnected or for a refused start; drop it once acknowledged.
        if (notification != null) {
            notification.hide()
            mReconnectNotification = null
        }
    }

    override fun isErrorShown(): Boolean = mErrorShown

    /** Talk key pressed; a no-op in toggle PTT mode, which acts on key up. */
    override fun onTalkKeyDown() {
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == mSettings.getInputMethod()) {
            if (!mSettings.isPushToTalkToggle()) {
                setTalkingState(true) // Start talking
            }
        }
    }

    /** Talk key released; toggles talking in toggle PTT mode, otherwise stops talking. */
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
     * Suppresses connection notifications, typically while the main activity is in the foreground.
     * Chat notifications are not suppressed.
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

        const val TTS_THRESHOLD = 250 // Maximum number of characters to read
    }
}
