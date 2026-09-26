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
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.HumlaService
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.util.Constants
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.chat.ChatMessageLog
import se.lublin.mumla.chat.IChatMessage
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.chat.outgoingMessageHtml
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver
import se.lublin.mumla.util.HtmlUtils
import se.lublin.mumla.util.changes
import se.lublin.mumla.util.collectEvents

/** [HumlaService] plus Mumla's notifications, overlay, hot corner, TTS and media session. */
class MumlaService : HumlaService(),
    MumlaConnectionNotification.OnActionListener,
    MumlaReconnectNotification.OnActionListener,
    IMumlaService {

    private lateinit var settings: Settings
    /** One per service life; its [MumlaConnectionNotification.isForeground] is the foreground state. */
    private lateinit var notification: MumlaConnectionNotification
    private lateinit var messageNotification: MumlaMessageNotification
    private var reconnectNotification: MumlaReconnectNotification? = null

    /** Headset / AVRCP media buttons while connected. */
    @VisibleForTesting
    internal var mediaSession: MumlaMediaSession? = null
        private set

    @VisibleForTesting
    internal lateinit var channelOverlay: MumlaOverlay

    /** Proximity lock, held while voice goes to the earpiece. */
    @VisibleForTesting
    internal var proximityLock: PowerManager.WakeLock? = null
        private set

    @VisibleForTesting
    internal var pttSoundEnabled = false
        private set

    @VisibleForTesting
    internal var shortTtsMessagesEnabled = false
        private set

    /** An error causing disconnection was dismissed by the user; a hint not to bother them again. */
    private var errorShown = false
    private val chatLog = ChatMessageLog()
    private val notices by lazy { NoticeFormatter(this) }
    private var suppressNotifications = false

    @VisibleForTesting
    internal var tts: TextToSpeech? = null
    private val ttsInitListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.ERROR) logWarning(getString(R.string.tts_failed))
    }

    @VisibleForTesting
    internal lateinit var hotCorner: MumlaHotCorner
    @VisibleForTesting
    internal val hotCornerListener = object : MumlaHotCorner.MumlaHotCornerListener {
        override fun onHotCornerDown() {
            onTalkKeyDown()
        }

        override fun onHotCornerUp() {
            onTalkKeyUp()
        }
    }

    private lateinit var talkReceiver: BroadcastReceiver

    /** Test seam: Robolectric's AudioManager records no sound effect. */
    internal var keyClickSound: () -> Unit = {
        (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    /**
     * Main.immediate: the session state is mutated on the main thread, so every transition is
     * rendered before the call that caused it returns.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun onEvent(event: HumlaEvent) {
        when (event) {
            is HumlaEvent.UserConnected -> requestAvatarIfMissing(event.user)
            is HumlaEvent.UserStateUpdated -> onUserStateUpdated(event.user)
            is HumlaEvent.UserTalkStateUpdated -> onUserTalkStateUpdated(event.user)
            is HumlaEvent.TextMessage -> onTextMessage(event.message)
            is HumlaEvent.Notice ->
                chatLog.add(IChatMessage.InfoMessage(infoType(event.level), notices.format(event)))
            is HumlaEvent.PermissionDenied ->
                if (notification.isForeground && !suppressNotifications) notification.show()
            else -> Unit
        }
    }

    private fun requestAvatarIfMissing(user: IUser) {
        if (user.textureHash != null && user.texture == null) {
            requestAvatar(user.session)
        }
    }

    private fun onUserStateUpdated(user: IUser) {
        val selfSession = try {
            sessionId
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in onUserStateUpdated: $e")
            return
        }

        if (user.session == selfSession) {
            settings.setMutedAndDeafened(user.isSelfMuted, user.isSelfDeafened)
            if (notification.isForeground) {
                val contentText = if (user.isSelfMuted && user.isSelfDeafened) {
                    getString(R.string.status_notify_muted_and_deafened)
                } else if (user.isSelfMuted) {
                    getString(R.string.status_notify_muted)
                } else {
                    getString(R.string.connected)
                }
                notification.customContentText = contentText
                notification.show()
            }
        }

        requestAvatarIfMissing(user)
    }

    private fun onTextMessage(message: IMessage) {
        val strippedMessage = HtmlUtils.toPlainText(message.message)
        val ttsMessage = if (shortTtsMessagesEnabled) {
            HtmlUtils.toPlainTextWithShortLinks(message.message) { host ->
                getString(R.string.chat_message_tts_short_link, host)
            }
        } else {
            strippedMessage
        }

        val sender = notices.senderName(message)
        val formattedTtsMessage = getString(R.string.notification_message, sender, ttsMessage)

        // tts is non-null exactly while the setting is on (the preference listener owns it).
        val tts = tts
        if (tts != null && formattedTtsMessage.length <= TTS_THRESHOLD && sessionUser?.isSelfDeafened == false) {
            @Suppress("DEPRECATION")
            tts.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null)
        }

        // Every message notifies while enabled; there is no per-sender filter yet.
        if (settings.isChatNotifyEnabled) {
            messageNotification.show(sender, strippedMessage, conversation())
        }

        chatLog.add(IChatMessage.TextMessage(message))
    }

    private fun onUserTalkStateUpdated(user: IUser) {
        var selfSession = -1
        try {
            selfSession = sessionId
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in onUserTalkStateUpdated: $e")
        }

        val selfStartedTalking = user.session == selfSession && user.talkState == TalkState.TALKING
        val pttClick = pttSoundEnabled && transmitMode == Constants.TRANSMIT_PUSH_TO_TALK
        if (pttClick && selfStartedTalking && isConnectionEstablished()) {
            keyClickSound()
        }
    }

    override fun onCreate() {
        super.onCreate()
        collectEvents(serviceScope, this, ::onEvent)

        settings = Settings.getInstance(this)
        pttSoundEnabled = settings.isPttSoundEnabled
        shortTtsMessagesEnabled = settings.isShortTextToSpeechMessagesEnabled
        serviceScope.launch {
            PreferenceManager.getDefaultSharedPreferences(this@MumlaService).changes(OBSERVED_KEYS)
                .collect(::onPreferenceChanged)
        }
        applyBluetoothPreference()

        // Overlay views need the theme set manually; the <application> theme does not apply.
        setTheme(R.style.Theme_Mumla)

        notification = MumlaConnectionNotification.create(this, "", this)
        messageNotification = MumlaMessageNotification(this@MumlaService)

        channelOverlay = MumlaOverlay(this)
        hotCorner = MumlaHotCorner(this, settings.hotCornerGravity, hotCornerListener)

        if (settings.isTextToSpeechEnabled) tts = TextToSpeech(this, ttsInitListener)

        talkReceiver = TalkBroadcastReceiver(this) { settings.isExternalPushToTalkAllowed }

        mediaSession = MumlaMediaSession(this, HumlaMediaKeyTarget(this), settings).also { it.attach(this) }

        serviceScope.launch { sessionState.collect { renderSessionState(it) } }
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
                reconnectNotification?.let {
                    it.hide()
                    reconnectNotification = null
                }
                errorShown = false
                showConnectionNotification(getString(R.string.mumlaConnecting) + torSuffix())
            }
            SessionState.Connected ->
                showConnectionNotification(getString(R.string.connected) + torSuffix(), actions = true)
            is SessionState.ConnectionLost, is SessionState.Reconnecting ->
                showConnectionNotification(getString(R.string.connection_lost_reconnecting), cancelReconnect = true)
            is SessionState.Disconnected -> {
                notification.hide()
                // Not in onConnectionDisconnected: the log survives a ConnectionLost.
                clearMessageLog()
                messageNotification.dismiss()
                val error = state.error
                if (error != null && !suppressNotifications) {
                    reconnectNotification?.hide()
                    reconnectNotification =
                        MumlaReconnectNotification.show(this, error.message + torSuffix(), false, this)
                }
            }
        }
    }

    private fun torSuffix(): String = if (settings.isTorEnabled) " (Tor)" else ""

    private fun showConnectionNotification(
        contentText: String,
        actions: Boolean = false,
        cancelReconnect: Boolean = false,
    ) {
        notification.customContentText = contentText
        notification.actionsShown = actions
        notification.cancelReconnectShown = cancelReconnect
        if (!notification.show()) {
            // The platform refused the foreground start; warn once while the refusal repeats.
            logWarningOnce(getString(R.string.foreground_start_failed))
            if (!suppressNotifications) {
                reconnectNotification?.hide()
                reconnectNotification = MumlaReconnectNotification.show(
                    this, getString(R.string.foreground_start_failed), false, this,
                )
            }
        }
    }

    /** Our name, the channel a reply goes to and the server, for the chat notification. */
    private fun conversation() = MumlaMessageNotification.Conversation(
        self = orNullOutsideSession { sessionUser }?.name,
        channel = orNullOutsideSession { sessionChannel }?.name,
        server = targetServer?.let { it.name.ifEmpty { it.host } },
    )

    /** [read]'s result, or null where it needs a synchronized session and there is none. */
    private fun <T> orNullOutsideSession(read: () -> T?): T? = try {
        read()
    } catch (e: IllegalStateException) {
        Log.d(TAG, "no session: $e")
        null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MumlaMessageNotification.ACTION_REPLY) onChatReply(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Sends the chat notification's inline reply to our channel. The notification is re-posted in
     * any case, or it would keep waiting for the send; without a session it is removed instead.
     */
    private fun onChatReply(intent: Intent) {
        val reply = MumlaMessageNotification.replyText(intent)?.trim()
        val channel = orNullOutsideSession { sessionChannel }
        if (channel == null) {
            messageNotification.dismiss()
            return
        }
        if (reply.isNullOrEmpty()) {
            messageNotification.refresh()
            return
        }
        sendChannelTextMessage(channel.id, outgoingMessageHtml(reply, settings.isMarkdownEnabled), false)
        messageNotification.showReply(reply, conversation())
    }

    override fun onBind(intent: Intent?): IBinder = MumlaBinder(this)

    override fun onDestroy() {
        // Stop rendering first: super.onDestroy() disconnects, and nothing may enter the foreground now.
        serviceScope.cancel()
        notification.hide()
        reconnectNotification?.let {
            it.hide()
            reconnectNotification = null
        }
        try {
            unregisterReceiver(talkReceiver)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }

        // Null-checked: built last in onCreate, so an earlier throw leaves it null.
        mediaSession?.detach()
        tts?.shutdown()
        messageNotification.dismiss()
        setProximitySensorOn(false)
        super.onDestroy()
    }

    override fun onConnectionSynchronized() {
        // TODO? The superclass sometimes throws IllegalStateException (not synchronized) here,
        //  presumably because connect()/disconnect() ran again between messageServerSync() and
        //  this posted callback.
        try {
            super.onConnectionSynchronized()
        } catch (e: RuntimeException) {
            Log.d(TAG, "exception in onConnectionSynchronized: $e")
            return
        }

        if (settings.isMuted || settings.isDeafened) {
            setSelfMuteDeafState(settings.isMuted, settings.isDeafened)
        }

        // Bluetooth is not restored here: applyBluetoothPreference hands the wish to the router.
        ContextCompat.registerReceiver(
            this, talkReceiver,
            IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), ContextCompat.RECEIVER_EXPORTED,
        )

        if (settings.isHotCornerEnabled) {
            hotCorner.isShown = true
        }
        // The proximity sensor follows the earpiece route (onAudioRouteChanged), not this hook.
    }

    override fun onConnectionDisconnected(e: HumlaException?) {
        super.onConnectionDisconnected(e)
        try {
            unregisterReceiver(talkReceiver)
        } catch (iae: IllegalArgumentException) {
        }

        channelOverlay.hide()

        hotCorner.isShown = false

        setProximitySensorOn(false)
    }

    internal fun onPreferenceChanged(key: String) {
        when (key) {
            Settings.PREF_INPUT_METHOD ->
                channelOverlay.setPushToTalkShown(settings.humlaInputMethod == Constants.TRANSMIT_PUSH_TO_TALK)
            Settings.PREF_HOT_CORNER_KEY -> {
                hotCorner.gravity = settings.hotCornerGravity
                hotCorner.isShown = isConnectionEstablished() && settings.isHotCornerEnabled
            }
            Settings.PREF_USE_TTS -> applyTextToSpeechPreference()
            Settings.PREF_SHORT_TTS_MESSAGES ->
                shortTtsMessagesEnabled = settings.isShortTextToSpeechMessagesEnabled
            Settings.PREF_PTT_SOUND ->
                pttSoundEnabled = settings.isPttSoundEnabled
            Settings.PREF_BLUETOOTH_SCO -> applyBluetoothPreference()
        }
        if (key in SessionSettings.AUDIO_KEYS) {
            // The result is ignored: audio settings never require a reconnect.
            configure(SessionSettings.withAudioSettings(sessionConfig, settings))
        }

        if (key in RECONNECT_KEYS && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyTextToSpeechPreference() {
        val current = tts
        if (current == null && settings.isTextToSpeechEnabled) {
            tts = TextToSpeech(this, ttsInitListener)
        } else if (current != null && !settings.isTextToSpeechEnabled) {
            current.shutdown()
            tts = null
        }
    }

    /**
     * Hands the stored Bluetooth wish to the router at any time, connected or not; the router only
     * routes while a session is synchronized.
     */
    private fun applyBluetoothPreference() {
        if (settings.isBluetoothScoEnabled) enableBluetoothSco() else disableBluetoothSco()
    }

    /** Earpiece route (chosen or default) turns the proximity sensor on; anything else turns it off. */
    override fun onAudioRouteChanged(type: Int?) = applyAudioRoute(type)

    @VisibleForTesting
    internal fun applyAudioRoute(type: Int?) {
        setProximitySensorOn(type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    private fun setProximitySensorOn(on: Boolean) {
        if (on) {
            if (proximityLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity")
                .also { it.acquire() }
        } else {
            proximityLock?.release()
            proximityLock = null
        }
    }

    override fun onMuteToggled() {
        val user = sessionUser
        if (isConnectionEstablished() && user != null) {
            val muted = !user.isSelfMuted
            val deafened = user.isSelfDeafened && muted
            setSelfMuteDeafState(muted, deafened)
        }
    }

    override fun onDeafenToggled() {
        val user = sessionUser
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened, !user.isSelfDeafened)
        }
    }

    override fun onOverlayToggled() {
        if (!channelOverlay.isShown) {
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
            channelOverlay.show()
        } else {
            channelOverlay.hide()
        }
    }

    /** A press on a stale notification after the reconnect succeeded is a no-op. */
    override fun onReconnectCancelled() {
        cancelReconnect()
    }

    override fun onReconnectNotificationDismissed() {
        errorShown = true
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
        reconnectNotification?.let {
            it.hide()
            reconnectNotification = null
        }
    }

    override val isOverlayShown: Boolean get() = channelOverlay.isShown

    override fun clearChatNotifications() {
        messageNotification.dismiss()
    }

    override fun markErrorShown() {
        errorShown = true
        val notification = reconnectNotification
        // The prompt only exists in Disconnected or for a refused start; drop it once acknowledged.
        if (notification != null) {
            notification.hide()
            reconnectNotification = null
        }
    }

    override val isErrorShown: Boolean get() = errorShown

    /** Talk key pressed; a no-op in toggle PTT mode, which acts on key up. */
    override fun onTalkKeyDown() {
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == settings.inputMethod) {
            if (!settings.isPushToTalkToggle) {
                setTalkingState(true) // Start talking
            }
        }
    }

    /** Talk key released; toggles talking in toggle PTT mode, otherwise stops talking. */
    override fun onTalkKeyUp() {
        if (isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT == settings.inputMethod) {
            if (settings.isPushToTalkToggle) {
                setTalkingState(!isTalking) // Toggle talk state
            } else {
                setTalkingState(false) // Stop talking (idempotent)
            }
        }
    }

    override val messageLog: StateFlow<List<IChatMessage>> get() = chatLog.messages

    override fun clearMessageLog() {
        chatLog.clear()
    }

    /**
     * Suppresses connection notifications, typically while the main activity is in the foreground.
     * Chat notifications are not suppressed.
     */
    override fun setSuppressNotifications(suppressNotifications: Boolean) {
        this.suppressNotifications = suppressNotifications
    }

    class MumlaBinder internal constructor(val service: IMumlaService) : Binder()

    override fun sendUserTextMessage(session: Int, message: String): Message {
        val msg = super.sendUserTextMessage(session, message)

        chatLog.add(IChatMessage.TextMessage(msg))
        return msg
    }

    override fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message {
        val msg = super.sendChannelTextMessage(channel, message, tree)

        chatLog.add(IChatMessage.TextMessage(msg))
        return msg
    }

    companion object {
        private val TAG = MumlaService::class.java.name

        private fun infoType(level: HumlaEvent.Level) = when (level) {
            HumlaEvent.Level.INFO -> IChatMessage.InfoMessage.Type.INFO
            HumlaEvent.Level.WARNING -> IChatMessage.InfoMessage.Type.WARNING
            HumlaEvent.Level.ERROR -> IChatMessage.InfoMessage.Type.ERROR
        }

        const val TTS_THRESHOLD = 250 // Maximum number of characters to read

        /** The settings a connection is made with; a change applies from the next one. */
        private val RECONNECT_KEYS = setOf(Settings.PREF_CERT_ID, Settings.PREF_FORCE_TCP, Settings.PREF_USE_TOR)

        /** The preferences [onPreferenceChanged] reacts to. */
        private val OBSERVED_KEYS = SessionSettings.AUDIO_KEYS + RECONNECT_KEYS + setOf(
            Settings.PREF_INPUT_METHOD,
            Settings.PREF_HOT_CORNER_KEY,
            Settings.PREF_USE_TTS,
            Settings.PREF_SHORT_TTS_MESSAGES,
            Settings.PREF_PTT_SOUND,
            Settings.PREF_BLUETOOTH_SCO,
        )
    }
}
