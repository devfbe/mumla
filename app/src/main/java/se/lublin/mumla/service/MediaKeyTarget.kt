package se.lublin.mumla.service

/**
 * The part of a Mumla session a headset/media button may act on.
 * Kept tiny so [MediaKeyHandler] can be tested against a fake.
 */
interface MediaKeyTarget {
    /** True while the server session is synchronized (IHumlaService.isConnected). */
    val isConnected: Boolean

    /** One of se.lublin.humla.Constants.TRANSMIT_*. */
    val transmitMode: Int

    val isTalking: Boolean

    fun setTalking(talking: Boolean)

    /** Flip self-mute; deafen is cleared when unmuting, kept when muting (as the mute menu item does). */
    fun toggleSelfMute()
}
