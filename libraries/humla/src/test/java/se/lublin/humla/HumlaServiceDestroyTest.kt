package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * A service destroyed while it is connected has to end its connection.
 *
 * The protocol thread is non-daemon and only [HumlaConnection.disconnect] quits its looper, so a
 * service that goes away without disconnecting leaves "humla-protocol" running - holding the
 * socket, the transports and everything the protocol thread's queue still references - for the
 * remaining life of the process. onDestroy unregistered its Bluetooth receiver and nothing else.
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
