package se.lublin.mumla.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import se.lublin.humla.model.Server
import se.lublin.humla.session.ConnectionConfig
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.ScriptedConnections
import se.lublin.mumla.R
import se.lublin.mumla.app.AppContainer
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.createMumlaService
import java.time.Duration

/**
 * The foreground service over a real session and its reconnect policy, with the connections
 * mocked: a loss that will be retried keeps the foreground, which Android would refuse to start
 * again from the background, and only the end of the session leaves it.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceForegroundTest {
    private val app = ApplicationProvider.getApplicationContext<MumlaApplication>()
    private lateinit var controller: ServiceController<MumlaService>
    private lateinit var service: MumlaService
    private lateinit var sessions: SessionManager
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val server = ScriptedConnections(reconnectBaseDelayMillis = 2_000L, reconnectAttempts = 2)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        app.installContainer(
            AppContainer(app, app.scope) { config -> server.session(app, config) },
        )
        sessions = SessionManager.get(app)
        sessions.connect(
            SessionConfig(
                ConnectionConfig(server = Server(-1, "test", "127.0.0.1", 64738, "me", "")),
                autoReconnect = true,
            ),
        )
        controller = createMumlaService()
        service = controller.get()
        mainLooper.idle()
    }

    @After
    fun tearDown() {
        controller.destroy()
        sessions.session.value?.close()
        mainLooper.idle()
    }

    private val state: SessionState get() = sessions.currentState

    /** The current connection reports its end, as a dropped socket does. */
    private fun loseConnection() {
        server.loseLatest("socket reset")
        mainLooper.idle()
    }

    private fun synchronize() {
        server.synchronizeLatest()
        mainLooper.idle()
    }

    private val notificationManager get() = app.getSystemService(NotificationManager::class.java)

    private fun foregroundText(): String? =
        shadowOf(notificationManager).getNotification(1)?.extras?.getString(Notification.EXTRA_TEXT)

    private fun foregroundActions(): List<String> =
        shadowOf(notificationManager).getNotification(1)?.actions.orEmpty().map { it.title.toString() }

    private fun reconnectPrompt(): Notification? = shadowOf(notificationManager).getNotification(3)

    private fun log() = sessions.chat.messages.value.map { it.body }

    /** From here on the platform refuses every foreground start, as with the screen off. */
    private fun screenOff() {
        shadowOf(service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("startForeground() not allowed from the background"),
        )
    }

    private fun pressCancelReconnect() {
        shadowOf(notificationManager).getNotification(1)!!.actions
            .single { it.title.toString() == app.getString(R.string.cancel_reconnect) }
            .actionIntent.send()
        mainLooper.idle()
    }

    @Test
    fun aConnectionLossThatWillBeRetriedKeepsTheServiceInTheForeground() {
        synchronize()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        screenOff()

        loseConnection()

        assertThat(state).isInstanceOf(SessionState.ConnectionLost::class.java)
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(foregroundText()).isEqualTo(app.getString(R.string.connection_lost_reconnecting))
        assertThat(foregroundActions()).containsExactly(app.getString(R.string.cancel_reconnect))
    }

    @Test
    fun theReconnectAttemptItselfStaysInTheForegroundWithoutStartingItAgain() {
        screenOff()
        loseConnection()

        mainLooper.idleFor(Duration.ofMillis(2_000))

        assertThat(server.attempts).isEqualTo(2) // the backoff timer fired and a new attempt started
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(log()).doesNotContain(app.getString(R.string.foreground_start_failed))
    }

    @Test
    fun aDisconnectTheUserAskedForLeavesTheForegroundAndStopsTheService() {
        sessions.disconnect()
        mainLooper.idle()

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun theForegroundFallsOnlyWhenThePolicyGivesUp() {
        screenOff()
        loseConnection() // attempt 1 of 2: retried
        mainLooper.idleFor(Duration.ofMillis(2_000))
        loseConnection() // attempt 2 of 2: retried
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        mainLooper.idleFor(Duration.ofMillis(4_000))

        loseConnection() // spent: Disconnected

        assertThat(state).isInstanceOf(SessionState.Disconnected::class.java)
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
    }

    @Test
    fun whenThePolicyGivesUpThePromptOffersAReconnectAndTheLogKeepsOnlyTheGiveUpLine() {
        synchronize()
        sessions.chat.warnOnce("before")
        for (delay in listOf(2_000L, 4_000L)) {
            loseConnection()
            mainLooper.idleFor(Duration.ofMillis(delay))
        }
        assertThat(log()).contains("before") // a loss is not the end

        loseConnection()

        val prompt = reconnectPrompt()!!
        assertThat(prompt.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
        assertThat(prompt.actions.single().title.toString()).isEqualTo(app.getString(R.string.reconnect))
        assertThat(log()).containsExactly(app.getString(se.lublin.humla.R.string.reconnect_gave_up))
    }

    @Test
    fun theCancelActionEndsAWaitingReconnectAndLeavesTheForeground() {
        screenOff()
        loseConnection()

        pressCancelReconnect()

        assertThat(state).isInstanceOf(SessionState.Disconnected::class.java)
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(reconnectPrompt()).isNull() // the user asked for this; nothing to report
        mainLooper.idleFor(Duration.ofMillis(10_000))
        assertThat(server.attempts).isEqualTo(1) // the backoff timer no longer retries
    }

    @Test
    fun theCancelActionDuringAnAttemptInFlightDisconnectsIt() {
        screenOff()
        loseConnection()
        mainLooper.idleFor(Duration.ofMillis(2_000)) // Reconnecting: attempt 2 is in flight
        assertThat(server.attempts).isEqualTo(2)
        assertThat(foregroundActions()).containsExactly(app.getString(R.string.cancel_reconnect))

        pressCancelReconnect()

        assertThat(server.disconnectCalls(1)).isEqualTo(1)
        assertThat(state).isInstanceOf(SessionState.Disconnected::class.java)
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
    }

    @Test
    fun aDestroyedServiceNoLongerRendersTheSession() {
        controller.destroy()
        mainLooper.idle()
        val before = shadowOf(notificationManager).getNotification(1)

        sessions.connect(SessionConfig(ConnectionConfig(server = Server(-1, "t", "127.0.0.1", 1, "me", ""))))
        mainLooper.idle()

        assertThat(shadowOf(notificationManager).getNotification(1)).isSameInstanceAs(before)
    }
}
