package se.lublin.mumla.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaDisconnectedException

class HumlaMediaKeyTargetTest {
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val service = mockk<IHumlaService> {
        every { isConnected } returns true
        every { HumlaSession() } returns session
    }
    private val target = HumlaMediaKeyTarget(service)

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
     * Unconditional by contract: it must not read the current state and skip the write because it
     * believes talking is already off. Every caller of this is a lifecycle event -- a disconnect, a
     * released media session, a switch to the "none" action -- and the cached view of that state is
     * precisely what is being distrusted.
     */
    @Test
    fun stopTalkingWritesTheOffStateEvenWhenAlreadyOff() {
        every { session.isTalking } returns false

        target.stopTalking()

        verify(exactly = 1) { session.setTalkingState(false) }
        verify(exactly = 0) { session.isTalking }
    }

    /**
     * The real HumlaService throws from HumlaSession() under exactly the condition that makes
     * isConnected false (both read mConnectionState != CONNECTED), so the double does too. Task 4
     * calls the off switch from onDisconnected, where that is the live state.
     */
    @Test
    fun stopTalkingWhileDisconnectedIsANoOpAndDoesNotThrow() {
        val disconnected = mockk<IHumlaService> {
            every { isConnected } returns false
            every { HumlaSession() } throws HumlaDisconnectedException()
        }

        HumlaMediaKeyTarget(disconnected).stopTalking()

        verify(exactly = 0) { disconnected.HumlaSession() }
    }
}
