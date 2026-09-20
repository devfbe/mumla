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
 *
 * What this pins, exactly: that onDestroy calls disconnect(), once. What it does not pin:
 *
 * - **the effect.** Nothing here connects, so there is no protocol thread to watch end. That the
 *   call actually quits the looper is HumlaConnectionProtocolThreadTest's
 *   `aConnectionThatIsDisconnectedLeavesNoProtocolThreadBehind`; this test only closes the gap
 *   between the two, and reaching a real connection from a Robolectric service would mean opening
 *   a socket.
 * - **the order** against unregisterReceiver(). Swapping the two lines in onDestroy leaves this
 *   test green, which is honest: disconnect() posts rather than calling back inline, so the two
 *   statements do not meet. See the ordering note in HumlaService.onDestroy.
 *
 * The subclass exists because the call is the observable. It overrides nothing else, so everything
 * onDestroy does still happens.
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
