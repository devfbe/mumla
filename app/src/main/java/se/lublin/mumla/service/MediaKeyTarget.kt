package se.lublin.mumla.service

import se.lublin.humla.audio.TransmitMode

/** The part of a Mumla session a headset/media button may act on. */
interface MediaKeyTarget {
    /** True while the server session is synchronized. */
    val isConnected: Boolean

    val transmitMode: TransmitMode

    val isTalking: Boolean

    fun setTalking(talking: Boolean)

    /**
     * Force talking off without reading [isTalking] first. Unlike [setTalking] it must be safe
     * (and never throw) in any state, including disconnected; called when the media session is
     * given up. It is a no-op once disconnected, so it cannot reset talking across a reconnect.
     */
    fun stopTalking()

    /** Flip self-mute; deafen is cleared when unmuting, kept when muting (as the mute menu item does). */
    fun toggleSelfMute()
}
