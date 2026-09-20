package se.lublin.humla.session

import com.google.common.truth.Truth.assertThat
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.protocol.AudioHandler

/**
 * [AudioHandlerAdapter] is the only place where the real [AudioHandler] is dressed as a
 * [ManagedAudio]. Every member is a delegation, so the thing worth pinning is that each one reaches
 * the handler rather than a default - and that the warning channel, which the handler does not have
 * yet, still carries a message to whoever registered for it (spec A8).
 */
@RunWith(RobolectricTestRunner::class)
class AudioHandlerAdapterTest {
    private val handler = mockk<AudioHandler>(relaxed = true)
    private val adapter = AudioHandlerAdapter(handler)

    @Test
    fun theProtocolListenersAreTheHandlerItself() {
        assertThat(adapter.tcpListener).isSameInstanceAs(handler)
        assertThat(adapter.udpListener).isSameInstanceAs(handler)
    }

    @Test
    fun bandwidthIsReadFromTheHandlerOnEveryCall() {
        every { handler.currentBandwidth } returnsMany listOf(12_000, 34_000)

        assertThat(adapter.currentBandwidth).isEqualTo(12_000)
        assertThat(adapter.currentBandwidth).isEqualTo(34_000)
    }

    @Test
    fun theVoiceTargetAndTheShutdownReachTheHandler() {
        adapter.setVoiceTargetId(7)
        adapter.shutdown()

        verify(exactly = 1) { handler.setVoiceTargetId(7) }
        verify(exactly = 1) { handler.shutdown() }
        confirmVerified(handler)
    }

    @Test
    fun aWarningReachesTheRegisteredListenerAndNobodyAfterItIsCleared() {
        val seen = mutableListOf<String>()
        adapter.setWarningListener { seen += it }
        adapter.reportWarning("microphone silenced by the system")

        adapter.setWarningListener(null)
        adapter.reportWarning("dropped")

        assertThat(seen).containsExactly("microphone silenced by the system")
    }

    /** With no listener ever registered a warning is a no-op, not a crash on the audio thread. */
    @Test
    fun aWarningWithNoListenerIsSilent() {
        adapter.reportWarning("nobody is listening")
    }
}
