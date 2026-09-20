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
 * Spec §4.1: a connection attempt that fails reports the failure; it does not throw at its caller.
 *
 * HumlaService.connect() lost its try/catch when host resolution moved onto the protocol thread,
 * and HumlaConnection.connect() gained an unchecked throw at the same time. The two meet in one
 * interleaving that costs a crash instead of a reported failure: onConnecting() is raised on the
 * handler thread with an empty queue, so it is delivered inline, and an observer that calls
 * disconnect() from it marks the connection that connect() is about to start as already
 * disconnected. connect() then throws out of onStartCommand, or out of the reconnect runnable.
 *
 * Neither observer in the tree does this today, which is why it is worth a test rather than a note:
 * the next one will not be checked against a comment.
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
