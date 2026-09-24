package se.lublin.humla

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.lublin.humla.model.Server
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A connection attempt that fails reports the failure; it does not throw at its caller.
 *
 * `onConnecting()` is delivered inline on the handler thread, and an observer that calls
 * disconnect() from it marks the connection that connect() is about to start as disconnected.
 * That must end as a reported failure, not a throw out of onStartCommand or the reconnect runnable.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceConnectCancellationTest {

    private val server = Server(-1, "test", "127.0.0.1", 64738, "user", "")

    @Test
    fun anObserverThatDisconnectsFromOnConnectingGetsAFailureReportInsteadOfACrash() {
        val intent = Intent(RuntimeEnvironment.getApplication(), HumlaService::class.java)
            .setAction(HumlaService.ACTION_CONNECT)
            .putExtra(HumlaService.EXTRAS_SERVER, server)
        val controller = Robolectric.buildService(HumlaService::class.java, intent).create()
        val service = controller.get()
        val disconnects = CopyOnWriteArrayList<HumlaException?>()
        service.registerObserver(object : HumlaObserver() {
            override fun onConnecting() = service.disconnect()
            override fun onDisconnected(e: HumlaException?) { disconnects += e }
        })

        controller.startCommand(0, 0)

        assertThat(disconnects).hasSize(1)
        assertThat(disconnects[0]).isNotNull()
        assertThat(service.connectionState).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
    }
}
