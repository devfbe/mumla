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
import se.lublin.humla.model.IUser
import se.lublin.humla.session.SessionState
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class SessionMediaKeyTargetTest {
    private val session = mockk<IHumlaSession>(relaxed = true).also { installSession(it.stubConnected()) }
    private val target = SessionMediaKeyTarget(SessionManager.get(ApplicationProvider.getApplicationContext<Context>()))

    @Test
    fun toggleMuteMutesAnUnmutedUserKeepingDeafenOff() {
        val self = mockk<IUser> {
            every { isSelfMuted } returns false
            every { isSelfDeafened } returns false
        }
        every { session.sessionUser } returns self

        target.toggleSelfMute()

        verify(exactly = 1) { session.setSelfMuteDeafState(true, false) }
    }

    @Test
    fun toggleMuteUnmutesAMutedAndDeafenedUserAndUndeafens() {
        val self = mockk<IUser> {
            every { isSelfMuted } returns true
            every { isSelfDeafened } returns true
        }
        every { session.sessionUser } returns self

        target.toggleSelfMute()

        verify(exactly = 1) { session.setSelfMuteDeafState(false, false) }
    }

    @Test
    fun toggleMuteDoesNothingWithoutSessionUser() {
        every { session.sessionUser } returns null

        target.toggleSelfMute()

        verify(exactly = 0) { session.setSelfMuteDeafState(any(), any()) }
    }

    @Test
    fun stopTalkingTurnsTalkingOff() {
        target.stopTalking()

        verify(exactly = 1) { session.setTalkingState(false) }
    }

    /**
     * Unconditional by contract: callers are lifecycle events (disconnect, released session,
     * "none" action), where the cached talking state is exactly what is distrusted.
     */
    @Test
    fun stopTalkingWritesTheOffStateEvenWhenAlreadyOff() {
        every { session.isTalking } returns false

        target.stopTalking()

        verify(exactly = 1) { session.setTalkingState(false) }
        verify(exactly = 0) { session.isTalking }
    }

    /** Once disconnected, nothing is written: a reconnect must not inherit a reset it did not ask for. */
    @Test
    fun stopTalkingWhileDisconnectedIsANoOp() {
        session.stubState(SessionState.Disconnected())

        target.stopTalking()

        verify(exactly = 0) { session.setTalkingState(any()) }
        assertThat(target.isConnected).isFalse()
    }
}
