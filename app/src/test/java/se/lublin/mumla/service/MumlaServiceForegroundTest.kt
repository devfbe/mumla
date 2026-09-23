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
}
