package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.util.HumlaException

/**
 * Spec §4.1: the push-to-talk toggle must not survive a lost connection.
 *
 * [ToggleInputMode] is created once in onCreate and lives as long as the service, and nothing ever
 * cleared its flag. With a headset media key a user can turn transmission on with the screen off,
 * lose the network, and the auto-reconnect then resumes sending from the first second - no key
 * press, no visible indication. That is the inverse of the complaint this work started from.
 *
 * The reset has to be in the code of onConnectionDisconnected rather than in an observer:
 * mConnectionState is set to DISCONNECTED before mCallbacks.onDisconnected fires, and both
 * isConnected() and HumlaSession() read that field, so an observer that went through the session
 * would find the service already disconnected and do nothing.
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
     * Closes the last link of the chain: the flag the service clears is the one the audio input
     * thread consults for every frame, so "isTalking() is false" really does mean "nothing is sent".
     */
    @Test
    fun theFlagIsTalkingReadsIsTheOneThatDecidesWhetherAFrameIsTransmitted() {
        val mode = ToggleInputMode()
        val frame = ShortArray(480)

        mode.setTalkingOn(true)
        assertThat(mode.isTalkingOn).isTrue()
        assertThat(mode.shouldTransmit(frame, frame.size)).isTrue()

        mode.setTalkingOn(false)
        assertThat(mode.isTalkingOn).isFalse()
        assertThat(mode.shouldTransmit(frame, frame.size)).isFalse()
    }
}
