package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.testutil.testSession

/**
 * The push-to-talk toggle must not survive a lost connection: [ToggleInputMode] lives as long as
 * the session, so otherwise an auto-reconnect would resume sending without a key press.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaSessionTransmitResetTest {

    private val sessions = mutableListOf<HumlaSession>()

    @After
    fun tearDown() = sessions.forEach { it.close() }

    private fun service(): HumlaSession = testSession().also { sessions += it }

    @Test
    fun aLostConnectionClearsTheTransmitToggle() {
        val service = service()
        service.audio.setTalking(true)
        assertThat(service.audio.isTalking).isTrue()

        service.onConnectionDisconnected(
            HumlaException("network gone", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR),
        )

        // The next connection starts silent: nothing sets this back without a key press.
        assertThat(service.audio.isTalking).isFalse()
    }

    @Test
    fun aCleanDisconnectClearsTheTransmitToggleToo() {
        val service = service()
        service.audio.setTalking(true)

        service.onConnectionDisconnected(null)

        assertThat(service.audio.isTalking).isFalse()
    }

    /**
     * The flag the session clears is the one the audio input thread consults for every frame, so
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
