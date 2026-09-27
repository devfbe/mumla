package se.lublin.mumla.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.UserState
import se.lublin.humla.session.SessionState
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.selfServerState
import se.lublin.mumla.testing.stubAudio
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class SessionMediaKeyTargetTest {
    private val session = mockk<IHumlaSession>(relaxed = true).also { installSession(it.stubConnected()) }
    private val audio = session.stubAudio()
    private val target = SessionMediaKeyTarget(SessionManager.get(ApplicationProvider.getApplicationContext<Context>()))

    private fun self(muted: Boolean, deafened: Boolean) =
        session.stubModel(selfServerState(UserState(1, "me", 0, isSelfMuted = muted, isSelfDeafened = deafened)))

    @Test
    fun toggleMuteMutesAnUnmutedUserKeepingDeafenOff() {
        self(muted = false, deafened = false)

        target.toggleSelfMute()

        verify(exactly = 1) { session.actions.setSelfMuteDeafState(true, false) }
    }

    @Test
    fun toggleMuteUnmutesAMutedAndDeafenedUserAndUndeafens() {
        self(muted = true, deafened = true)

        target.toggleSelfMute()

        verify(exactly = 1) { session.actions.setSelfMuteDeafState(false, false) }
    }

    @Test
    fun toggleMuteDoesNothingWithoutSessionUser() {
        session.stubModel(serverState { channel(0, "Root") })

        target.toggleSelfMute()

        verify(exactly = 0) { session.actions.setSelfMuteDeafState(any(), any()) }
    }

    @Test
    fun stopTalkingTurnsTalkingOff() {
        target.stopTalking()

        verify(exactly = 1) { audio.setTalking(false) }
    }

    /**
     * Unconditional by contract: callers are lifecycle events (disconnect, released session,
     * "none" action), where the cached talking state is exactly what is distrusted.
     */
    @Test
    fun stopTalkingWritesTheOffStateEvenWhenAlreadyOff() {
        every { audio.isTalking } returns false

        target.stopTalking()

        verify(exactly = 1) { audio.setTalking(false) }
        verify(exactly = 0) { audio.isTalking }
    }

    /** Once disconnected, nothing is written: a reconnect must not inherit a reset it did not ask for. */
    @Test
    fun stopTalkingWhileDisconnectedIsANoOp() {
        session.stubState(SessionState.Disconnected())

        target.stopTalking()

        verify(exactly = 0) { audio.setTalking(any()) }
        assertThat(target.isConnected).isFalse()
    }
}
