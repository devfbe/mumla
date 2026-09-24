package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * A service destroyed while connected has to end its connection: the protocol thread is non-daemon
 * and only [HumlaConnection.disconnect] quits its looper.
 *
 * This pins only that onDestroy calls disconnect() once. The effect (the thread ending) is covered
 * by `HumlaConnectionProtocolThreadTest`; reaching a real connection here would need a socket.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceDestroyTest {

    /** Records the call rather than the effect: without a connection there is no effect to see. */
    class DisconnectRecordingService : HumlaService() {
        val disconnectCalls = AtomicInteger()
        override fun disconnect() {
            disconnectCalls.incrementAndGet()
            super.disconnect()
        }
    }

    @Test
    fun destroyingTheServiceDisconnects() {
        val controller = Robolectric.buildService(DisconnectRecordingService::class.java).create()

        controller.destroy()

        assertThat(controller.get().disconnectCalls.get()).isEqualTo(1)
    }
}
