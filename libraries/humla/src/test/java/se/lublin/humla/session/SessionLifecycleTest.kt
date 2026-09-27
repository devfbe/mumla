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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import se.lublin.humla.net.ReconnectPolicy

/** The reconnect timer, the network wait and the wake lock around the state machine; plain JVM. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest {
    private class FakeNetwork : NetworkMonitor {
        override var isOnline = true
        var waiting: (() -> Unit)? = null

        override fun awaitNetwork(onAvailable: () -> Unit) {
            waiting = onAvailable
        }

        override fun stopWaiting() {
            waiting = null
        }

        /** The platform's callback: it unregisters itself before it reports. */
        fun comesBack() {
            val callback = checkNotNull(waiting) { "nobody waits for the network" }
            waiting = null
            callback()
        }
    }

    private class FakeWakeLock : SessionWakeLock {
        var count = 0
        override val isHeld get() = count > 0
        override fun acquire() {
            count++
        }

        override fun release() {
            count--
        }
    }

    private val scope = TestScope(StandardTestDispatcher())
    private val network = FakeNetwork()
    private val wakeLock = FakeWakeLock()
    private var retries = 0
    private val lifecycle = SessionLifecycle(
        ReconnectPolicy(baseDelayMillis = 10L, maxDelayMillis = 10L, maxAttempts = 3, maxJitterFraction = 0.0),
        network,
        wakeLock,
        scope,
        retry = { retries++ },
        jitter = { 0.0 },
    )

    private val error = DisconnectReason.Network("socket reset", null)

    private fun connected() {
        assertThat(lifecycle.connectRequested()).isTrue()
        assertThat(lifecycle.synchronized()).isTrue()
    }

    private fun lose(): SessionState = lifecycle.lost(autoReconnect = true, reason = error)

    @Test
    fun theWakeLockIsTakenAtTheFirstSynchronizationOnly() {
        assertThat(wakeLock.isHeld).isFalse()
        lifecycle.connectRequested()
        assertThat(wakeLock.isHeld).isFalse()

        lifecycle.synchronized()
        lose()
        scope.advanceTimeBy(11)
        lifecycle.synchronized()

        assertThat(wakeLock.count).isEqualTo(1)
    }

    /** A late ServerSync for an attempt the user ended must not take the lock. */
    @Test
    fun aSynchronizationOutsideAnAttemptTakesNothing() {
        assertThat(lifecycle.synchronized()).isFalse()
        assertThat(wakeLock.isHeld).isFalse()
    }

    @Test
    fun aLossWhileOnlineRetriesAfterTheBackoffAndKeepsTheWakeLock() {
        connected()

        val next = lose() as SessionState.ConnectionLost

        assertThat(next.reconnectInMillis).isEqualTo(10L)
        assertThat(wakeLock.isHeld).isTrue()
        assertThat(network.waiting).isNull()
        scope.advanceTimeBy(9)
        scope.runCurrent()
        assertThat(retries).isEqualTo(0)
        scope.advanceTimeBy(2)
        assertThat(retries).isEqualTo(1)
        assertThat(lifecycle.current).isInstanceOf(SessionState.Reconnecting::class.java)
    }

    @Test
    fun aLossWhileOfflineWaitsForTheNetworkInsteadOfBurningAttempts() {
        connected()
        network.isOnline = false

        lose()
        scope.advanceTimeBy(60_000)

        assertThat(retries).isEqualTo(0)
        assertThat(network.waiting).isNotNull()
    }

    @Test
    fun theNetworkComingBackRetriesAtOnceWithAFreshBudget() {
        connected()
        network.isOnline = false
        lose()

        network.comesBack()
        assertThat(retries).isEqualTo(0) // posted, never inline
        scope.runCurrent()

        assertThat(retries).isEqualTo(1)
        assertThat(lifecycle.machine.attempt).isEqualTo(0)
        assertThat(lifecycle.current).isInstanceOf(SessionState.Reconnecting::class.java)
    }

    /** A callback that beats the unregistration after the session moved on does nothing. */
    @Test
    fun aNetworkThatComesBackAfterTheSessionMovedOnDoesNotRetry() {
        connected()
        network.isOnline = false
        lose()
        val callback = checkNotNull(network.waiting)

        lifecycle.cancelReconnect()
        callback()
        scope.runCurrent()

        assertThat(retries).isEqualTo(0)
    }

    @Test
    fun exhaustedAttemptsEndTheSessionAndReleaseEverything() {
        connected()
        repeat(3) {
            assertThat(lose()).isInstanceOf(SessionState.ConnectionLost::class.java)
            scope.advanceTimeBy(11)
        }

        val last = lose()

        assertThat(last).isEqualTo(SessionState.Disconnected(error))
        assertThat(retries).isEqualTo(3)
        assertThat(wakeLock.isHeld).isFalse()
    }

    @Test
    fun aLossWithoutAutoReconnectEndsTheSession() {
        connected()

        assertThat(lifecycle.lost(autoReconnect = false, reason = error)).isEqualTo(SessionState.Disconnected(error))
        assertThat(wakeLock.isHeld).isFalse()
    }

    /** Nothing reports the end of a session that waits out its backoff, so the call releases. */
    @Test
    fun aDisconnectWhileWaitingReleasesAndCancelsTheTimer() {
        connected()
        network.isOnline = false
        lose()

        assertThat(lifecycle.disconnectRequested()).isTrue()

        assertThat(lifecycle.current).isEqualTo(SessionState.Disconnected())
        assertThat(wakeLock.isHeld).isFalse()
        assertThat(network.waiting).isNull()
    }

    /** With a live connection its own report releases, so the lock still covers the teardown. */
    @Test
    fun aDisconnectOfALiveSessionLeavesTheReleaseToTheConnectionsReport() {
        connected()

        assertThat(lifecycle.disconnectRequested()).isFalse()
        assertThat(wakeLock.isHeld).isTrue()

        lifecycle.lost(autoReconnect = true, reason = error)
        assertThat(lifecycle.current).isEqualTo(SessionState.Disconnected())
        assertThat(wakeLock.isHeld).isFalse()
    }

    @Test
    fun aCancelledReconnectNeverRetriesAndKeepsTheError() {
        connected()
        lose()

        assertThat(lifecycle.cancelReconnect()).isTrue()
        scope.advanceTimeBy(1_000)

        assertThat(retries).isEqualTo(0)
        assertThat(lifecycle.current).isEqualTo(SessionState.Disconnected(error))
        assertThat(wakeLock.isHeld).isFalse()
    }

    @Test
    fun cancellingALiveSessionChangesNothing() {
        connected()

        assertThat(lifecycle.cancelReconnect()).isFalse()

        assertThat(lifecycle.current).isEqualTo(SessionState.Connected)
        assertThat(wakeLock.isHeld).isTrue()
    }

    @Test
    fun releaseIsIdempotent() {
        connected()

        lifecycle.release()
        lifecycle.release()

        assertThat(wakeLock.count).isEqualTo(0)
    }
}
