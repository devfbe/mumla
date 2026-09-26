package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.testutil.onEvents
import se.lublin.humla.exception.HumlaException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A connection attempt that fails reports the failure; it does not throw at its caller.
 *
 * `Connecting` reaches a main-thread collector inline, and a collector that calls
 * disconnect() on it marks the connection that connect() is about to start as disconnected.
 * That must end as a reported failure, not a throw out of connect() or the reconnect runnable.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceConnectCancellationTest {

    private val server = Server(-1, "test", "127.0.0.1", 64738, "user", "")

    @Test
    fun aCollectorThatDisconnectsOnConnectingGetsAFailureReportInsteadOfACrash() {
        val service = Robolectric.buildService(HumlaService::class.java).create().get()
        service.configure(SessionConfig(server = server))
        val disconnects = CopyOnWriteArrayList<HumlaException?>()
        service.onEvents { event ->
            when (event) {
                HumlaEvent.Connecting -> service.disconnect()
                is HumlaEvent.Disconnected -> disconnects += event.error
                else -> Unit
            }
        }

        service.connect()

        assertThat(disconnects).hasSize(1)
        assertThat(disconnects[0]).isNotNull()
        assertThat(service.connectionState).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
    }
}
