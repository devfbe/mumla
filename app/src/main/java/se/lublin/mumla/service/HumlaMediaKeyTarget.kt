package se.lublin.mumla.service

import se.lublin.humla.IHumlaService

/** [MediaKeyTarget] backed by the live Humla session of [service]. */
class HumlaMediaKeyTarget(private val service: IHumlaService) : MediaKeyTarget {
    override val isConnected: Boolean
        get() = service.isConnected

    override val transmitMode: Int
        get() = service.HumlaSession().transmitMode

    override val isTalking: Boolean
        get() = service.HumlaSession().isTalking

    override fun setTalking(talking: Boolean) {
        service.HumlaSession().setTalkingState(talking)
    }

    override fun stopTalking() {
        // HumlaService.HumlaSession() throws under exactly the condition that makes isConnected
        // false, and the callers of this are lifecycle events that may well arrive after the
        // connection is already gone.
        if (!service.isConnected) return
        service.HumlaSession().setTalkingState(false)
    }

    override fun toggleSelfMute() {
        val session = service.HumlaSession()
        val self = session.sessionUser ?: return
        val muted = !self.isSelfMuted
        val deafened = self.isSelfDeafened && muted
        session.setSelfMuteDeafState(muted, deafened)
    }
}
