package se.lublin.mumla.service

import se.lublin.humla.IHumlaService
import se.lublin.humla.audio.TransmitMode

/** [MediaKeyTarget] backed by the live Humla session of [service]. */
class HumlaMediaKeyTarget(private val service: IHumlaService) : MediaKeyTarget {
    override val isConnected: Boolean
        get() = service.isConnected

    override val transmitMode: TransmitMode
        get() = service.session.transmitMode

    override val isTalking: Boolean
        get() = service.session.isTalking

    override fun setTalking(talking: Boolean) {
        service.session.setTalkingState(talking)
    }

    override fun stopTalking() {
        // `session` throws once disconnected, and lifecycle callers may arrive after that.
        if (!service.isConnected) return
        service.session.setTalkingState(false)
    }

    override fun toggleSelfMute() {
        val session = service.session
        val self = session.sessionUser ?: return
        val muted = !self.isSelfMuted
        val deafened = self.isSelfDeafened && muted
        session.setSelfMuteDeafState(muted, deafened)
    }
}
