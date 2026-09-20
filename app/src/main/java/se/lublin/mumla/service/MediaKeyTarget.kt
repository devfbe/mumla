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

    /**
     * Force talking off, unconditionally and without reading [isTalking] first.
     *
     * Separate from `setTalking(false)` on purpose. [setTalking] actuates a live session and the
     * handler only reaches it behind an [isConnected] check; this is a lifecycle reset that must be
     * safe to call from *any* state, including a disconnected one, where the underlying session
     * accessor throws. Task 4 calls it when it gives up the media session (disconnect, the action
     * switching to NONE, service teardown), so that a talking state the user switched on by headset
     * with the screen off cannot survive into the next connection. Must never throw.
     *
     * Beware: wiring this to a disconnect alone does *not* close that hole. HumlaService sets its
     * connection state before it fires onDisconnected, and both [isConnected] and the session
     * accessor read that same field, so by the time the observer runs this is a no-op by contract.
     * The reset that survives an auto-reconnect has to happen on connect. See the Task 4
     * obligations in the stream ledger.
     */
    fun stopTalking()

    /** Flip self-mute; deafen is cleared when unmuting, kept when muting (as the mute menu item does). */
    fun toggleSelfMute()
}
