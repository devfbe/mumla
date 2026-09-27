package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.exception.HumlaException
/**
 * The push-to-talk toggle must not survive a lost connection: [ToggleInputMode] lives as long as
 * the service, so otherwise an auto-reconnect would resume sending without a key press.
 *
 * The reset is in onConnectionDisconnected rather than in an event collector: the state is already
 * DISCONNECTED when collectors run, so a collector going through the session would do nothing.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceTransmitResetTest {

    private fun service(): HumlaService =
        Robolectric.buildService(HumlaService::class.java).create().get()

    @Test
    fun aLostConnectionClearsTheTransmitToggle() {
        val service = service()
        service.setTalkingState(true)
        assertThat(service.isTalking).isTrue()

        service.onConnectionDisconnected(
            HumlaException("network gone", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        )

        // The next connection starts silent: nothing sets this back without a key press.
        assertThat(service.isTalking).isFalse()
    }

    @Test
    fun aCleanDisconnectClearsTheTransmitToggleToo() {
        val service = service()
        service.setTalkingState(true)

        service.onConnectionDisconnected(null)

        assertThat(service.isTalking).isFalse()
    }

    /**
     * The flag the service clears is the one the audio input thread consults for every frame, so
     * "isTalking() is false" really means "nothing is sent".
     */
    @Test
    fun theFlagIsTalkingReadsIsTheOneThatDecidesWhetherAFrameIsTransmitted() {
        val mode = ToggleInputMode()
        val frame = ShortArray(480)

        mode.setTalkingOn(true)
        assertThat(mode.isTalkingOn).isTrue()
        assertThat(mode.shouldTransmit(frame, frame.size, null)).isTrue()

        mode.setTalkingOn(false)
        assertThat(mode.isTalkingOn).isFalse()
        assertThat(mode.shouldTransmit(frame, frame.size, null)).isFalse()
    }
}
