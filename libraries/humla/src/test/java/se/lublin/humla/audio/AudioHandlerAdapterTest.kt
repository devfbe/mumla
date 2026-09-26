package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * [AudioHandlerAdapter] dresses the real [AudioHandler] as a [ManagedAudio]. Each delegation must
 * reach the handler rather than a default, and the warning channel must reach whoever registered.
 */
class AudioHandlerAdapterTest {
    private val handler = mockk<AudioHandler>(relaxed = true)
    private val adapter = AudioHandlerAdapter(handler)

    @Test
    fun theProtocolListenersAreTheHandlerItself() {
        assertThat(adapter.tcpHandler).isSameInstanceAs(handler)
        assertThat(adapter.voiceHandler).isSameInstanceAs(handler)
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
