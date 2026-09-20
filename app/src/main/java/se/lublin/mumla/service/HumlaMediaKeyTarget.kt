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

    override fun toggleSelfMute() {
        val session = service.HumlaSession()
        val self = session.sessionUser ?: return
        val muted = !self.isSelfMuted
        val deafened = self.isSelfDeafened && muted
        session.setSelfMuteDeafState(muted, deafened)
    }
}
