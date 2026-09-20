package se.lublin.humla.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.util.HumlaException
import kotlin.random.Random

/**
 * Pure transition logic for [SessionState]. Not thread-safe: it must be driven from a single
 * thread the owner designates (a service's protocol or audio-control thread, not necessarily
 * the main thread) — any thread that only observes should collect [state] instead of calling a
 * mutator. Timers and sockets live outside this class; it only decides.
 */
class SessionStateMachine(
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val jitterSource: () -> Double = { Random.nextDouble() },
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Disconnected())

    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    val current: SessionState get() = mutableState.value
    val maxAttempts: Int get() = policy.maxAttempts

    /** Reconnect attempts since the last successful session (or connectivity change). */
    var attempt: Int = 0
        private set

    /** User asked to connect. Ignored while connecting or connected. */
    fun connectRequested(): Boolean = when (current) {
        SessionState.Connecting, SessionState.Connected -> false
        else -> {
            attempt = 0
            mutableState.value = SessionState.Connecting
            true
        }
    }

    /** ServerSync arrived. Only valid while an attempt is in flight. */
    fun synchronized(): Boolean = when (current) {
        SessionState.Connecting, is SessionState.Reconnecting -> {
            attempt = 0
            mutableState.value = SessionState.Connected
            true
        }
        else -> false
    }

    /**
     * The connection ended. With [autoReconnect] the next state is [SessionState.ConnectionLost]
     * carrying the backoff delay, or [SessionState.Disconnected] once attempts are exhausted.
     * A no-op (returns the current state) when nothing was connected.
     */
    fun lost(autoReconnect: Boolean, error: HumlaException?): SessionState {
        when (current) {
            is SessionState.Disconnected, is SessionState.ConnectionLost -> return current
            else -> Unit
        }
        val next = if (autoReconnect) {
            attempt += 1
            val delay = policy.delayFor(attempt, jitterSource())
            if (delay == null) SessionState.Disconnected(error) else SessionState.ConnectionLost(delay, attempt, error)
        } else {
            SessionState.Disconnected(error)
        }
        if (next is SessionState.Disconnected) attempt = 0
        mutableState.value = next
        return next
    }

    /** The backoff timer fired: start the reconnect attempt. */
    fun reconnectTimerFired(): Boolean {
        val lostState = current as? SessionState.ConnectionLost ?: return false
        mutableState.value = SessionState.Reconnecting(lostState.error)
        return true
    }

    /** Network came back: forget previous attempts and ask the caller to retry immediately. */
    fun connectivityRestored(): Boolean {
        val lostState = current as? SessionState.ConnectionLost ?: return false
        attempt = 0
        mutableState.value = SessionState.ConnectionLost(0L, 0, lostState.error)
        return true
    }

    /** User cancelled the automatic reconnect; the error stays visible to the UI. */
    fun cancelReconnect(): Boolean {
        val error = when (val state = current) {
            is SessionState.ConnectionLost -> state.error
            is SessionState.Reconnecting -> state.error
            else -> return false
        }
        attempt = 0
        mutableState.value = SessionState.Disconnected(error)
        return true
    }

    /** User asked to disconnect (or the service is going away). */
    fun disconnectRequested(): Boolean {
        if (current is SessionState.Disconnected) return false
        attempt = 0
        mutableState.value = SessionState.Disconnected(null)
        return true
    }
}
