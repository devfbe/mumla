package se.lublin.mumla.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.os.Bundle
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import se.lublin.humla.HumlaService
import se.lublin.humla.model.Server
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.util.HumlaException
import se.lublin.mumla.R
import java.time.Duration

/**
 * "The microphone is dead when the screen is off" (spec A3/A6), at the level of the service.
 *
 * The session runs through the real HumlaService and its state machine; only the connection is a
 * relaxed mock, so connect() opens nothing and the test delivers the connection's callbacks
 * itself. The platform's refusal is modelled with ShadowService.setThrowInStartForeground: once
 * the first start has succeeded, every later startForeground throws, exactly as it does on a
 * device whose screen is off (the restriction is re-checked on every call).
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceForegroundTest {
    private lateinit var controller: ServiceController<MumlaService>
    private lateinit var service: MumlaService
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val connections = mutableListOf<HumlaConnection>()

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext()).edit().clear().commit()
        controller = Robolectric.buildService(MumlaService::class.java)
        service = controller.get()
        service.reconnectPolicy = ReconnectPolicy(baseDelayMillis = 2_000L, maxAttempts = 2, maxJitterFraction = 0.0)
        service.connectionFactory = {
            mockk<HumlaConnection>(relaxed = true).also { connections += it }
        }
        controller.create()
        service.configureExtras(
            Bundle().apply {
                putParcelable(HumlaService.EXTRAS_SERVER, Server(-1, "test", "127.0.0.1", 64738, "me", ""))
                putBoolean(HumlaService.EXTRAS_AUTO_RECONNECT, true)
            },
        )
        mainLooper.idle()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun lost() = HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

    private fun foregroundText(): String? =
        shadowOf(service.getSystemService(android.app.NotificationManager::class.java))
            .getNotification(1)?.extras?.getString(Notification.EXTRA_TEXT)

    /** From here on the platform refuses every foreground start, as with the screen off. */
    private fun screenOff() {
        shadowOf(service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("startForeground() not allowed from the background"),
        )
    }

    @Test
    fun aConnectionLossThatWillBeRetriedKeepsTheServiceInTheForeground() {
        service.connect()
        mainLooper.idle()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        screenOff()

        service.onConnectionDisconnected(lost())
        mainLooper.idle()

        assertThat(service.isReconnecting()).isTrue()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(foregroundText()).isEqualTo(service.getString(R.string.connection_lost_reconnecting))
    }

    @Test
    fun theReconnectAttemptItselfStaysInTheForegroundWithoutStartingItAgain() {
        service.connect()
        mainLooper.idle()
        screenOff()
        service.onConnectionDisconnected(lost())
        mainLooper.idle()

        mainLooper.idleFor(Duration.ofMillis(2_000))

        assertThat(connections).hasSize(2) // the backoff timer fired and a new attempt started
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(service.getMessageLog().map { it.body })
            .doesNotContain(service.getString(R.string.foreground_start_failed))
    }

    @Test
    fun aDisconnectTheUserAskedForLeavesTheForeground() {
        service.connect()
        mainLooper.idle()

        service.disconnect()
        mainLooper.idle()

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
    }

    @Test
    fun theForegroundFallsOnlyWhenThePolicyGivesUp() {
        service.connect()
        mainLooper.idle()
        screenOff()
        service.onConnectionDisconnected(lost()) // attempt 1 of 2: retried
        mainLooper.idle()
        mainLooper.idleFor(Duration.ofMillis(2_000))
        service.onConnectionDisconnected(lost()) // attempt 2 of 2: retried
        mainLooper.idle()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        mainLooper.idleFor(Duration.ofMillis(4_000))

        service.onConnectionDisconnected(lost()) // spent: Disconnected
        mainLooper.idle()

        assertThat(service.isReconnecting()).isFalse()
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
    }

    // ---- the chat log across the session (spec A3, D5) ------------------------------------------

    private fun log() = service.getMessageLog().map { it.body }

    @Test
    fun theChatLogSurvivesAConnectionLossAndIsClearedOnDisconnect() {
        service.connect()
        service.logWarning("something happened")
        mainLooper.idle()

        service.onConnectionDisconnected(lost())
        mainLooper.idle()
        assertThat(log()).containsExactly("something happened") // a loss is not the end

        service.disconnect()
        mainLooper.idle()
        assertThat(log()).isEmpty()
    }

    @Test
    fun theGiveUpLineIsTheOneThingLeftInTheChatLog() {
        service.connect()
        mainLooper.idle()
        service.logWarning("before")
        for (delay in listOf(2_000L, 4_000L)) {
            service.onConnectionDisconnected(lost())
            mainLooper.idle()
            mainLooper.idleFor(Duration.ofMillis(delay))
        }

        service.onConnectionDisconnected(lost())
        mainLooper.idle()

        assertThat(log()).containsExactly(service.getString(se.lublin.humla.R.string.reconnect_gave_up))
    }

    @Test
    fun theChatLogIsBoundedAtFiveHundredEntries() {
        repeat(ChatMessageLog.MAX_ENTRIES + 1) { service.logWarning("m$it") }
        mainLooper.idle()

        assertThat(service.getMessageLog()).hasSize(ChatMessageLog.MAX_ENTRIES)
        assertThat(service.getMessageLog().first().body).isEqualTo("m1")
    }

    // ---- the reconnect prompt -------------------------------------------------------------------

    private fun reconnectPrompt(): Notification? =
        shadowOf(service.getSystemService(android.app.NotificationManager::class.java)).getNotification(3)

    @Test
    fun whenThePolicyGivesUpThePromptOffersAReconnect() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        service.connect()
        for (delay in listOf(2_000L, 4_000L)) {
            service.onConnectionDisconnected(lost())
            mainLooper.idle()
            mainLooper.idleFor(Duration.ofMillis(delay))
        }
        service.onConnectionDisconnected(lost())
        mainLooper.idle()

        val prompt = reconnectPrompt()!!
        assertThat(prompt.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
        assertThat(prompt.actions.single().title.toString()).isEqualTo(service.getString(R.string.reconnect))
    }

    @Test
    fun cancellingTheReconnectEndsTheSessionWithoutAPrompt() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        service.connect()
        service.onConnectionDisconnected(lost())
        mainLooper.idle()

        service.cancelReconnect()
        mainLooper.idle()

        assertThat(service.isReconnecting()).isFalse()
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(reconnectPrompt()).isNull() // the user asked for this; nothing to report
    }

    @Test
    fun dismissingTheChatNotificationLeavesThePromptAlone() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        service.renderSessionState(se.lublin.humla.session.SessionState.Disconnected(lost()))

        service.clearChatNotifications()

        assertThat(reconnectPrompt()).isNotNull()
    }

    // ---- spec A6: a refused start ---------------------------------------------------------------

    @Test
    fun aRefusedForegroundStartBecomesAWarningAndAPromptInsteadOfACrash() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        screenOff()

        service.connect()
        mainLooper.idle()

        assertThat(log()).containsExactly(service.getString(R.string.foreground_start_failed))
        assertThat(reconnectPrompt()!!.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo(service.getString(R.string.foreground_start_failed))
    }

    @Test
    fun aRefusalThatRepeatsIsReportedOnce() {
        screenOff()
        service.connect()
        mainLooper.idle()

        service.renderSessionState(se.lublin.humla.session.SessionState.Connected)
        mainLooper.idle()

        assertThat(log()).containsExactly(service.getString(R.string.foreground_start_failed))
    }

    @Test
    fun aRefusalIsNotShownAsAPromptWhileNotificationsAreSuppressed() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        service.setSuppressNotifications(true)
        screenOff()

        service.connect()
        mainLooper.idle()

        assertThat(reconnectPrompt()).isNull()
        assertThat(log()).containsExactly(service.getString(R.string.foreground_start_failed))
    }

    // ---- spec A7 ---------------------------------------------------------------------------------

    /**
     * Half duplex follows the transmit mode the service is in, which the connect intent always
     * carries (ServerConnectTask) -- a settings write that carries only half duplex is enough.
     */
    @Test
    fun aHalfDuplexPreferenceChangeTakesEffectInPushToTalk() {
        service.configureExtras(Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, se.lublin.humla.Constants.TRANSMIT_PUSH_TO_TALK) })
        val preferences = PreferenceManager.getDefaultSharedPreferences(service)

        preferences.edit().putBoolean(se.lublin.mumla.Settings.PREF_HALF_DUPLEX, true).commit()
        assertThat(service.getAudioConfigForTest().halfDuplex).isTrue()

        preferences.edit().putBoolean(se.lublin.mumla.Settings.PREF_HALF_DUPLEX, false).commit()
        assertThat(service.getAudioConfigForTest().halfDuplex).isFalse()
    }
}
