package se.lublin.mumla.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowToast
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.model.Message
import se.lublin.humla.model.UserState
import se.lublin.humla.model.Server
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.AppContainer
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.createMumlaService
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubTalkStates
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.util.HtmlUtils
import java.security.cert.X509Certificate

/**
 * MumlaService as it follows the current session: its foreground notification, reconnect prompt,
 * chat notifications, text-to-speech, what it shows while synchronized and the notification
 * actions. The session is a mock whose state and events the test drives; the overlay and the hot
 * corner are relaxed mocks.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceTest {
    private val app = ApplicationProvider.getApplicationContext<MumlaApplication>()
    private val created = mutableListOf<SessionConfig>()
    private lateinit var sessions: SessionManager
    private lateinit var controller: ServiceController<MumlaService>
    private lateinit var service: MumlaService
    private lateinit var overlay: MumlaOverlay
    private lateinit var hotCorner: MumlaHotCorner
    private val route = MutableStateFlow<Int?>(null)
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { audio.route } returns route
        every { audio.transmitMode } returns TransmitMode.VOICE_ACTIVITY
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val state = session.stubState(SessionState.Connecting)
    private val events: MutableSharedFlow<HumlaEvent> = session.stubEvents()
    private val model = session.stubModel(model(user(SELF)))
    private val talkStates = session.stubTalkStates()
    private var destroyed = false

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(app)

    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        app.installContainer(
            AppContainer(app, app.scope) { config ->
                created += config
                mockk<IHumlaSession>(relaxed = true).also { it.stubEvents(); it.stubState(SessionState.Connecting) }
            },
        )
        sessions = SessionManager.get(app)
        installSession(session)
        controller = createMumlaService()
        service = controller.get()
        overlay = mockk(relaxed = true)
        hotCorner = mockk(relaxed = true)
        service.channelOverlay = overlay
        service.hotCorner = hotCorner
        idleMainLooper()
    }

    @After
    fun tearDown() {
        if (!destroyed) controller.destroy()
    }

    private fun user(session: Int, muted: Boolean = false, deafened: Boolean = false) =
        UserState(session, "user$session", LOBBY, isSelfMuted = muted, isSelfDeafened = deafened)

    /** The server with [self] as our own user, or none, in the lobby. */
    private fun model(self: UserState?) = serverState(self = self?.session) {
        channel(0, "Root")
        channel(LOBBY, "Lobby")
        self?.let(::user)
    }

    private fun self(user: UserState?) {
        model.value = model(user)
        idleMainLooper()
    }

    private fun move(next: SessionState) {
        state.value = next
        idleMainLooper()
    }

    private fun emit(event: HumlaEvent) {
        events.tryEmit(event)
        idleMainLooper()
    }

    private fun foregroundText(): String? =
        shadowOf(service).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TEXT)

    private fun postedText(id: Int): String? =
        shadowOf(notificationManager).getNotification(id)?.extras?.getString(Notification.EXTRA_TEXT)

    private fun postedActions(): List<String> =
        shadowOf(notificationManager).getNotification(FOREGROUND_ID)?.actions.orEmpty().map { it.title.toString() }

    private fun reconnectPrompt(): Notification? = shadowOf(notificationManager).getNotification(RECONNECT_ID)

    private fun promptReceivers() =
        shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction("b_reconnect") }

    private fun talkReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction(TalkBroadcastReceiver.BROADCAST_TALK)
    }

    private fun textMessage(body: String, actor: String? = "alice") =
        Message(1, actor, emptyList(), emptyList(), emptyList(), body, receivedTime = 0L)

    private val lost = DisconnectReason.Network("socket reset", null)

    /** From here on the platform refuses every foreground start, as with the screen off. */
    private fun screenOff() {
        shadowOf(service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("startForeground() not allowed from the background"),
        )
    }

    // The foreground notification

    @Test
    fun connectingEntersTheForegroundWithTheConnectingTextAndNoActions() {
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(foregroundText()).isEqualTo(app.getString(R.string.mumlaConnecting))
        assertThat(postedActions()).isEmpty()
    }

    @Test
    fun withTorTheConnectingTextSaysSo() {
        preferences().edit().putBoolean(Settings.USE_TOR.key, true).commit()

        move(SessionState.Disconnected())
        installSession(mockk<IHumlaSession>(relaxed = true).also { it.stubState(SessionState.Connecting) })
        idleMainLooper()

        assertThat(foregroundText()).isEqualTo(app.getString(R.string.mumlaConnecting) + " (Tor)")
    }

    @Test
    fun connectedShowsTheConnectedTextAndTheActions() {
        move(SessionState.Connected)

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
        assertThat(postedActions()).hasSize(3)
    }

    /** A lost connection shows only "Cancel reconnect": there is no session to mute. */
    @Test
    fun aLostConnectionOffersOnlyToCancelTheReconnect() {
        move(SessionState.Connected)
        move(SessionState.ConnectionLost(2_000, 1, lost))
        assertThat(postedActions()).containsExactly(app.getString(R.string.cancel_reconnect))
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connection_lost_reconnecting))

        move(SessionState.Reconnecting(lost))
        assertThat(postedActions()).containsExactly(app.getString(R.string.cancel_reconnect))

        move(SessionState.Connected)
        assertThat(postedActions()).doesNotContain(app.getString(R.string.cancel_reconnect))
    }

    /** A reconnect restarting the foreground from the background is refused, so a loss keeps it. */
    @Test
    fun aLossAndTheRetryStayInTheForegroundWithoutStartingItAgain() {
        move(SessionState.Connected)
        screenOff()

        move(SessionState.ConnectionLost(2_000, 1, lost))
        move(SessionState.Reconnecting(lost))
        move(SessionState.Connected)

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(sessions.chat.messages.value.map { it.body })
            .doesNotContain(app.getString(R.string.foreground_start_failed))
    }

    @Test
    fun theCancelActionGivesUpTheReconnect() {
        move(SessionState.ConnectionLost(2_000, 1, lost))

        shadowOf(notificationManager).getNotification(FOREGROUND_ID)!!.actions
            .single { it.title.toString() == app.getString(R.string.cancel_reconnect) }.actionIntent.send()
        idleMainLooper()

        verify { session.cancelReconnect() }
        assertThat(sessions.errorShown.value).isTrue()
    }

    @Test
    fun theEndOfTheSessionLeavesTheForegroundAndStopsTheService() {
        move(SessionState.Connected)

        move(SessionState.Disconnected())

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    /** A connect that replaces the session keeps the service; only an idle one stops. */
    @Test
    fun aSessionReplacedOnTheSameTurnKeepsTheService() {
        move(SessionState.Connected)

        sessions.connect(SessionConfig())
        idleMainLooper()

        assertThat(shadowOf(service).isStoppedBySelf).isFalse()
        assertThat(foregroundText()).isEqualTo(app.getString(R.string.mumlaConnecting))
    }

    /** The replaced session's end is not this service's to render. */
    @Test
    fun aReplacedSessionIsNotRenderedAnyMore() {
        sessions.connect(SessionConfig())
        idleMainLooper()

        move(SessionState.Disconnected(lost))

        assertThat(reconnectPrompt()).isNull()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
    }

    /** The next session starts with the screen off, where the platform refuses the foreground. */
    private fun connectWithTheScreenOff() {
        move(SessionState.Disconnected())
        screenOff()
        installSession(mockk<IHumlaSession>(relaxed = true).also { it.stubState(SessionState.Connecting) })
        idleMainLooper()
    }

    @Test
    fun aRefusedForegroundStartBecomesAWarningAndAPromptInsteadOfACrash() {
        connectWithTheScreenOff()

        assertThat(sessions.chat.messages.value.map { it.body })
            .containsExactly(app.getString(R.string.foreground_start_failed))
        assertThat(reconnectPrompt()!!.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo(app.getString(R.string.foreground_start_failed))
    }

    @Test
    fun aRefusalIsNotShownAsAPromptWhileTheAppIsVisible() {
        sessions.setAppVisible(true)

        connectWithTheScreenOff()

        assertThat(reconnectPrompt()).isNull()
        assertThat(sessions.chat.messages.value.map { it.body })
            .containsExactly(app.getString(R.string.foreground_start_failed))
    }

    @Test
    fun aRefusalPromptReplacesAnOlderPromptIncludingItsReceiver() {
        move(SessionState.Disconnected(lost))
        screenOff()

        move(SessionState.Connecting)
        move(SessionState.Connected)

        assertThat(promptReceivers()).hasSize(1)
    }

    // Our own state

    @Test
    fun ourOwnMuteAndDeafenStateIsStoredAndShown() {
        move(SessionState.Connected)

        self(user(SELF, muted = true, deafened = false))
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.status_notify_muted))
        assertThat(Settings.getInstance(app).isMuted).isTrue()
        assertThat(Settings.getInstance(app).isDeafened).isFalse()

        self(user(SELF, muted = true, deafened = true))
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.status_notify_muted_and_deafened))
        assertThat(Settings.getInstance(app).isDeafened).isTrue()

        self(user(SELF))
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
        assertThat(Settings.getInstance(app).isMuted).isFalse()
    }

    /** What the server says at synchronization is not a change: the stored state is restored instead. */
    @Test
    fun theStateFoundAtSynchronizationIsNotStored() {
        Settings.getInstance(app).setMutedAndDeafened(true, false)

        move(SessionState.Connected)

        assertThat(Settings.getInstance(app).isMuted).isTrue()
        verify { session.actions.setSelfMuteDeafState(true, false) }
    }

    @Test
    fun deafenedWithoutMuteReadsAsConnected() {
        move(SessionState.Connected)

        self(user(SELF, muted = false, deafened = true))

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
    }

    @Test
    fun somebodyElsesStateChangesNothing() {
        move(SessionState.Connected)

        model.value = serverState(self = SELF) {
            channel(0, "Root")
            channel(LOBBY, "Lobby")
            user(user(SELF))
            user(user(SELF + 1, muted = true, deafened = true))
        }
        idleMainLooper()

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
        assertThat(Settings.getInstance(app).isMuted).isFalse()
    }

    @Test
    fun aModelWithoutOurUserChangesNothing() {
        self(null)
        move(SessionState.Connected)

        self(null)

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
        assertThat(Settings.getInstance(app).isMuted).isFalse()
    }

    @Test
    fun ourOwnStateOutsideTheForegroundDoesNotEnterIt() {
        move(SessionState.Disconnected())

        self(user(SELF, muted = true))
        emit(HumlaEvent.PermissionDenied(HumlaEvent.DenyType.OTHER, "no"))

        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID)).isNull()
    }

    @Test
    fun aPermissionDenialRepostsTheNotification() {
        val before = shadowOf(notificationManager).getNotification(FOREGROUND_ID)

        emit(HumlaEvent.PermissionDenied(HumlaEvent.DenyType.OTHER, "no"))

        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID)).isNotSameInstanceAs(before)
    }

    @Test
    fun aPermissionDenialRepostsNothingWhileTheAppIsVisible() {
        val before = shadowOf(notificationManager).getNotification(FOREGROUND_ID)
        sessions.setAppVisible(true)

        emit(HumlaEvent.PermissionDenied(HumlaEvent.DenyType.OTHER, "no"))

        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID)).isSameInstanceAs(before)
    }

    // The reconnect prompt

    @Test
    fun anEndWithAReasonShowsTheReconnectPrompt() {
        move(SessionState.Disconnected(lost))

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        val prompt = reconnectPrompt()!!
        assertThat(prompt.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
        assertThat(prompt.actions.single().title.toString()).isEqualTo(app.getString(R.string.reconnect))
    }

    /** The prompt's buttons need the service, so it stays until the prompt is gone. */
    @Test
    fun theServiceStaysWhileThePromptIsUpAndStopsOnceItIsDismissed() {
        move(SessionState.Disconnected(lost))
        assertThat(shadowOf(service).isStoppedBySelf).isFalse()

        reconnectPrompt()!!.deleteIntent.send()
        idleMainLooper()

        assertThat(sessions.errorShown.value).isTrue()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun thePromptsReconnectConnectsANewSessionWithTheLastConfiguration() {
        val config = SessionConfig()
        every { session.config } returns config
        move(SessionState.Disconnected(lost))

        reconnectPrompt()!!.actions.single().actionIntent.send()
        idleMainLooper()

        assertThat(created.single()).isSameInstanceAs(config)
    }

    @Test
    fun anEndWithAReasonShowsNoPromptWhileTheAppIsVisible() {
        sessions.setAppVisible(true)

        move(SessionState.Disconnected(lost))

        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun aCleanEndShowsNoPrompt() {
        move(SessionState.Disconnected())

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(reconnectPrompt()).isNull()
    }

    /** The certificate dialog is the app's to show; a prompt to reconnect would just fail again. */
    @Test
    fun aCertificateProblemShowsNoPrompt() {
        move(SessionState.Disconnected(DisconnectReason.TlsUntrusted(listOf(mockk<X509Certificate>()))))

        assertThat(reconnectPrompt()).isNull()
    }

    /** The user asked for it, so there is nothing to report. */
    @Test
    fun cancellingTheReconnectEndsWithoutAPrompt() {
        move(SessionState.ConnectionLost(2_000, 1, lost))
        every { session.cancelReconnect() } answers { state.value = SessionState.Disconnected(lost) }

        sessions.cancelReconnect()
        idleMainLooper()

        assertThat(reconnectPrompt()).isNull()
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
    }

    @Test
    fun acknowledgingTheErrorHidesThePrompt() {
        move(SessionState.Disconnected(lost))

        sessions.markErrorShown()
        idleMainLooper()

        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun aNewSessionHidesThePromptLeftFromTheLastOne() {
        move(SessionState.Disconnected(lost))
        assertThat(reconnectPrompt()).isNotNull()

        sessions.connect(SessionConfig())
        idleMainLooper()

        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun withTorTheConnectedTextAndThePromptSaySo() {
        preferences().edit().putBoolean(Settings.USE_TOR.key, true).commit()

        move(SessionState.Connected)
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected) + " (Tor)")

        move(SessionState.Disconnected(lost))
        assertThat(reconnectPrompt()!!.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset (Tor)")
    }

    @Test
    fun aNewPromptReplacesTheOldOneIncludingItsReceiver() {
        move(SessionState.Disconnected(lost))
        move(SessionState.Disconnected(DisconnectReason.Network("again", null)))

        assertThat(promptReceivers()).hasSize(1)
        assertThat(reconnectPrompt()!!.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("again")
    }

    @Test
    fun dismissingTheChatNotificationLeavesThePromptAlone() {
        move(SessionState.Disconnected(lost))

        sessions.setAppVisible(true)
        idleMainLooper()

        assertThat(reconnectPrompt()).isNotNull()
    }

    // Chat notifications and text-to-speech

    /** The messages of the one posted chat notification. */
    private fun postedMessages(): List<NotificationCompat.MessagingStyle.Message> {
        val posted = shadowOf(notificationManager).getNotification(MESSAGE_ID)!!
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)!!.messages
    }

    private fun replyIntent(text: String): Intent {
        val intent = Intent(app, MumlaService::class.java).setAction(MumlaMessageNotification.ACTION_REPLY)
        val input = RemoteInput.Builder("reply").build()
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence("reply", text) })
        return intent
    }

    @Test
    fun aMessageFromTheServerIsNotifiedAsFromTheServer() {
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()

        emit(HumlaEvent.TextMessage(textMessage("motd", actor = null)))

        val message = postedMessages().single()
        assertThat(message.person?.name.toString()).isEqualTo(app.getString(R.string.server))
        assertThat(message.text.toString()).isEqualTo("motd")
    }

    @Test
    fun anInlineReplyGoesToOurChannelAndJoinsTheNotification() {
        move(SessionState.Connected)
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()
        emit(HumlaEvent.TextMessage(textMessage("coming?", actor = null)))

        service.onStartCommand(replyIntent(" on my way "), 0, 1)

        verify { session.actions.sendChannelTextMessage(LOBBY, HtmlUtils.markupOutgoingMessage("on my way"), false) }
        assertThat(postedMessages().last().text.toString()).isEqualTo("on my way")
    }

    @Test
    fun anInlineReplyWithoutAConnectedSessionSendsNothingAndRemovesTheNotification() {
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()
        emit(HumlaEvent.TextMessage(textMessage("coming?")))

        service.onStartCommand(replyIntent("hello"), 0, 1)

        verify(exactly = 0) { session.actions.sendChannelTextMessage(any(), any(), any()) }
        assertThat(shadowOf(notificationManager).getNotification(MESSAGE_ID)).isNull()
    }

    @Test
    fun aReceivedMessageIsNotifiedOnlyWhenChatNotificationsAreOn() {
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, false).commit()
        emit(HumlaEvent.TextMessage(textMessage("ping")))
        assertThat(shadowOf(notificationManager).getNotification(MESSAGE_ID)).isNull()

        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()
        emit(HumlaEvent.TextMessage(textMessage("ping")))
        assertThat(shadowOf(notificationManager).getNotification(MESSAGE_ID)).isNotNull()
    }

    @Test
    fun aNotifiedMessageShowsItsTextWithoutMarkup() {
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()

        emit(HumlaEvent.TextMessage(textMessage("<b>hi</b> <a href=\"https://x.example\">there</a>")))

        val message = postedMessages().single()
        assertThat(message.text.toString()).isEqualTo("hi there")
        assertThat(message.person?.name.toString()).isEqualTo("alice")
    }

    /** The app shows the chat itself, so its notification goes when a screen of the app shows. */
    @Test
    fun theAppBecomingVisibleDismissesTheChatNotification() {
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()
        emit(HumlaEvent.TextMessage(textMessage("ping")))

        sessions.setAppVisible(true)
        idleMainLooper()

        assertThat(shadowOf(notificationManager).getNotification(MESSAGE_ID)).isNull()
    }

    @Test
    fun theEndOfTheSessionDismissesTheChatNotification() {
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()
        emit(HumlaEvent.TextMessage(textMessage("ping")))

        move(SessionState.Disconnected())

        assertThat(shadowOf(notificationManager).getNotification(MESSAGE_ID)).isNull()
    }

    private fun installTts(): TextToSpeech {
        val tts = mockk<TextToSpeech>(relaxed = true)
        service.tts = tts
        preferences().edit().putBoolean(Settings.USE_TTS.key, true).commit()
        return tts
    }

    private fun spoken(tts: TextToSpeech): List<String> {
        val texts = mutableListOf<String>()
        verify(atLeast = 0) { tts.speak(capture(texts), any(), any()) }
        return texts
    }

    @Test
    fun aReceivedMessageIsSpokenWithItsSenderWhenTextToSpeechIsOn() {
        val tts = installTts()

        emit(HumlaEvent.TextMessage(textMessage("<b>hi</b> there")))

        verify(exactly = 1) {
            tts.speak(app.getString(R.string.notification_message, "alice", "hi there"), TextToSpeech.QUEUE_ADD, null)
        }
    }

    @Test
    fun nothingIsSpokenWhenTextToSpeechIsOff() {
        val tts = installTts()
        preferences().edit().putBoolean(Settings.USE_TTS.key, false).commit()

        emit(HumlaEvent.TextMessage(textMessage("hi")))

        assertThat(spoken(tts)).isEmpty()
    }

    @Test
    fun nothingIsSpokenWhileDeafened() {
        self(user(SELF, muted = true, deafened = true))
        val tts = installTts()

        emit(HumlaEvent.TextMessage(textMessage("hi")))

        assertThat(spoken(tts)).isEmpty()
    }

    @Test
    fun aMessageLongerThanTheThresholdIsNotSpoken() {
        val tts = installTts()
        val prefix = app.getString(R.string.notification_message, "alice", "")
        val atThreshold = "x".repeat(MumlaService.TTS_THRESHOLD - prefix.length)

        emit(HumlaEvent.TextMessage(textMessage(atThreshold)))
        emit(HumlaEvent.TextMessage(textMessage(atThreshold + "y")))

        assertThat(spoken(tts)).containsExactly(prefix + atThreshold)
    }

    @Test
    fun shortMessagesSpeakOnlyTheHostOfABareLink() {
        val tts = installTts()
        preferences().edit().putBoolean(Settings.SHORT_TTS_MESSAGES.key, true).commit()

        emit(
            HumlaEvent.TextMessage(
                textMessage(
                    "see <a href=\"https://example.org/a/b\">https://example.org/a/b</a> and " +
                        "<a href=\"https://other.org/x\">named</a>",
                ),
            ),
        )

        val link = app.getString(R.string.chat_message_tts_short_link, "example.org")
        assertThat(spoken(tts)).containsExactly(
            app.getString(R.string.notification_message, "alice", "see $link and named"),
        )
    }

    @Test
    fun withoutShortMessagesTheLinkIsSpokenInFull() {
        val tts = installTts()

        emit(HumlaEvent.TextMessage(textMessage("see <a href=\"https://example.org/a\">https://example.org/a</a>")))

        assertThat(spoken(tts)).containsExactly(
            app.getString(R.string.notification_message, "alice", "see https://example.org/a"),
        )
    }

    @Test
    fun turningTextToSpeechOffShutsItDownAndOnCreatesIt() {
        val tts = installTts()

        preferences().edit().putBoolean(Settings.USE_TTS.key, false).commit()
        verify(exactly = 1) { tts.shutdown() }
        assertThat(service.tts).isNull()

        preferences().edit().putBoolean(Settings.USE_TTS.key, true).commit()
        assertThat(service.tts).isNotNull()
    }

    /** Read when the service starts; nothing else would until the setting changes. */
    @Test
    fun textToSpeechOnAtStartIsCreatedAtStart() {
        preferences().edit().putBoolean(Settings.USE_TTS.key, true).commit()

        val fresh = createMumlaService()

        assertThat(fresh.get().tts).isNotNull()
        fresh.destroy()
    }

    // Synchronization and its end

    @Test
    fun synchronizingRestoresTheStoredMuteAndDeafenState() {
        preferences().edit().putBoolean(Settings.MUTED.key, true).putBoolean(Settings.DEAFENED.key, true).commit()

        move(SessionState.Connected)

        verify(exactly = 1) { session.actions.setSelfMuteDeafState(true, true) }
    }

    @Test
    fun synchronizingRestoresADeafenStoredWithoutMute() {
        preferences().edit().putBoolean(Settings.DEAFENED.key, true).commit()

        move(SessionState.Connected)

        verify(exactly = 1) { session.actions.setSelfMuteDeafState(false, true) }
    }

    @Test
    fun synchronizingSendsNoStateWhenNeitherIsStored() {
        move(SessionState.Connected)

        verify(exactly = 0) { session.actions.setSelfMuteDeafState(any(), any()) }
    }

    /** A reconnect synchronizes again, and the stored state is restored again. */
    @Test
    fun everySynchronizationRestoresTheStoredState() {
        preferences().edit().putBoolean(Settings.MUTED.key, true).commit()

        move(SessionState.Connected)
        move(SessionState.ConnectionLost(1L, 1, lost))
        move(SessionState.Reconnecting(lost))
        move(SessionState.Connected)

        verify(exactly = 2) { session.actions.setSelfMuteDeafState(true, false) }
    }

    /** Other apps (automation, headset helpers) send the talk broadcast: exported on purpose. */
    @Test
    fun synchronizingStartsListeningForExportedTalkBroadcasts() {
        move(SessionState.Connected)

        val receiver = talkReceivers().single()
        assertThat(receiver.flags and android.content.Context.RECEIVER_EXPORTED).isNotEqualTo(0)
    }

    @Test
    fun synchronizingLeavesTheHotCornerAloneWhenNotAskedFor() {
        move(SessionState.Connected)

        verify(exactly = 0) { hotCorner.isShown = true }
        assertThat(service.proximityLock).isNull()
    }

    @Test
    fun synchronizingShowsTheHotCornerWhenAskedFor() {
        preferences().edit().putString(Settings.HOT_CORNER.key, Settings.ARRAY_HOT_CORNER_TOP_LEFT).commit()
        verify(exactly = 0) { hotCorner.isShown = true }

        move(SessionState.Connected)

        verify(exactly = 1) { hotCorner.isShown = true }
    }

    @Test
    fun aLostConnectionTearsDownWhatTheSessionShowed() {
        move(SessionState.Connected)
        route.value = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

        move(SessionState.ConnectionLost(1L, 1, lost))

        assertThat(talkReceivers()).isEmpty()
        verify { overlay.hide() }
        verify { hotCorner.isShown = false }
        assertThat(service.proximityLock).isNull()
    }

    // The audio route

    private fun proximityLockHeld(): Boolean {
        val lock = service.proximityLock
        return lock != null && lock.isHeld && shadowOf(lock).tag == "Mumla:Proximity" &&
            ShadowPowerManager.getLatestWakeLock() === lock
    }

    @Test
    fun theEarpieceTurnsTheProximitySensorOnAndAnythingElseOff() {
        route.value = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        idleMainLooper()
        assertThat(proximityLockHeld()).isTrue()

        route.value = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        idleMainLooper()
        assertThat(proximityLockHeld()).isFalse()
    }

    @Test
    fun aRepeatedEarpieceReportKeepsOneLockAndLeaksNone() {
        service.applyAudioRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val first = service.proximityLock!!

        service.applyAudioRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        service.applyAudioRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        assertThat(first.isHeld).isFalse()
        assertThat(proximityLockHeld()).isFalse()
    }

    // The notification actions and the talk keys

    @Test
    fun muteToggleFlipsMuteAndDropsDeafenWhenUnmuting() {
        self(user(SELF, muted = true, deafened = true))
        move(SessionState.Connected)

        service.onMuteToggled()

        verify { session.actions.setSelfMuteDeafState(false, false) }
    }

    @Test
    fun muteToggleKeepsDeafenWhenMuting() {
        self(user(SELF, muted = false, deafened = true))
        move(SessionState.Connected)

        service.onMuteToggled()

        verify { session.actions.setSelfMuteDeafState(true, true) }
    }

    @Test
    fun deafenToggleSetsBothFromTheDeafenState() {
        move(SessionState.Connected)
        service.onDeafenToggled()
        self(user(SELF, muted = true, deafened = true))
        service.onDeafenToggled()

        verify { session.actions.setSelfMuteDeafState(true, true) }
        verify { session.actions.setSelfMuteDeafState(false, false) }
    }

    /** A stale notification button outside a synchronized session does nothing, and throws nothing. */
    @Test
    fun theTogglesDoNothingWithoutASynchronizedSessionOrOurUser() {
        service.onMuteToggled()
        service.onDeafenToggled()
        move(SessionState.Connected)
        self(null)
        service.onMuteToggled()
        service.onDeafenToggled()

        verify(exactly = 0) { session.actions.setSelfMuteDeafState(any(), any()) }
    }

    @Test
    fun theHotCornerDrivesTheTalkKeys() {
        move(SessionState.Connected)
        preferences().edit()
            .putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT)
            .putBoolean(Settings.PTT_TOGGLE.key, false)
            .commit()

        service.hotCornerListener.onHotCornerDown()
        verify { session.audio.setTalking(true) }
        service.hotCornerListener.onHotCornerUp()
        verify { session.audio.setTalking(false) }
    }

    @Test
    fun theOverlayToggleShowsAHiddenOverlayWhenAllowed() {
        every { overlay.isShown } returns false
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)

        service.onOverlayToggled()

        verify(exactly = 1) { overlay.show() }
    }

    @Test
    fun theOverlayToggleAsksForThePermissionFirst() {
        every { overlay.isShown } returns false
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)

        service.onOverlayToggled()

        verify(exactly = 0) { overlay.show() }
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        assertThat(started.data.toString()).isEqualTo("package:" + app.packageName)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(app.getString(R.string.grant_perm_draw_over_apps))
    }

    @Test
    fun theOverlayToggleHidesAShownOverlay() {
        every { overlay.isShown } returns true

        service.onOverlayToggled()

        verify(exactly = 1) { overlay.hide() }
        verify(exactly = 0) { overlay.show() }
    }

    @Test
    fun switchingToPushToTalkShowsTheOverlayButton() {
        preferences().edit().putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT).commit()
        preferences().edit().putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_VOICE).commit()

        verify(exactly = 1) { overlay.setPushToTalkShown(true) }
        verify(exactly = 1) { overlay.setPushToTalkShown(false) }
    }

    @Test
    fun theHotCornerPreferenceMovesItAndShowsItOnlyWhileConnected() {
        preferences().edit().putString(Settings.HOT_CORNER.key, Settings.ARRAY_HOT_CORNER_TOP_LEFT).commit()
        verify { hotCorner.gravity = Settings.getInstance(app).hotCornerGravity }
        verify(exactly = 1) { hotCorner.isShown = false }

        state.value = SessionState.Connected
        service.onPreferenceChanged(Settings.HOT_CORNER.key)
        verify(exactly = 2) { hotCorner.isShown = true }

        preferences().edit().putString(Settings.HOT_CORNER.key, Settings.ARRAY_HOT_CORNER_NONE).commit()
        verify(exactly = 2) { hotCorner.isShown = false }
    }

    // The talk click

    private var clicks = 0

    /** All five clauses true; each test below turns exactly one of them false. */
    private fun clickReady() {
        service.keyClickSound = { clicks++ }
        every { session.audio.transmitMode } returns TransmitMode.PUSH_TO_TALK
        preferences().edit().putBoolean(Settings.PTT_SOUND.key, true).commit()
        move(SessionState.Connected)
    }

    private fun talk(session: Int = SELF, state: TalkState = TalkState.TALKING) {
        talkStates.value = mapOf(session to state)
        idleMainLooper()
    }

    @Test
    fun startingToTalkInPushToTalkClicks() {
        clickReady()
        talk()
        talkStates.value = emptyMap()
        idleMainLooper()
        talk()
        assertThat(clicks).isEqualTo(2)
    }

    @Test
    fun noClickWithoutASynchronizedSession() {
        clickReady()
        state.value = SessionState.Reconnecting(lost)
        talk()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickForSomebodyElse() {
        clickReady()
        talk(session = SELF + 1)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickOutsidePushToTalk() {
        clickReady()
        every { session.audio.transmitMode } returns TransmitMode.VOICE_ACTIVITY
        talk()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickWhenTheTalkStateIsNotTalking() {
        clickReady()
        talk(state = TalkState.WHISPERING)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickWhenTheSoundIsOff() {
        clickReady()
        preferences().edit().putBoolean(Settings.PTT_SOUND.key, false).commit()
        talk()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickBeforeOurSessionIsKnown() {
        self(null)
        clickReady()
        talk()
        assertThat(clicks).isEqualTo(0)
    }

    // The media session and the end of the service

    @Test
    fun aSynchronizedSessionHoldsAMediaSessionUntilTheServiceIsDestroyed() {
        val mediaSession = service.mediaSession!!
        assertThat(mediaSession.isActive).isFalse()

        move(SessionState.Connected)
        assertThat(mediaSession.isActive).isTrue()

        controller.destroy()
        destroyed = true
        idleMainLooper()
        assertThat(mediaSession.isActive).isFalse()

        // Really detached, not merely inactive: a later synchronization must not revive it.
        move(SessionState.ConnectionLost(1L, 1, lost))
        move(SessionState.Connected)
        assertThat(mediaSession.isActive).isFalse()
    }

    /** Without the service the microphone is dead in the background, so the session goes with it. */
    @Test
    fun destroyingTheServiceDuringASessionDisconnectsIt() {
        move(SessionState.Connected)

        controller.destroy()
        destroyed = true

        verify { session.disconnect() }
    }

    @Test
    fun destroyingTheServiceLetsGoOfEverything() {
        val tts = installTts()
        preferences().edit().putBoolean(Settings.CHAT_NOTIFY.key, true).commit()
        move(SessionState.Connected)
        route.value = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        move(SessionState.Disconnected(lost))
        emit(HumlaEvent.TextMessage(textMessage("ping")))
        assertThat(shadowOf(notificationManager).allNotifications).hasSize(2)

        controller.destroy()
        destroyed = true
        idleMainLooper()

        verify { tts.shutdown() }
        assertThat(talkReceivers()).isEmpty()
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
        assertThat(promptReceivers()).isEmpty()
        assertThat(service.proximityLock).isNull()
        // Nothing follows the session or the preferences any more.
        emit(HumlaEvent.TextMessage(textMessage("after")))
        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
        preferences().edit().putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT).commit()
        verify(exactly = 0) { overlay.setPushToTalkShown(any()) }
    }

    private companion object {
        const val SELF = 7
        const val LOBBY = 4
        const val FOREGROUND_ID = 1
        const val MESSAGE_ID = 2
        const val RECONNECT_ID = 3
    }
}
