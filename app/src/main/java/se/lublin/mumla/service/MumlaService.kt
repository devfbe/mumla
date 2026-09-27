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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.model.Message
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.chat.outgoingMessageHtml
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver
import se.lublin.mumla.session.PushToTalk
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.session.isConnected
import se.lublin.mumla.util.HtmlUtils
import se.lublin.mumla.util.changes
import se.lublin.mumla.util.collectEvents

/**
 * The foreground service that anchors a session while the app is in the background: it holds the
 * microphone foreground notification and the media session, and hosts what a session shows outside
 * the app (overlay, hot corner, chat notifications, text-to-speech, the reconnect prompt). It
 * observes the [SessionManager]'s current session and stops itself once none is active.
 */
@Suppress("TooManyFunctions") // Framework and notification callbacks, each delegating.
class MumlaService :
    Service(),
    MumlaConnectionNotification.OnActionListener,
    MumlaReconnectNotification.OnActionListener {

    private lateinit var sessions: SessionManager
    private lateinit var settings: Settings
    private lateinit var pushToTalk: PushToTalk

    /** One per service life; its [MumlaConnectionNotification.isForeground] is the foreground state. */
    private lateinit var notification: MumlaConnectionNotification
    private lateinit var messageNotification: MumlaMessageNotification
    private lateinit var reconnectPrompt: MumlaReconnectNotification
    private var reconnectPromptShown = false

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
    internal var tts: TextToSpeech? = null
    private val ttsInitListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.ERROR) sessions.chat.warnOnce(getString(R.string.tts_failed))
    }

    @VisibleForTesting
    internal lateinit var hotCorner: MumlaHotCorner

    @VisibleForTesting
    internal val hotCornerListener = object : MumlaHotCorner.MumlaHotCornerListener {
        override fun onHotCornerDown() = pushToTalk.onKeyDown()

        override fun onHotCornerUp() = pushToTalk.onKeyUp()
    }

    private lateinit var talkReceiver: TalkBroadcastReceiver
    private var talkReceiverRegistered = false
    private val notices by lazy { NoticeFormatter(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val stopIfIdle = Runnable { if (!sessions.isActive && !reconnectPromptShown) stopSelf() }

    /** Test seam: Robolectric's AudioManager records no sound effect. */
    internal var keyClickSound: () -> Unit = {
        getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    /**
     * Main.immediate: the session state is mutated on the main thread, so every transition is
     * rendered before the call that caused it returns.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        sessions = SessionManager.get(this)
        settings = Settings.getInstance(this)
        pushToTalk = PushToTalk(settings, sessions)
        // Overlay views need the theme set manually; the <application> theme does not apply.
        setTheme(R.style.Theme_Mumla)

        notification = MumlaConnectionNotification.create(this, "", this)
        messageNotification = MumlaMessageNotification(this)
        reconnectPrompt = MumlaReconnectNotification(this, this)
        channelOverlay = MumlaOverlay(this, sessions)
        hotCorner = MumlaHotCorner(this, settings.hotCornerGravity, hotCornerListener)
        if (settings.isTextToSpeechEnabled) tts = TextToSpeech(this, ttsInitListener)
        talkReceiver = TalkBroadcastReceiver(sessions) { settings.isExternalPushToTalkAllowed }
        mediaSession = MumlaMediaSession(this, SessionMediaKeyTarget(sessions), settings).also {
            it.attach(sessions.state)
        }

        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            PreferenceManager.getDefaultSharedPreferences(this@MumlaService).changes(OBSERVED_KEYS)
                .collect(::onPreferenceChanged)
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.errorShown.collect { shown -> if (shown) hideReconnectPrompt() }
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.appVisible.collect { visible -> if (visible) messageNotification.dismiss() }
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.session.collectLatest { session -> if (session != null) follow(session) }
        }
    }

    /** Renders [session] until it is replaced. */
    private suspend fun follow(session: IHumlaSession): Unit = coroutineScope {
        collectEvents(this, session) { onEvent(session, it) }
        launch(start = CoroutineStart.UNDISPATCHED) { session.audio.route.drop(1).collect(::applyAudioRoute) }
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.map { it == SessionState.Connected }.distinctUntilChanged().collectLatest { connected ->
                // Changes only: the state found at synchronization is the one onSynchronized restores.
                if (connected) {
                    session.model.map { model -> model?.self?.let { it.isSelfMuted to it.isSelfDeafened } }
                        .filterNotNull().distinctUntilChanged().drop(1)
                        .collect { (muted, deafened) -> onSelfMuteChanged(muted, deafened) }
                }
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            val self = session.model.map { it?.selfSession }.distinctUntilChanged()
            combine(self, session.talkStates) { id, states -> id != null && states[id] == TalkState.TALKING }
                .distinctUntilChanged().drop(1)
                .collect { talking -> if (talking) onStartedTalking(session) }
        }
        var previous: SessionState? = null
        session.state.collect { state ->
            renderSessionState(session, state)
            if (state == SessionState.Connected) {
                onSynchronized(session)
            } else if (previous == SessionState.Connected) {
                onConnectionEnded()
            }
            previous = state
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MumlaMessageNotification.ACTION_REPLY) onChatReply(intent)
        handler.post(stopIfIdle)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Stop rendering first: nothing may enter the foreground now.
        serviceScope.cancel()
        handler.removeCallbacks(stopIfIdle)
        notification.hide()
        reconnectPrompt.hide()
        unregisterTalkReceiver()
        // Null-checked: built last in onCreate, so an earlier throw leaves it null.
        mediaSession?.detach()
        tts?.shutdown()
        messageNotification.dismiss()
        setProximitySensorOn(false)
        channelOverlay.hide()
        hotCorner.isShown = false
        // A session must not outlive its foreground service: without it the microphone is dead
        // in the background.
        if (sessions.isActive) sessions.disconnect()
        super.onDestroy()
    }

    /**
     * Renders the session state into the foreground notification; the only place that decides
     * whether the service is in the foreground. `Connecting` enters it and only `Disconnected`
     * leaves it: a reconnect restarting it from the background is refused on Android 12+, which
     * would leave the microphone dead.
     */
    private fun renderSessionState(session: IHumlaSession, state: SessionState) {
        // A replaced session may still report its end before its observer is cancelled.
        if (sessions.session.value !== session) return
        when (state) {
            SessionState.Connecting -> {
                hideReconnectPrompt()
                showConnectionNotification(getString(R.string.mumlaConnecting) + torSuffix())
            }
            SessionState.Connected -> {
                val self = session.model.value?.self
                notification.muted = self?.isSelfMuted == true
                notification.deafened = self?.isSelfDeafened == true
                showConnectionNotification(connectedText(), actions = true)
            }
            is SessionState.ConnectionLost, is SessionState.Reconnecting ->
                showConnectionNotification(getString(R.string.connection_lost_reconnecting), cancelReconnect = true)
            is SessionState.Disconnected -> {
                notification.hide()
                messageNotification.dismiss()
                val reason = state.reason?.takeIf { it.isReported }
                if (reason != null && !sessions.appVisible.value && !sessions.errorShown.value) {
                    showReconnectPrompt(notices.disconnectReason(reason) + torSuffix())
                }
                // Posted: a connect that replaces this session is still under way on this turn.
                handler.post(stopIfIdle)
            }
        }
    }

    /** A certificate problem is the app's own dialog to show, not a reason to reconnect. */
    private val DisconnectReason.isReported: Boolean
        get() = this !is DisconnectReason.TlsUntrusted && this !is DisconnectReason.TlsCertificateChanged

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
            sessions.chat.warnOnce(getString(R.string.foreground_start_failed))
            if (!sessions.appVisible.value) showReconnectPrompt(getString(R.string.foreground_start_failed))
        }
    }

    private fun showReconnectPrompt(text: String) {
        reconnectPrompt.show(text)
        reconnectPromptShown = true
    }

    private fun hideReconnectPrompt() {
        reconnectPrompt.hide()
        if (reconnectPromptShown) {
            reconnectPromptShown = false
            handler.post(stopIfIdle)
        }
    }

    private fun onEvent(session: IHumlaSession, event: HumlaEvent) {
        when (event) {
            is HumlaEvent.TextMessage -> onTextMessage(session, event.message)
            is HumlaEvent.PermissionDenied ->
                if (notification.isForeground && !sessions.appVisible.value) notification.show()
            else -> Unit
        }
    }

    private fun onSelfMuteChanged(muted: Boolean, deafened: Boolean) {
        settings.setMutedAndDeafened(muted, deafened)
        if (notification.isForeground) {
            notification.muted = muted
            notification.deafened = deafened
            notification.customContentText = connectedText()
            notification.show()
        }
    }

    private fun connectedText(): String = when {
        notification.muted && notification.deafened -> getString(R.string.status_notify_muted_and_deafened)
        notification.muted -> getString(R.string.status_notify_muted)
        else -> getString(R.string.connected) + torSuffix()
    }

    private fun onTextMessage(session: IHumlaSession, message: Message) {
        val strippedMessage = HtmlUtils.toPlainText(message.message)
        val ttsMessage = if (settings.isShortTextToSpeechMessagesEnabled) {
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
        val deafened = session.model.value?.self?.isSelfDeafened
        if (tts != null && formattedTtsMessage.length <= TTS_THRESHOLD && deafened == false) {
            @Suppress("DEPRECATION")
            tts.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null)
        }

        // Every message notifies while enabled; there is no per-sender filter yet.
        if (settings.isChatNotifyEnabled) messageNotification.show(sender, strippedMessage, conversation(session))
    }

    private fun onStartedTalking(session: IHumlaSession) {
        val pttClick = settings.isPttSoundEnabled && session.audio.transmitMode == TransmitMode.PUSH_TO_TALK
        if (pttClick && session.isConnected) keyClickSound()
    }

    /** The session synchronized, the first time or again after a reconnect. */
    private fun onSynchronized(session: IHumlaSession) {
        if (settings.isMuted || settings.isDeafened) {
            session.actions.setSelfMuteDeafState(settings.isMuted, settings.isDeafened)
        }
        if (!talkReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, talkReceiver,
                IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), ContextCompat.RECEIVER_EXPORTED,
            )
            talkReceiverRegistered = true
        }
        if (settings.isHotCornerEnabled) hotCorner.isShown = true
        // The proximity sensor follows the earpiece route (applyAudioRoute), not this hook.
    }

    /** The synchronized connection ended, for good or for a reconnect. */
    private fun onConnectionEnded() {
        unregisterTalkReceiver()
        channelOverlay.hide()
        hotCorner.isShown = false
        setProximitySensorOn(false)
    }

    private fun unregisterTalkReceiver() {
        if (!talkReceiverRegistered) return
        unregisterReceiver(talkReceiver)
        talkReceiverRegistered = false
    }

    /** Our name, the channel a reply goes to and the server, for the chat notification. */
    private fun conversation(session: IHumlaSession): MumlaMessageNotification.Conversation {
        val model = session.model.value
        return MumlaMessageNotification.Conversation(
            self = model?.self?.name,
            channel = model?.selfChannel?.name,
            server = session.targetServer?.let { it.name.ifEmpty { it.host } },
        )
    }

    /**
     * Sends the chat notification's inline reply to our channel. The notification is re-posted in
     * any case, or it would keep waiting for the send; without a session it is removed instead.
     */
    private fun onChatReply(intent: Intent) {
        val reply = MumlaMessageNotification.replyText(intent)?.trim()
        val session = sessions.connected
        val channel = session?.model?.value?.selfChannel
        if (session == null || channel == null) {
            messageNotification.dismiss()
            return
        }
        if (reply.isNullOrEmpty()) {
            messageNotification.refresh()
            return
        }
        val html = outgoingMessageHtml(reply, settings.isMarkdownEnabled)
        session.actions.sendChannelTextMessage(channel.id, html, false)
        messageNotification.showReply(reply, conversation(session))
    }

    @VisibleForTesting
    internal fun onPreferenceChanged(key: String) {
        when (key) {
            Settings.INPUT_METHOD.key ->
                channelOverlay.setPushToTalkShown(settings.transmitMode == TransmitMode.PUSH_TO_TALK)
            Settings.HOT_CORNER.key -> {
                hotCorner.gravity = settings.hotCornerGravity
                hotCorner.isShown = sessions.connected != null && settings.isHotCornerEnabled
            }
            Settings.USE_TTS.key -> applyTextToSpeechPreference()
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

    /** Earpiece route (chosen or default) turns the proximity sensor on; anything else turns it off. */
    @VisibleForTesting
    internal fun applyAudioRoute(type: Int?) {
        setProximitySensorOn(type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    private fun setProximitySensorOn(on: Boolean) {
        if (on) {
            if (proximityLock?.isHeld == true) return
            proximityLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity")
                .also { it.acquire() }
        } else {
            proximityLock?.release()
            proximityLock = null
        }
    }

    override fun onMuteToggled() {
        sessions.connected?.let(::toggleSelfMute)
    }

    override fun onDeafenToggled() {
        sessions.connected?.let(::toggleSelfDeafen)
    }

    override fun onOverlayToggled() {
        if (channelOverlay.isShown) {
            channelOverlay.hide()
            return
        }
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
    }

    /** A press on a stale notification after the reconnect succeeded is a no-op. */
    override fun onReconnectCancelled() = sessions.cancelReconnect()

    override fun onReconnectNotificationDismissed() = sessions.markErrorShown()

    override fun reconnect() = sessions.reconnect()

    companion object {
        private const val TAG = "MumlaService"

        const val TTS_THRESHOLD = 250 // Maximum number of characters to read

        /** The preferences [onPreferenceChanged] reacts to. */
        private val OBSERVED_KEYS = setOf(Settings.INPUT_METHOD.key, Settings.HOT_CORNER.key, Settings.USE_TTS.key)

        /**
         * Starts the service for a session that is under way. Called while the user is looking at
         * the app (or acting on one of its notifications), when Android allows the start.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, MumlaService::class.java))
            } catch (e: IllegalStateException) {
                HumlaLog.w(TAG, "The app may not start its service now", e)
            }
        }
    }
}
