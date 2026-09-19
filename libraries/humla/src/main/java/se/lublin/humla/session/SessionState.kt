package se.lublin.humla.session

import se.lublin.humla.util.HumlaException

/**
 * Lifecycle of one server session as seen by the service and the UI (spec A3).
 *
 * Disconnected -> Connecting -> Connected -> ConnectionLost -> Reconnecting -> Connected ...
 *
 * Every state except [Disconnected] keeps the foreground notification, the partial wake lock,
 * the Bluetooth SCO "wanted" flag and the user's mute/deafen state.
 */
sealed class SessionState {
    /** No session. [error] is why the last session ended, or null after a clean disconnect. */
    data class Disconnected(val error: HumlaException? = null) : SessionState()

    /** A user-initiated connection attempt is in progress. */
    object Connecting : SessionState() {
        override fun toString(): String = "Connecting"
    }

    /** ServerSync has been received; the session is usable. */
    object Connected : SessionState() {
        override fun toString(): String = "Connected"
    }

    /** The session dropped; an automatic reconnect fires in [reconnectInMillis]. */
    data class ConnectionLost(val reconnectInMillis: Long, val attempt: Int, val error: HumlaException?) : SessionState()

    /** An automatic reconnect attempt is in progress. */
    object Reconnecting : SessionState() {
        override fun toString(): String = "Reconnecting"
    }
}
