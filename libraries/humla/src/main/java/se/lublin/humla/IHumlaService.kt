package se.lublin.humla

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.model.Server
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.exception.HumlaDisconnectedException
import se.lublin.humla.exception.HumlaException

/**
 * What clients of a [HumlaService] use. Not thread-safe: call it from the main thread. Unless
 * stated otherwise, members that depend on the connection throw [IllegalStateException] while
 * disconnected or not synchronized.
 */
interface IHumlaService {
    /**
     * What happens in the session, emitted from any thread. There is no replay: collect before
     * acting on a result. Collect on the main thread, through
     * [se.lublin.humla.session.inMainThreadSlices], to keep the UI responsive during bursts.
     */
    val events: SharedFlow<HumlaEvent>

    /** True once the handshake with the server has completed. */
    val isConnected: Boolean

    /** The configuration last passed to [configure]. */
    val sessionConfig: SessionConfig

    /** The coarse connection state; [sessionState] tells more. */
    val connectionState: HumlaService.ConnectionState

    /**
     * The session lifecycle as a flow, for clients that render it. Finer than [connectionState]: it
     * tells a lost connection that is being retried apart from one that is not, and carries the
     * attempt number and the delay until the next try.
     */
    val sessionState: StateFlow<SessionState>

    /** The error that ended the last connection, or null if it ended cleanly or none was made yet. */
    val connectionError: HumlaException?

    /** True while the service will try to reconnect. */
    val isReconnecting: Boolean

    /** The server that Humla is connected to, was connected to, or will connect to. */
    val targetServer: Server?

    /**
     * The active session.
     * @throws HumlaDisconnectedException while not connected; see [isConnected].
     */
    val session: IHumlaSession

    /**
     * Replaces the session configuration. Audio settings apply live, connection settings on the
     * next connection.
     * @return true if a reconnect is required for the changes to take effect.
     */
    fun configure(config: SessionConfig): Boolean

    /** Connects to the configured server. Ignored while connecting or connected. */
    fun connect()

    /** Disconnects, or does nothing if no connection is active. */
    fun disconnect()

    /** Cancels any future reconnection attempts. */
    fun cancelReconnect()
}
