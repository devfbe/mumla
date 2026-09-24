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

    /**
     * Like the real HumlaService, HumlaSession() throws exactly when isConnected is false, which
     * is the live state in onDisconnected.
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
