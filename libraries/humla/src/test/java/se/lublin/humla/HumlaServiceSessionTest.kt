/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.HumlaException
import java.util.concurrent.TimeUnit

/**
 * Spec A3: the session lifecycle is a state machine with an exponential backoff, and everything
 * that must survive a dropped connection - the wake lock, the attempt counter, the user's wish to
 * reconnect - is decided here rather than by a boolean.
 *
 * These tests drive a **real** [se.lublin.humla.net.HumlaConnection] over fake transports, so what
 * they exercise is the service's own wiring: the state it is in, the timer it posts and the
 * resources it holds. Task A9a's characterization suite covered the same ground through
 * `setReconnecting(boolean)`, which no longer exists; the corners it enumerated are re-homed here
 * against the mechanism that replaced it, one test per corner.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceSessionTest {
    private val harnesses = mutableListOf<HumlaServiceHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.destroy() }
    }

    private fun start(autoReconnect: Boolean = false): HumlaServiceHarness =
        HumlaServiceHarness(autoReconnect = autoReconnect).also { harnesses += it }

    private fun connectionError() =
        HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

    private fun connectivityManager() = RuntimeEnvironment.getApplication()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    private fun connectivityReceivers() = shadowOf(RuntimeEnvironment.getApplication())
        .registeredReceivers
        .filter { it.intentFilter.hasAction(ConnectivityManager.CONNECTIVITY_ACTION) }

    @Suppress("DEPRECATION")
    private fun sendConnectivityBroadcast(harness: HumlaServiceHarness) {
        RuntimeEnvironment.getApplication()
            .sendBroadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
        harness.mainLooper.idle()
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    fun connectWalksDisconnectedToConnectingToConnected() {
        val h = start()
        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Disconnected())
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()

        h.service.connect()
        h.mainLooper.idle()
        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Connecting)
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTING)

        h.synchronize(h.openSocket(0))

        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Connected)
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTED)
        assertThat(h.service.isConnected()).isTrue()
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
    }

    /** A second connect while one is in flight is the state machine's business, not the socket's. */
    @Test
    fun aSecondConnectWhileConnectedOpensNoSecondSocket() {
        val h = start()
        h.connectAndSynchronize()

        h.service.connect()
        h.mainLooper.idle()

        assertThat(h.transports.tcps).hasSize(1)
        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Connected)
    }

    // ---------------------------------------------------------------- loss and backoff

    @Test
    fun aConnectionErrorWithAutoReconnectEntersConnectionLostAndKeepsTheWakeLock() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())

        val state = h.service.getSessionState().value as SessionState.ConnectionLost
        assertThat(state.attempt).isEqualTo(1)
        assertThat(state.reconnectInMillis).isEqualTo(10L)
        assertThat(h.service.isReconnecting()).isTrue()
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
        // Spec A3: only Disconnected releases it. This is the line the screen-off complaint rests on.
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
        assertThat(h.service.getConnectionError()).isNotNull()
    }

    /**
     * The four corners of `onConnectionDisconnected`'s input: the disconnect reason and
     * `EXTRAS_AUTO_RECONNECT`. Re-homed from the characterization suite, which could drive them
     * without a session because `setReconnecting` was a plain field write; the state machine
     * refuses a loss that had nothing to lose, so each corner now runs over a real session.
     */
    @Test
    fun onlyAConnectionErrorWithAutoReconnectOnStartsReconnecting() {
        val corners = listOf(
            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR to true,
            HumlaException.HumlaDisconnectReason.CONNECTION_ERROR to false,
            HumlaException.HumlaDisconnectReason.REJECT to true,
            HumlaException.HumlaDisconnectReason.OTHER_ERROR to true,
        )

        for ((reason, autoReconnect) in corners) {
            val h = start(autoReconnect = autoReconnect)
            h.connectAndSynchronize()

            h.failConnection(0, HumlaException("gone", reason))

            val shouldReconnect =
                autoReconnect && reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR
            assertThat(h.service.getConnectionState())
                .isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
            assertThat(h.service.isReconnecting()).isEqualTo(shouldReconnect)
            assertThat(h.service.isWakeLockHeldForTest()).isEqualTo(shouldReconnect)
        }
    }

    /** A clean disconnect never reconnects, whatever `EXTRAS_AUTO_RECONNECT` says. */
    @Test
    fun aCleanDisconnectGoesToDisconnectedAndNeverReconnects() {
        for (autoReconnect in listOf(false, true)) {
            val h = start(autoReconnect = autoReconnect)
            h.connectAndSynchronize()

            h.service.disconnect()
            h.mainLooper.idle()

            assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Disconnected())
            assertThat(h.service.getConnectionState())
                .isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
            assertThat(h.service.isReconnecting()).isFalse()
            assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        }
    }

    @Test
    fun reconnectAttemptsAreExhaustedAndEndInDisconnected() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        // A successful ServerSync resets the attempt counter, so exhausting the policy means
        // failing again before the session synchronizes. maxAttempts = 3: losses 1..3 schedule a
        // retry, loss 4 gives up.
        h.failConnection(0, connectionError())
        for (index in 1..2) {
            assertThat(h.service.getSessionState().value)
                .isInstanceOf(SessionState.ConnectionLost::class.java)
            h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer fires
            h.openSocket(index)
            h.failConnection(index, connectionError())
        }
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.openSocket(3)
        h.failConnection(3, connectionError())

        assertThat(h.service.getSessionState().value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        assertThat((h.service.getSessionState().value as SessionState.Disconnected).error).isNotNull()
        assertThat(h.service.isReconnecting()).isFalse()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.warnings).contains(h.service.getString(R.string.reconnect_gave_up))
    }

    /** A session that synchronizes again starts the budget over; otherwise a flaky link runs out. */
    @Test
    fun aSuccessfulSessionResetsTheAttemptCounter() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())
        assertThat((h.service.getSessionState().value as SessionState.ConnectionLost).attempt)
            .isEqualTo(1)
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.synchronize(h.openSocket(1))

        h.failConnection(1, connectionError())

        assertThat((h.service.getSessionState().value as SessionState.ConnectionLost).attempt)
            .isEqualTo(1)
    }

    @Test
    fun cancelReconnectStopsTheTimerAndEndsTheSession() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())

        h.service.cancelReconnect()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        assertThat(h.service.getSessionState().value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        // The error stays visible: the UI is still showing why the session ended.
        assertThat(h.service.getConnectionError()).isNotNull()
        assertThat(h.service.isReconnecting()).isFalse()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.transports.tcps).hasSize(1) // no reconnect was attempted
    }

    @Test
    fun cancellingAReconnectThatNeverStartedIsHarmless() {
        val h = start()

        h.service.cancelReconnect()

        assertThat(h.service.isReconnecting()).isFalse()
        assertThat(connectivityReceivers()).isEmpty()
    }

    // ---------------------------------------------------------------- connectivity

    /**
     * Without connectivity the service does **not** burn attempts: it registers the connectivity
     * receiver and waits. Idling a full minute past the backoff shows nothing was queued at all.
     */
    @Test
    fun aReconnectWithoutConnectivityWaitsForTheNetworkInstead() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)

        h.failConnection(0, connectionError())

        assertThat(h.service.isReconnecting()).isTrue()
        assertThat(connectivityReceivers()).hasSize(1)
        h.mainLooper.idleFor(60, TimeUnit.SECONDS)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /** With connectivity it polls instead, and registers no receiver. */
    @Test
    fun aReconnectWithConnectivityPollsAfterTheBackoffDelay() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())

        assertThat(connectivityReceivers()).isEmpty()
        h.mainLooper.idleFor(9, TimeUnit.MILLISECONDS)
        assertThat(h.transports.tcps).hasSize(1)
        h.mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertThat(h.service.getSessionState().value)
            .isInstanceOf(SessionState.Reconnecting::class.java)
        // The socket is opened on the protocol thread, so the transport appears after the post.
        awaitUntil(description = "second connection attempt") { h.transports.tcps.size == 2 }
    }

    /**
     * The connectivity receiver's own input space: it retries only while the service still wants
     * to reconnect **and** the network is back.
     */
    @Test
    fun theConnectivityReceiverReconnectsOnlyWhenTheNetworkIsBack() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        @Suppress("DEPRECATION")
        val connected = connectivityManager().activeNetworkInfo
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(connectivityReceivers()).hasSize(1)

        // Still no network: the broadcast changes nothing and the receiver stays registered.
        sendConnectivityBroadcast(h)
        assertThat(h.transports.tcps).hasSize(1)
        assertThat(connectivityReceivers()).hasSize(1)

        // Network back: the receiver retries immediately rather than waiting out the backoff.
        shadowOf(connectivityManager()).setActiveNetworkInfo(connected)
        sendConnectivityBroadcast(h)
        awaitUntil(description = "immediate retry") { h.transports.tcps.size == 2 }
        assertThat(connectivityReceivers()).isEmpty()
    }

    /**
     * The receiver's first guard: once the session has ended, a late broadcast unregisters the
     * receiver instead of reconnecting. `cancelReconnect` already unregisters it, so this arm is
     * only reachable when the broadcast beats the unregistration - which is why the guard cannot
     * be dropped as redundant.
     */
    @Test
    fun aBroadcastThatArrivesAfterTheSessionEndedUnregistersTheReceiver() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        val receiver = connectivityReceivers().single().broadcastReceiver

        h.service.cancelReconnect()
        receiver.onReceive(RuntimeEnvironment.getApplication(), Intent())
        h.mainLooper.idle()

        assertThat(h.transports.tcps).hasSize(1)
        assertThat(connectivityReceivers()).isEmpty()
    }

    // ---------------------------------------------------------------- the missing server

    /**
     * Repair of a pre-existing crash that A9a characterized and handed on: `connect()` passed
     * `mServer` straight to `HumlaConnection.connect(Server)`, which is non-null in Kotlin, so a
     * connect without a configured server died on a parameter check on the main looper instead of
     * reporting a failed attempt. `IHumlaService.reconnect()` is public API, so a bound client can
     * reach it. One guard, in the one place both entry points pass through.
     */
    @Test
    fun aConnectWithoutATargetServerReportsAFailureInsteadOfCrashing() {
        val h = HumlaServiceHarness(server = null).also { harnesses += it }

        h.service.connect()
        h.mainLooper.idle()

        assertThat(h.transports.tcps).isEmpty()
        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Disconnected())
        assertThat(h.service.getConnectionState())
            .isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
        assertThat(h.disconnects).hasSize(1)
        assertThat(h.disconnects[0]!!.message).isEqualTo(h.service.getString(R.string.no_target_server))
    }
}
