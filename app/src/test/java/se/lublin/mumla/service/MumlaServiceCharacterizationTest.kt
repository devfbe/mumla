package se.lublin.mumla.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Message as ProtoMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowToast
import se.lublin.humla.HumlaService
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.Channel
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.session.SessionState
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaException
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver

/**
 * What MumlaService does, written against the Java service before its Kotlin conversion and left
 * unchanged across it (task 12). Enumerated from the production file: every observer callback,
 * every lifecycle hook, every preference arm that is not an audio extra (those are
 * MumlaServiceAudioPreferencesTest's) and every IMumlaService call.
 *
 * The collaborators that draw windows -- the overlay and the hot corner -- and TextToSpeech are
 * replaced by relaxed mocks after onCreate, through their fields, so the calls the service makes
 * on them can be read back. The session is a mocked HumlaConnection that reports itself connected
 * and synchronized, plus a mocked ModelHandler that knows the one user who is us.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceCharacterizationTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<MumlaService>
    private lateinit var service: MumlaService
    private lateinit var overlay: MumlaOverlay
    private lateinit var hotCorner: MumlaHotCorner
    private lateinit var connection: HumlaConnection
    private lateinit var self: User
    private val sent = mutableListOf<Pair<HumlaTCPMessageType, ProtoMessage>>()

    private fun humlaField(name: String) =
        HumlaService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun mumlaField(name: String) =
        MumlaService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(app)

    private fun callbacks() = humlaField("mCallbacks").get(service) as HumlaCallbacks

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildService(MumlaService::class.java).create()
        service = controller.get()
        overlay = mockk(relaxed = true)
        hotCorner = mockk(relaxed = true)
        mumlaField("mChannelOverlay").set(service, overlay)
        mumlaField("mHotCorner").set(service, hotCorner)
    }

    private var destroyed = false

    @After
    fun tearDown() {
        if (!destroyed) controller.destroy()
    }

    /**
     * onConnectionSynchronized without the model handler: the superclass hook then returns before
     * it builds an audio pipeline (a real AudioRecord on another thread whose failure would land in
     * the chat log at an unpredictable moment), and MumlaService's own half runs in full.
     */
    private fun synchronize() {
        humlaField("mModelHandler").set(service, null)
        service.onConnectionSynchronized()
    }

    /** A session that is up and synchronized, with [SELF] as our own user. */
    private fun connect(muted: Boolean = false, deafened: Boolean = false) {
        connection = mockk(relaxed = true)
        every { connection.isConnected } returns true
        every { connection.isSynchronized } returns true
        every { connection.getSession() } returns SELF
        val type = slot<HumlaTCPMessageType>()
        val message = slot<ProtoMessage>()
        every { connection.sendTCPMessage(capture(message), capture(type)) } answers {
            sent += type.captured to message.captured
        }
        self = user(SELF, muted, deafened)
        val model = mockk<ModelHandler>(relaxed = true)
        every { model.getUser(SELF) } returns self
        humlaField("mConnection").set(service, connection)
        humlaField("mModelHandler").set(service, model)
        humlaField("mConnectionState").set(service, HumlaService.ConnectionState.CONNECTED)
    }

    private fun user(session: Int, muted: Boolean = false, deafened: Boolean = false): User {
        val u = mockk<User>(relaxed = true)
        every { u.getSession() } returns session
        every { u.isSelfMuted } returns muted
        every { u.isSelfDeafened } returns deafened
        every { u.getName() } returns "user$session"
        every { u.getTextureHash() } returns null
        every { u.getTexture() } returns null
        return u
    }

    private fun userStates() = sent.filter { it.first == HumlaTCPMessageType.UserState }
        .map { it.second as Mumble.UserState }

    private fun blobRequests() = sent.filter { it.first == HumlaTCPMessageType.RequestBlob }
        .map { it.second as Mumble.RequestBlob }

    private fun foregroundText(): String? =
        shadowOf(service).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TEXT)

    private fun postedText(id: Int): String? =
        shadowOf(notificationManager).getNotification(id)?.extras?.getString(Notification.EXTRA_TEXT)

    private fun reconnectPrompt(): Notification? = shadowOf(notificationManager).getNotification(RECONNECT_ID)

    private fun textMessage(body: String, actor: String = "alice"): IMessage = object : IMessage {
        override fun getActor(): Int = 1
        override fun getActorName(): String = actor
        override fun getTargetChannels(): List<Channel> = emptyList()
        override fun getTargetTrees(): List<Channel> = emptyList()
        override fun getTargetUsers(): List<User> = emptyList()
        override fun getMessage(): String = body
        override fun getReceivedTime(): Long = 0L
    }

    private fun error() = HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

    // ---- the chat log --------------------------------------------------------------------------

    @Test
    fun startsDisconnectedWithAnEmptyChatLogAndNoForegroundNotification() {
        assertThat(service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
        assertThat(service.getMessageLog()).isEmpty()
        assertThat(shadowOf(service).lastForegroundNotification).isNull()
    }

    @Test
    fun warningsAndErrorsLandInTheChatLogWithTheirTypeButInfoWaitsForASession() {
        service.logInfo("hello")
        service.logWarning("careful")
        service.logError("broken")
        idle()

        assertThat(service.getMessageLog().map { (it as IChatMessage.InfoMessage).type to it.body }).containsExactly(
            IChatMessage.InfoMessage.Type.WARNING to "careful",
            IChatMessage.InfoMessage.Type.ERROR to "broken",
        ).inOrder()
    }

    @Test
    fun infoLandsInTheChatLogOnceSynchronized() {
        connect()

        service.logInfo("hello")
        idle()

        val entry = service.getMessageLog().single() as IChatMessage.InfoMessage
        assertThat(entry.type).isEqualTo(IChatMessage.InfoMessage.Type.INFO)
        assertThat(entry.body).isEqualTo("hello")
    }

    @Test
    fun aReceivedTextMessageLandsInTheChatLogAsTheSameMessage() {
        connect()
        val message = textMessage("hi there")

        callbacks().onMessageLogged(message)
        idle()

        assertThat((service.getMessageLog().single() as IChatMessage.TextMessage).message).isSameInstanceAs(message)
    }

    @Test
    fun sentTextMessagesLandInTheChatLog() {
        connect()

        val toUser = service.sendUserTextMessage(SELF, "to a user")
        val toChannel = service.sendChannelTextMessage(3, "to a channel", false)

        assertThat(service.getMessageLog().map { (it as IChatMessage.TextMessage).message })
            .containsExactly(toUser, toChannel).inOrder()
    }

    @Test
    fun theReturnedLogCannotBeWrittenThrough() {
        service.logWarning("one")
        idle()

        @Suppress("UNCHECKED_CAST")
        val log = service.getMessageLog() as MutableList<IChatMessage>
        assertThrows(UnsupportedOperationException::class.java) {
            log.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, "sneaked in"))
        }
    }

    @Test
    fun clearMessageLogEmptiesIt() {
        service.logWarning("one")
        idle()

        service.clearMessageLog()

        assertThat(service.getMessageLog()).isEmpty()
    }

    // ---- chat notifications and text to speech --------------------------------------------------

    @Test
    fun aReceivedMessageIsNotifiedWhenChatNotificationsAreOn() {
        connect()
        preferences().edit().putBoolean(Settings.PREF_CHAT_NOTIFY, true).commit()

        callbacks().onMessageLogged(textMessage("ping"))
        idle()

        assertThat(shadowOf(notificationManager).allNotifications).isNotEmpty()
    }

    @Test
    fun aReceivedMessageIsNotNotifiedWhenChatNotificationsAreOff() {
        connect()
        preferences().edit().putBoolean(Settings.PREF_CHAT_NOTIFY, false).commit()

        callbacks().onMessageLogged(textMessage("ping"))
        idle()

        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
    }

    @Test
    fun clearChatNotificationsDismissesThem() {
        connect()
        preferences().edit().putBoolean(Settings.PREF_CHAT_NOTIFY, true).commit()
        callbacks().onMessageLogged(textMessage("ping"))
        idle()

        service.clearChatNotifications()

        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
    }

    private fun installTts(): TextToSpeech {
        val tts = mockk<TextToSpeech>(relaxed = true)
        mumlaField("mTTS").set(service, tts)
        preferences().edit().putBoolean(Settings.PREF_USE_TTS, true).commit()
        return tts
    }

    private fun spoken(tts: TextToSpeech): List<String> {
        val texts = mutableListOf<String>()
        verify(atLeast = 0) { tts.speak(capture(texts), any(), any()) }
        return texts
    }

    @Test
    fun aReceivedMessageIsSpokenWithItsSenderWhenTextToSpeechIsOn() {
        connect()
        val tts = installTts()

        callbacks().onMessageLogged(textMessage("<b>hi</b> there"))
        idle()

        verify(exactly = 1) {
            tts.speak(app.getString(R.string.notification_message, "alice", "hi there"), TextToSpeech.QUEUE_ADD, null)
        }
    }

    @Test
    fun nothingIsSpokenWhenTextToSpeechIsOff() {
        connect()
        val tts = installTts()
        preferences().edit().putBoolean(Settings.PREF_USE_TTS, false).commit()

        callbacks().onMessageLogged(textMessage("hi"))
        idle()

        assertThat(spoken(tts)).isEmpty()
    }

    @Test
    fun nothingIsSpokenWhileDeafened() {
        connect(muted = true, deafened = true)
        val tts = installTts()

        callbacks().onMessageLogged(textMessage("hi"))
        idle()

        assertThat(spoken(tts)).isEmpty()
    }

    @Test
    fun aMessageLongerThanTheThresholdIsNotSpoken() {
        connect()
        val tts = installTts()
        val prefix = app.getString(R.string.notification_message, "alice", "")
        val atThreshold = "x".repeat(MumlaService.TTS_THRESHOLD - prefix.length)

        callbacks().onMessageLogged(textMessage(atThreshold))
        callbacks().onMessageLogged(textMessage(atThreshold + "y"))
        idle()

        assertThat(spoken(tts)).containsExactly(prefix + atThreshold)
    }

    @Test
    fun shortMessagesSpeakOnlyTheHostOfABareLink() {
        connect()
        val tts = installTts()
        preferences().edit().putBoolean(Settings.PREF_SHORT_TTS_MESSAGES, true).commit()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_SHORT_TTS_MESSAGES)

        callbacks().onMessageLogged(
            textMessage(
                "see <a href=\"https://example.org/a/b\">https://example.org/a/b</a> and " +
                    "<a href=\"https://other.org/x\">named</a>",
            ),
        )
        idle()

        val link = app.getString(R.string.chat_message_tts_short_link, "example.org")
        assertThat(spoken(tts)).containsExactly(
            app.getString(R.string.notification_message, "alice", "see $link and named"),
        )
    }

    @Test
    fun withoutShortMessagesTheLinkIsSpokenInFull() {
        connect()
        val tts = installTts()

        callbacks().onMessageLogged(textMessage("see <a href=\"https://example.org/a\">https://example.org/a</a>"))
        idle()

        assertThat(spoken(tts)).containsExactly(
            app.getString(R.string.notification_message, "alice", "see https://example.org/a"),
        )
    }

    @Test
    fun turningTextToSpeechOffShutsItDownAndOnCreatesIt() {
        val tts = installTts()
        preferences().edit().putBoolean(Settings.PREF_USE_TTS, false).commit()

        service.onSharedPreferenceChanged(preferences(), Settings.PREF_USE_TTS)

        verify(exactly = 1) { tts.shutdown() }
        assertThat(mumlaField("mTTS").get(service)).isNull()

        preferences().edit().putBoolean(Settings.PREF_USE_TTS, true).commit()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_USE_TTS)

        assertThat(mumlaField("mTTS").get(service)).isNotNull()
    }

    // ---- the foreground notification, as the observer drives it today --------------------------

    @Test
    fun connectingEntersTheForegroundWithTheConnectingText() {
        service.renderSessionState(SessionState.Connecting)
        idle()

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(foregroundText()).isEqualTo(app.getString(R.string.mumlaConnecting))
    }

    @Test
    fun withTorTheTextSaysSo() {
        preferences().edit().putBoolean(Settings.PREF_USE_TOR, true).commit()

        service.renderSessionState(SessionState.Connecting)
        idle()

        assertThat(foregroundText()).isEqualTo(app.getString(R.string.mumlaConnecting) + " (Tor)")
    }

    @Test
    fun connectedShowsTheConnectedTextAndTheActions() {
        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Connected)
        idle()

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID).actions).hasLength(3)
    }

    @Test
    fun ourOwnMuteAndDeafenStateIsStoredAndShown() {
        connect()
        service.renderSessionState(SessionState.Connecting)
        idle()

        callbacks().onUserStateUpdated(user(SELF, muted = true, deafened = false))
        idle()
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.status_notify_muted))
        assertThat(Settings.getInstance(app).isMuted()).isTrue()
        assertThat(Settings.getInstance(app).isDeafened()).isFalse()

        callbacks().onUserStateUpdated(user(SELF, muted = true, deafened = true))
        idle()
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.status_notify_muted_and_deafened))
        assertThat(Settings.getInstance(app).isDeafened()).isTrue()

        callbacks().onUserStateUpdated(user(SELF))
        idle()
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
        assertThat(Settings.getInstance(app).isMuted()).isFalse()
    }

    @Test
    fun somebodyElsesStateChangesNothing() {
        connect()
        service.renderSessionState(SessionState.Connecting)
        idle()

        callbacks().onUserStateUpdated(user(SELF + 1, muted = true, deafened = true))
        idle()

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.mumlaConnecting))
        assertThat(Settings.getInstance(app).isMuted()).isFalse()
    }

    @Test
    fun aUserStateBeforeOurSessionIsKnownChangesNothing() {
        connect()
        every { connection.getSession() } throws NotSynchronizedException()
        service.renderSessionState(SessionState.Connecting)
        idle()

        callbacks().onUserStateUpdated(user(SELF, muted = true))
        callbacks().onUserStateUpdated(null)
        idle()

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.mumlaConnecting))
        assertThat(Settings.getInstance(app).isMuted()).isFalse()
    }

    @Test
    fun aNullUserStateChangesNothing() {
        connect()
        service.renderSessionState(SessionState.Connecting)
        idle()

        callbacks().onUserStateUpdated(null)
        idle()

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.mumlaConnecting))
    }

    @Test
    fun aPermissionDenialRepostsTheNotification() {
        service.renderSessionState(SessionState.Connecting)
        idle()
        val before = shadowOf(notificationManager).getNotification(FOREGROUND_ID)

        callbacks().onPermissionDenied("no")
        idle()

        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID)).isNotSameInstanceAs(before)
    }

    @Test
    fun aPermissionDenialRepostsNothingWhileNotificationsAreSuppressed() {
        service.renderSessionState(SessionState.Connecting)
        idle()
        val before = shadowOf(notificationManager).getNotification(FOREGROUND_ID)
        service.setSuppressNotifications(true)

        callbacks().onPermissionDenied("no")
        idle()

        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID)).isSameInstanceAs(before)
    }

    @Test
    fun aDisconnectWithAnErrorShowsTheReconnectPrompt() {
        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Disconnected(error()))
        idle()

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(reconnectPrompt()!!.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
    }

    @Test
    fun aDisconnectWithAnErrorShowsNoPromptWhileNotificationsAreSuppressed() {
        service.setSuppressNotifications(true)

        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Disconnected(error()))
        idle()

        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun aCleanDisconnectShowsNoPrompt() {
        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Disconnected(null))
        idle()

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun cancelReconnectHidesThePrompt() {
        service.renderSessionState(SessionState.Disconnected(error()))
        idle()

        service.cancelReconnect()

        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun theErrorCountsAsShownOnceTheUserDismissedOrAcknowledgedItUntilTheNextConnect() {
        assertThat(service.isErrorShown()).isFalse()
        service.onReconnectNotificationDismissed()
        assertThat(service.isErrorShown()).isTrue()

        service.renderSessionState(SessionState.Connecting)
        idle()
        assertThat(service.isErrorShown()).isFalse()

        service.markErrorShown()
        assertThat(service.isErrorShown()).isTrue()
    }

    @Test
    fun acknowledgingTheErrorHidesThePromptWhenNoReconnectIsUnderway() {
        service.renderSessionState(SessionState.Disconnected(error()))
        idle()

        service.markErrorShown()

        assertThat(reconnectPrompt()).isNull()
    }

    // ---- avatars --------------------------------------------------------------------------------

    @Test
    fun aUserWithAnUnfetchedAvatarHasItRequested() {
        connect()
        val other = user(9)
        every { other.getTextureHash() } returns byteArrayOf(1)

        callbacks().onUserConnected(other)
        callbacks().onUserStateUpdated(other)
        idle()

        assertThat(blobRequests().map { it.sessionTextureList }).containsExactly(listOf(9), listOf(9))
    }

    @Test
    fun anAvatarThatIsAlreadyThereOrDoesNotExistIsNotRequested() {
        connect()
        val fetched = user(9)
        every { fetched.getTextureHash() } returns byteArrayOf(1)
        every { fetched.getTexture() } returns byteArrayOf(2)
        val none = user(10)

        callbacks().onUserConnected(fetched)
        callbacks().onUserConnected(none)
        callbacks().onUserStateUpdated(fetched)
        callbacks().onUserStateUpdated(none)
        idle()

        assertThat(blobRequests()).isEmpty()
    }

    // ---- the session hooks ----------------------------------------------------------------------

    @Test
    fun synchronizingRestoresTheStoredMuteAndDeafenState() {
        preferences().edit().putBoolean(Settings.PREF_MUTED, true).putBoolean(Settings.PREF_DEAFENED, true).commit()
        connect()

        synchronize()

        assertThat(userStates().map { it.selfMute to it.selfDeaf }).containsExactly(true to true)
    }

    @Test
    fun synchronizingSendsNoStateWhenNeitherIsStored() {
        connect()

        synchronize()

        assertThat(userStates()).isEmpty()
    }

    @Test
    fun synchronizingStartsListeningForTalkBroadcasts() {
        connect()

        synchronize()

        assertThat(talkReceivers()).hasSize(1)
    }

    private fun talkReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction(TalkBroadcastReceiver.BROADCAST_TALK)
    }

    @Test
    fun synchronizingLeavesTheHotCornerAndTheProximityLockAloneWhenNotAskedFor() {
        connect()
        synchronize()

        verify(exactly = 0) { hotCorner.setShown(true) }
        assertThat(proximityLockHeld()).isFalse()
    }

    @Test
    fun synchronizingShowsTheHotCornerAndTakesTheProximityLockWhenAskedFor() {
        // Written while disconnected: the preference listener sees them and shows nothing yet.
        preferences().edit()
            .putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_TOP_LEFT)
            .putBoolean(Settings.PREF_HANDSET_MODE, true)
            .commit()
        verify(exactly = 0) { hotCorner.setShown(true) }
        connect()

        synchronize()

        verify(exactly = 1) { hotCorner.setShown(true) }
        assertThat(proximityLockHeld()).isTrue()
    }

    private fun proximityLockHeld(): Boolean {
        val lock = mumlaField("mProximityLock").get(service) as PowerManager.WakeLock?
        return lock != null && lock.isHeld &&
            shadowOf(lock).tag == "Mumla:Proximity" &&
            ShadowPowerManager.getLatestWakeLock() === lock
    }

    @Test
    fun aDisconnectTearsDownWhatTheSessionShowed() {
        preferences().edit().putBoolean(Settings.PREF_HANDSET_MODE, true).putBoolean(Settings.PREF_CHAT_NOTIFY, true).commit()
        connect()
        service.logWarning("old")
        callbacks().onMessageLogged(textMessage("ping"))
        idle()
        synchronize()
        val lock = mumlaField("mProximityLock").get(service) as PowerManager.WakeLock

        service.onConnectionDisconnected(null)
        idle()

        assertThat(talkReceivers()).isEmpty()
        verify { overlay.hide() }
        verify { hotCorner.setShown(false) }
        assertThat(lock.isHeld).isFalse()
        assertThat(mumlaField("mProximityLock").get(service)).isNull()
        // Changed in task 12 (spec A3): the chat log and the chat notification survive a loss;
        // only the Disconnected state clears them. See the next test.
        assertThat(service.getMessageLog()).isNotEmpty()
    }

    @Test
    fun theDisconnectedStateClearsTheChatLogAndTheChatNotification() {
        preferences().edit().putBoolean(Settings.PREF_CHAT_NOTIFY, true).commit()
        connect()
        service.logWarning("old")
        callbacks().onMessageLogged(textMessage("ping"))
        idle()
        assertThat(shadowOf(notificationManager).allNotifications).isNotEmpty()

        service.renderSessionState(SessionState.Disconnected(null))

        assertThat(service.getMessageLog()).isEmpty()
        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
    }

    // ---- the talk keys --------------------------------------------------------------------------

    private fun pushToTalk(toggle: Boolean) {
        preferences().edit()
            .putString(Settings.PREF_INPUT_METHOD, Settings.ARRAY_INPUT_METHOD_PTT)
            .putBoolean(Settings.PREF_PTT_TOGGLE, toggle)
            .commit()
    }

    @Test
    fun holdToTalkTalksWhileTheKeyIsDown() {
        connect()
        pushToTalk(toggle = false)

        service.onTalkKeyDown()
        assertThat(service.isTalking()).isTrue()
        service.onTalkKeyUp()
        assertThat(service.isTalking()).isFalse()
    }

    @Test
    fun toggleToTalkFlipsOnEveryRelease() {
        connect()
        pushToTalk(toggle = true)

        service.onTalkKeyDown()
        assertThat(service.isTalking()).isFalse()
        service.onTalkKeyUp()
        assertThat(service.isTalking()).isTrue()
        service.onTalkKeyDown()
        assertThat(service.isTalking()).isTrue()
        service.onTalkKeyUp()
        assertThat(service.isTalking()).isFalse()
    }

    @Test
    fun theTalkKeysDoNothingOutsidePushToTalk() {
        connect()
        preferences().edit().putString(Settings.PREF_INPUT_METHOD, Settings.ARRAY_INPUT_METHOD_VOICE).commit()

        service.onTalkKeyDown()
        assertThat(service.isTalking()).isFalse()
        service.setTalkingState(true)
        service.onTalkKeyUp()
        assertThat(service.isTalking()).isTrue()
    }

    @Test
    fun theTalkKeysDoNothingWithoutAConnection() {
        pushToTalk(toggle = false)

        service.onTalkKeyDown()
        assertThat(service.isTalking()).isFalse()
        service.setTalkingState(true)
        service.onTalkKeyUp()
        assertThat(service.isTalking()).isTrue()
    }

    @Test
    fun toggleModeWithoutAConnectionDoesNotFlip() {
        pushToTalk(toggle = true)

        service.onTalkKeyUp()

        assertThat(service.isTalking()).isFalse()
    }

    @Test
    fun aHoldReleaseWhileNotTalkingStaysSilent() {
        connect()
        pushToTalk(toggle = false)

        service.onTalkKeyUp()

        assertThat(service.isTalking()).isFalse()
    }

    // ---- mute and deafen from the notification ------------------------------------------------

    @Test
    fun muteToggleFlipsMuteAndDropsDeafenWhenUnmuting() {
        connect(muted = true, deafened = true)

        service.onMuteToggled()

        assertThat(userStates().map { it.selfMute to it.selfDeaf }).containsExactly(false to false)
    }

    @Test
    fun muteToggleKeepsDeafenWhenMuting() {
        connect(muted = false, deafened = true)

        service.onMuteToggled()

        assertThat(userStates().map { it.selfMute to it.selfDeaf }).containsExactly(true to true)
    }

    @Test
    fun deafenToggleSetsBothFromTheDeafenState() {
        connect(muted = false, deafened = false)
        service.onDeafenToggled()
        connect(muted = true, deafened = true)
        service.onDeafenToggled()

        assertThat(userStates().map { it.selfMute to it.selfDeaf }).containsExactly(true to true, false to false).inOrder()
    }

    /** Characterized, not endorsed: the buttons only exist while connected, so nothing reaches this. */
    @Test
    fun theTogglesThrowWithoutASession() {
        assertThrows(IllegalStateException::class.java) { service.onMuteToggled() }
        assertThrows(IllegalStateException::class.java) { service.onDeafenToggled() }
    }

    @Test
    fun theTogglesDoNothingWithoutOurUser() {
        connect()
        val model = mockk<ModelHandler>(relaxed = true)
        every { model.getUser(any()) } returns null
        humlaField("mModelHandler").set(service, model)
        service.onMuteToggled()
        service.onDeafenToggled()

        assertThat(userStates()).isEmpty()
    }

    @Test
    fun theTogglesDoNothingWhileTheConnectionIsNotEstablished() {
        connect()
        every { connection.isConnected } returns false

        service.onMuteToggled()
        service.onDeafenToggled()

        assertThat(userStates()).isEmpty()
    }

    // ---- the overlay ----------------------------------------------------------------------------

    @Test
    fun theOverlayToggleShowsAHiddenOverlayWhenAllowed() {
        every { overlay.isShown } returns false
        shadowOf(app).grantPermissions(android.Manifest.permission.SYSTEM_ALERT_WINDOW)
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

    /** Characterized, not endorsed: the argument is ignored and the call toggles. */
    @Test
    fun setOverlayShownTogglesWhateverItIsAsked() {
        every { overlay.isShown } returns false
        service.setOverlayShown(false)
        verify(exactly = 1) { overlay.show() }

        every { overlay.isShown } returns true
        service.setOverlayShown(true)
        verify(exactly = 1) { overlay.hide() }
    }

    @Test
    fun isOverlayShownAsksTheOverlay() {
        every { overlay.isShown } returns true
        assertThat(service.isOverlayShown()).isTrue()
        every { overlay.isShown } returns false
        assertThat(service.isOverlayShown()).isFalse()
    }

    @Test
    fun theHotCornerDrivesTheTalkKeys() {
        connect()
        pushToTalk(toggle = false)
        val listener = mumlaField("mHotCornerListener").get(service) as MumlaHotCorner.MumlaHotCornerListener

        listener.onHotCornerDown()
        assertThat(service.isTalking()).isTrue()
        listener.onHotCornerUp()
        assertThat(service.isTalking()).isFalse()
    }

    // ---- preferences that are not audio extras --------------------------------------------------

    @Test
    fun switchingToPushToTalkShowsTheOverlayButton() {
        // The commits alone: the service is registered as the preference listener.
        pushToTalk(toggle = false)
        preferences().edit().putString(Settings.PREF_INPUT_METHOD, Settings.ARRAY_INPUT_METHOD_VOICE).commit()

        verify(exactly = 1) { overlay.setPushToTalkShown(true) }
        verify(exactly = 1) { overlay.setPushToTalkShown(false) }
    }

    @Test
    fun theHandsetPreferenceHoldsTheProximityLockOnlyWhileConnected() {
        preferences().edit().putBoolean(Settings.PREF_HANDSET_MODE, true).commit()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_HANDSET_MODE)
        assertThat(proximityLockHeld()).isFalse()

        connect()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_HANDSET_MODE)
        assertThat(proximityLockHeld()).isTrue()

        preferences().edit().putBoolean(Settings.PREF_HANDSET_MODE, false).commit()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_HANDSET_MODE)
        assertThat(proximityLockHeld()).isFalse()
    }

    @Test
    fun theHotCornerPreferenceMovesItAndShowsItOnlyWhileConnected() {
        preferences().edit().putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_TOP_LEFT).commit()
        verify { hotCorner.setGravity(Settings.getInstance(app).getHotCornerGravity()) }
        verify(exactly = 1) { hotCorner.setShown(false) }

        connect()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_HOT_CORNER_KEY)
        verify(exactly = 1) { hotCorner.setShown(true) }

        preferences().edit().putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_NONE).commit()
        verify(exactly = 2) { hotCorner.setShown(false) }
    }

    @Test
    fun aSettingThatNeedsAReconnectSaysSoWhileConnected() {
        for (key in listOf(Settings.PREF_CERT_ID, Settings.PREF_FORCE_TCP, Settings.PREF_USE_TOR, Settings.PREF_DISABLE_OPUS)) {
            ShadowToast.reset()
            service.onSharedPreferenceChanged(preferences(), key)
            assertThat(ShadowToast.getLatestToast()).isNull()

            connect()
            service.onSharedPreferenceChanged(preferences(), key)
            assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(app.getString(R.string.change_requires_reconnect))
            humlaField("mConnection").set(service, null)
        }
    }

    @Test
    fun anOrdinarySettingSaysNothingAboutReconnecting() {
        connect()

        service.onSharedPreferenceChanged(preferences(), Settings.PREF_PTT_SOUND)

        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    // ---- binding and lifecycle ------------------------------------------------------------------

    @Test
    fun theBinderHandsOutThisService() {
        val binder = service.onBind(Intent()) as MumlaService.MumlaBinder

        assertThat(binder.getService()).isSameInstanceAs(service)
    }

    @Test
    fun reconnectAsksForAConnection() {
        service.configureExtras(
            android.os.Bundle().apply {
                putParcelable(HumlaService.EXTRAS_SERVER, se.lublin.humla.model.Server(-1, "t", "127.0.0.1", 64738, "me", ""))
            },
        )
        service.connectionFactory = { mockk(relaxed = true) }

        service.reconnect()

        assertThat(service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTING)
    }

    @Test
    fun destroyingTheServiceLetsGoOfEverything() {
        val tts = installTts()
        connect()
        synchronize()
        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Disconnected(error()))
        idle()
        service.renderSessionState(SessionState.Connecting)
        idle()

        preferences().edit().putBoolean(Settings.PREF_CHAT_NOTIFY, true).commit()

        controller.destroy()
        destroyed = true
        idle()

        verify { tts.shutdown() }
        assertThat(talkReceivers()).isEmpty()
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(reconnectPrompt()).isNull()
        // The observer and the preference listener are gone: neither reaches the service now. A
        // registered observer would post a chat notification here (and add to a log that is gone).
        callbacks().onMessageLogged(textMessage("after"))
        idle()
        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
        preferences().edit().putString(Settings.PREF_INPUT_METHOD, Settings.ARRAY_INPUT_METHOD_PTT).commit()
        verify(exactly = 0) { overlay.setPushToTalkShown(any()) }
    }

    // ---- the push-to-talk click (added with the Kotlin conversion's seam; not characterization) --

    private var clicks = 0

    /** All five clauses true; each test below turns exactly one of them false. */
    private fun clickReady(): User {
        service.keyClickSound = { clicks++ }
        service.configureExtras(android.os.Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, se.lublin.humla.Constants.TRANSMIT_PUSH_TO_TALK) })
        preferences().edit().putBoolean(Settings.PREF_PTT_SOUND, true).commit()
        connect()
        val talking = user(SELF)
        every { talking.getTalkState() } returns TalkState.TALKING
        return talking
    }

    private fun talk(user: User) {
        callbacks().onUserTalkStateUpdated(user)
        idle()
    }

    @Test
    fun startingToTalkInPushToTalkClicks() {
        talk(clickReady())
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun noClickWithoutAnEstablishedConnection() {
        val u = clickReady()
        every { connection.isConnected } returns false
        talk(u)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickForSomebodyElse() {
        clickReady()
        val other = user(SELF + 1)
        every { other.getTalkState() } returns TalkState.TALKING
        talk(other)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickOutsidePushToTalk() {
        val u = clickReady()
        service.configureExtras(android.os.Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, se.lublin.humla.Constants.TRANSMIT_VOICE_ACTIVITY) })
        talk(u)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickWhenTheTalkStateIsNotTalking() {
        val u = clickReady()
        every { u.getTalkState() } returns TalkState.PASSIVE
        talk(u)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickWhenTheSoundIsOff() {
        val u = clickReady()
        preferences().edit().putBoolean(Settings.PREF_PTT_SOUND, false).commit()
        talk(u)
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun noClickBeforeOurSessionIsKnown() {
        val u = clickReady()
        every { connection.getSession() } throws NotSynchronizedException()
        talk(u)
        assertThat(clicks).isEqualTo(0)
    }

    // ---- added in the sweep round of task 12 ----------------------------------------------------

    private fun postedActions(): Array<Notification.Action>? =
        shadowOf(notificationManager).getNotification(FOREGROUND_ID)?.actions

    /**
     * Connecting shows nothing; a lost connection shows only "Cancel reconnect" -- the session
     * buttons go, since there is no session to mute. Changed on purpose: the loss used to show no
     * action at all, which left no way to give up on the reconnect from the notification.
     */
    @Test
    fun connectingShowsNoActionsAndALostConnectionOnlyTheCancel() {
        service.renderSessionState(SessionState.Connecting)
        assertThat(postedActions()).isNull()
        service.renderSessionState(SessionState.Connected)
        service.renderSessionState(SessionState.ConnectionLost(2_000, 1, error()))
        assertThat(postedActions()!!.map { it.title.toString() })
            .containsExactly(service.getString(R.string.cancel_reconnect))
        service.renderSessionState(SessionState.Reconnecting(error()))
        assertThat(postedActions()!!.map { it.title.toString() })
            .containsExactly(service.getString(R.string.cancel_reconnect))
        service.renderSessionState(SessionState.Connected)
        assertThat(postedActions()!!.map { it.title.toString() })
            .doesNotContain(service.getString(R.string.cancel_reconnect))
    }

    @Test
    fun connectingHidesAPromptLeftFromTheLastSession() {
        service.renderSessionState(SessionState.Disconnected(error()))
        assertThat(reconnectPrompt()).isNotNull()

        service.renderSessionState(SessionState.Connecting)

        assertThat(reconnectPrompt()).isNull()
    }

    @Test
    fun withTorTheConnectedTextAndThePromptSaySo() {
        preferences().edit().putBoolean(Settings.PREF_USE_TOR, true).commit()

        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Connected)
        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected) + " (Tor)")

        service.renderSessionState(SessionState.Disconnected(error()))
        assertThat(reconnectPrompt()!!.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset (Tor)")
    }

    private fun promptReceivers() = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction("b_reconnect") }

    @Test
    fun aNewPromptReplacesTheOldOneIncludingItsReceiver() {
        service.renderSessionState(SessionState.Disconnected(error()))
        service.renderSessionState(SessionState.Disconnected(HumlaException("again", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)))

        assertThat(promptReceivers()).hasSize(1)
    }

    @Test
    fun aRefusalPromptReplacesAnOlderPromptIncludingItsReceiver() {
        service.renderSessionState(SessionState.Disconnected(error()))
        shadowOf(service).setThrowInStartForeground(SecurityException("refused"))

        service.renderSessionState(SessionState.Connecting)
        service.renderSessionState(SessionState.Connected)

        assertThat(promptReceivers()).hasSize(1)
    }

    @Test
    fun ourOwnStateOutsideASessionDoesNotEnterTheForeground() {
        connect()

        callbacks().onUserStateUpdated(user(SELF, muted = true))
        callbacks().onPermissionDenied("no")
        idle()

        assertThat(shadowOf(service).lastForegroundNotification).isNull()
        assertThat(shadowOf(notificationManager).getNotification(FOREGROUND_ID)).isNull()
    }

    @Test
    fun deafenedWithoutMuteReadsAsConnected() {
        connect()
        service.renderSessionState(SessionState.Connecting)

        callbacks().onUserStateUpdated(user(SELF, muted = false, deafened = true))
        idle()

        assertThat(postedText(FOREGROUND_ID)).isEqualTo(app.getString(R.string.connected))
    }

    @Test
    fun synchronizingRestoresADeafenStoredWithoutMute() {
        preferences().edit().putBoolean(Settings.PREF_DEAFENED, true).commit()
        connect()

        synchronize()

        assertThat(userStates().map { it.selfMute to it.selfDeaf }).containsExactly(false to true)
    }

    /** Other apps (automation, headset helpers) send the talk broadcast: exported on purpose. */
    @Test
    fun theTalkReceiverIsExported() {
        connect()
        synchronize()

        val receiver = talkReceivers().single()
        assertThat(receiver.flags and android.content.Context.RECEIVER_EXPORTED).isNotEqualTo(0)
    }

    @Test
    fun aSynchronizationTheSuperclassRejectsGoesNoFurther() {
        preferences().edit().putBoolean(Settings.PREF_MUTED, true).commit()
        humlaField("mConnection").set(service, null) // super dereferences it: NullPointerException

        service.onConnectionSynchronized()

        assertThat(talkReceivers()).isEmpty()
    }

    @Test
    fun destroyingTheServiceRemovesThePromptAndTheChatNotification() {
        connect()
        preferences().edit().putBoolean(Settings.PREF_CHAT_NOTIFY, true).commit()
        service.renderSessionState(SessionState.Disconnected(error()))
        callbacks().onMessageLogged(textMessage("ping"))
        idle()
        assertThat(shadowOf(notificationManager).allNotifications).hasSize(2)

        controller.destroy()
        destroyed = true

        assertThat(shadowOf(notificationManager).allNotifications).isEmpty()
    }

    private companion object {
        const val SELF = 7
        const val FOREGROUND_ID = 1
        const val RECONNECT_ID = 3
    }
}
