package se.lublin.humla.util

/** Thrown when the session is requested while the service is not connected. */
class HumlaDisconnectedException @JvmOverloads constructor(
    reason: String = "Caller attempted to use the protocol while disconnected.",
) : RuntimeException(reason)
