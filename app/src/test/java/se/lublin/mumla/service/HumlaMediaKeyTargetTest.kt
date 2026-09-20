package se.lublin.mumla.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser

class HumlaMediaKeyTargetTest {
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val service = mockk<IHumlaService> {
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
}
