package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class SelfMuteStateTest {
    private fun state(serverMuted: Boolean = false, selfMuted: Boolean = false, suppressed: Boolean = false) =
        SelfMuteState(serverMuted, selfMuted, suppressed)

    private fun userState(build: Mumble.UserState.Builder.() -> Unit) =
        Mumble.UserState.newBuilder().setSession(1).apply(build).build()

    @Test
    fun anyFlagMutes() {
        assertThat(state().isMuted).isFalse()
        assertThat(state(serverMuted = true).isMuted).isTrue()
        assertThat(state(selfMuted = true).isMuted).isTrue()
        assertThat(state(suppressed = true).isMuted).isTrue()
    }

    @Test
    fun aPartialUpdateLeavesTheFlagsItDoesNotCarry() {
        val state = state(serverMuted = true)

        state.update(userState { selfMute = false })

        assertThat(state.isMuted).isTrue()
    }

    @Test
    fun theLastFlagClearedUnmutes() {
        val state = state(serverMuted = true, suppressed = true)

        state.update(userState { mute = false })
        assertThat(state.isMuted).isTrue()

        state.update(userState { suppress = false })
        assertThat(state.isMuted).isFalse()
    }

    @Test
    fun aSelfMuteUpdateMutesAndUnmutes() {
        val state = state()

        state.update(userState { selfMute = true })
        assertThat(state.isMuted).isTrue()

        state.update(userState { selfMute = false })
        assertThat(state.isMuted).isFalse()
    }
}
