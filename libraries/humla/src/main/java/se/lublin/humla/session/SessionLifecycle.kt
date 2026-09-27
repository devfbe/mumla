/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.net.ReconnectPolicy
import kotlin.random.Random

/**
 * Drives a [SessionStateMachine] and owns what its states hold: the reconnect timer, the wait for a
 * network while offline, and the wake lock taken at the first synchronization and released when the
 * session ends. [retry] starts a reconnect attempt.
 *
 * Confined to the thread of [scope], whose dispatcher must post rather than run inline, so a retry
 * never starts inside the call that scheduled it.
 */
internal class SessionLifecycle(
    policy: ReconnectPolicy,
    private val network: NetworkMonitor,
    private val wakeLock: SessionWakeLock,
    private val scope: CoroutineScope,
    private val retry: () -> Unit,
    jitter: () -> Double = { Random.nextDouble() },
) {
    internal val machine = SessionStateMachine(policy, jitter)

    /** The pending reconnect, cancelled whenever the state moves on. */
    private var pendingRetry: Job? = null

    val state: StateFlow<SessionState> get() = machine.state
    val current: SessionState get() = machine.current

    /** Whether a live session keeps the device awake; true from the first synchronization on. */
    val isWakeLockHeld: Boolean get() = wakeLock.isHeld

    /** A user-initiated attempt; false while one is in flight or a session is up. */
    fun connectRequested(): Boolean = machine.connectRequested()

    /**
     * ServerSync arrived. The wake lock is reference counted, so it is taken once per session rather
     * than on every reconnect.
     */
    fun synchronized(): Boolean {
        if (!machine.synchronized()) return false
        if (!wakeLock.isHeld) wakeLock.acquire()
        return true
    }

    /**
     * The connection ended. Schedules the retry the next state asks for, or ends the session.
     * @return the next state.
     */
    fun lost(autoReconnect: Boolean, error: HumlaException?): SessionState {
        val next = machine.lost(autoReconnect, error)
        if (next is SessionState.ConnectionLost) scheduleRetry(next.reconnectInMillis) else release()
        return next
    }

    /**
     * Ends the session on the user's request.
     * @return true if the session was waiting to reconnect, so no connection will report the end
     * and this call released everything.
     */
    fun disconnectRequested(): Boolean {
        val waiting = current is SessionState.ConnectionLost
        machine.disconnectRequested()
        if (waiting) release() else cancelRetry()
        return waiting
    }

    /** Gives up the automatic reconnect; the error stays in the state. */
    fun cancelReconnect(): Boolean {
        if (!machine.cancelReconnect()) return false
        release()
        return true
    }

    /** Gives back the timer, the network wait and the wake lock. Idempotent. */
    fun release() {
        cancelRetry()
        network.stopWaiting()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun scheduleRetry(delayMillis: Long) {
        cancelRetry()
        if (network.isOnline) {
            pendingRetry = scope.launch {
                delay(delayMillis)
                if (machine.reconnectTimerFired()) retry()
            }
        } else {
            // No point in burning attempts while there is no network; wait for it to come back.
            network.awaitNetwork(::onNetworkAvailable)
        }
    }

    private fun onNetworkAvailable() {
        if (!machine.connectivityRestored()) return
        pendingRetry = scope.launch { if (machine.reconnectTimerFired()) retry() }
    }

    private fun cancelRetry() {
        pendingRetry?.cancel()
        pendingRetry = null
    }
}
