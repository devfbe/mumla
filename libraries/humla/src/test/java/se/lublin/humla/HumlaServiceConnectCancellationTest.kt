package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A connection attempt that fails reports the failure; it does not throw at its caller.
 *
 * `onConnecting()` is delivered inline on the handler thread, and an observer that calls
 * disconnect() from it marks the connection that connect() is about to start as disconnected.
 * That must end as a reported failure, not a throw out of connect() or the reconnect runnable.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceConnectCancellationTest {

    private val server = Server(-1, "test", "127.0.0.1", 64738, "user", "")

    @Test
    fun anObserverThatDisconnectsFromOnConnectingGetsAFailureReportInsteadOfACrash() {
        val service = Robolectric.buildService(HumlaService::class.java).create().get()
        service.configure(SessionConfig(server = server))
        val disconnects = CopyOnWriteArrayList<HumlaException?>()
        service.registerObserver(object : HumlaObserver() {
            override fun onConnecting() = service.disconnect()
            override fun onDisconnected(e: HumlaException?) { disconnects += e }
        })

        service.connect()

        assertThat(disconnects).hasSize(1)
        assertThat(disconnects[0]).isNotNull()
        assertThat(service.connectionState).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
    }
}
