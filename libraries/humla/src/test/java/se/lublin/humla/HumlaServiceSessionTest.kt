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
import org.robolectric.shadows.ShadowNetwork
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.model.WhisperTargetList
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.ClientCertificate
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.TimeUnit

/**
 * The session lifecycle: a state machine with exponential backoff that decides the wake lock, the
 * attempt counter and the user's wish to reconnect. Drives a real
 * [se.lublin.humla.net.HumlaConnection] over fake transports.
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

    private fun networkCallbacks() = shadowOf(connectivityManager()).networkCallbacks.toList()

    /** Tells every registered callback that a default network came up. */
    private fun networkAvailable(harness: HumlaServiceHarness) {
        val network = ShadowNetwork.newInstance(1)
        networkCallbacks().forEach { it.onAvailable(network) }
        harness.mainLooper.idle()
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    fun connectWalksDisconnectedToConnectingToConnected() {
        val h = start()
        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()

        h.service.connect()
        h.mainLooper.idle()
        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Connecting)
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTING)

        h.synchronize(h.openSocket(0))

        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Connected)
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTED)
        assertThat(h.service.isConnected).isTrue()
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
    }

    /** A second connect while one is in flight is the state machine's business, not the socket's. */
    @Test
    fun aSecondConnectWhileConnectedOpensNoSecondSocket() {
        val h = start()
        h.connectAndSynchronize()

        val connection = h.service.getConnection()
        h.service.connect()
        h.mainLooper.idle()

        // `getConnection()` rather than `transports.tcps.size`: a transport appears on the
        // protocol thread only later, so a size check would pass either way.
        assertThat(h.service.getConnection()).isSameInstanceAs(connection)
        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Connected)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /** The handshake offers Opus and no CELT version: Opus is the only codec this client has. */
    @Test
    fun theHandshakeOffersOpusOnly() {
        val h = start()

        h.service.connect()
        h.openSocket(0)

        val auth = h.transports.tcps[0].sentMessages.filterIsInstance<Mumble.Authenticate>().single()
        assertThat(auth.opus).isTrue()
        assertThat(auth.celtVersionsList).isEmpty()
    }

    @Test
    fun theHandshakeAdvertisesVersion150InBothFormats() {
        val h = start()

        h.service.connect()
        h.openSocket(0)

        val version = h.transports.tcps[0].sentMessages.filterIsInstance<Mumble.Version>().single()
        assertThat(version.versionV1).isEqualTo(0x010500)
        assertThat(version.versionV2).isEqualTo(0x0001_0005_0000_0000L)
    }

    @Test
    fun listeningToAChannelAddsAndRemovesItForTheOwnSession() {
        val h = start()
        val tcp = h.connectAndSynchronize()

        h.service.session.setListening(5, true)
        h.service.session.setListening(6, false)

        val states = tcp.sentMessages.filterIsInstance<Mumble.UserState>()
        assertThat(states.map { it.session }).containsExactly(1, 1)
        assertThat(states[0].listeningChannelAddList).containsExactly(5)
        assertThat(states[0].listeningChannelRemoveList).isEmpty()
        assertThat(states[1].listeningChannelRemoveList).containsExactly(6)
        assertThat(states[1].listeningChannelAddList).isEmpty()
        assertThat(states.none { it.hasChannelId() }).isTrue()
    }

    @Test
    fun userStatsAreRequestedInFull() {
        val h = start()
        val tcp = h.connectAndSynchronize()

        h.service.session.requestUserStats(7)

        val request = tcp.sentMessages.filterIsInstance<Mumble.UserStats>().single()
        assertThat(request.session).isEqualTo(7)
        assertThat(request.statsOnly).isFalse()
    }

    /**
     * Four settings the service writes into a `HumlaConnection` it does not own. The certificate
     * and trust store matter only once a TLS socket opens, so the connection's fields are read back.
     */
    @Test
    fun everyConnectionSettingReachesTheConnection() {
        val h = start()
        h.configure {
            // Force TCP without Tor, so the two fields differ: with both true, Tor masks a missing
            // `setForceTCP` through `shouldForceTCP()`.
            copy(
                forceTcp = true,
                useTor = false,
                certificate = ClientCertificate(byteArrayOf(1, 2, 3), "cert-pw"),
                trustStorePath = "/store",
                trustStorePassword = "store-pw",
                trustStoreFormat = "BKS",
            )
        }

        h.service.connect()
        h.mainLooper.idle()
        val connection = h.service.getConnection()!!

        assertThat(connection.forceTcp).isEqualTo(true)
        assertThat(connection.useTor).isEqualTo(false)
        assertThat(connection.certificate).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(connection.certificatePassword).isEqualTo("cert-pw")
        assertThat(connection.trustStorePath).isEqualTo("/store")
        assertThat(connection.trustStorePassword).isEqualTo("store-pw")
        assertThat(connection.trustStoreFormat).isEqualTo("BKS")
    }

    /** The other configuration, where Tor is on: `useTor` is what carries it to the connection. */
    @Test
    fun torReachesTheConnectionAsItsOwnFlag() {
        val h = start()
        h.configure { copy(useTor = true) }

        h.service.connect()
        h.mainLooper.idle()

        assertThat(h.service.getConnection()!!.useTor).isEqualTo(true)
    }

    // ---------------------------------------------------------------- loss and backoff

    @Test
    fun aConnectionErrorWithAutoReconnectEntersConnectionLostAndKeepsTheWakeLock() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())

        val state = h.service.sessionState.value as SessionState.ConnectionLost
        assertThat(state.attempt).isEqualTo(1)
        assertThat(state.reconnectInMillis).isEqualTo(10L)
        assertThat(h.service.isReconnecting).isTrue()
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
        // Only Disconnected releases it.
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
        assertThat(h.service.connectionError).isNotNull()
    }

    /** The four corners of `onConnectionDisconnected`: disconnect reason x `autoReconnect`. */
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
            assertThat(h.service.connectionState)
                .isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
            assertThat(h.service.isReconnecting).isEqualTo(shouldReconnect)
            assertThat(h.service.isWakeLockHeldForTest()).isEqualTo(shouldReconnect)
        }
    }

    /** A clean disconnect never reconnects, whatever `autoReconnect` says. */
    @Test
    fun aCleanDisconnectGoesToDisconnectedAndNeverReconnects() {
        for (autoReconnect in listOf(false, true)) {
            val h = start(autoReconnect = autoReconnect)
            h.connectAndSynchronize()

            h.service.disconnect()
            // Derived from the session state, so already over before the connection reports back.
            assertThat(h.service.isConnected).isFalse()
            h.mainLooper.idle()

            assertThat(h.service.sessionState.value).isEqualTo(SessionState.Disconnected())
            assertThat(h.service.connectionState)
                .isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
            assertThat(h.service.isReconnecting).isFalse()
            assertThat(h.service.isWakeLockHeldForTest()).isFalse()
            // The giving-up line belongs to a spent auto-reconnect, not to every disconnect.
            assertThat(h.warnings).doesNotContain(h.service.getString(R.string.reconnect_gave_up))
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
            assertThat(h.service.sessionState.value)
                .isInstanceOf(SessionState.ConnectionLost::class.java)
            h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer fires
            h.openSocket(index)
            h.failConnection(index, connectionError())
        }
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.openSocket(3)
        h.failConnection(3, connectionError())

        assertThat(h.service.sessionState.value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        assertThat((h.service.sessionState.value as SessionState.Disconnected).error).isNotNull()
        assertThat(h.service.isReconnecting).isFalse()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.warnings).contains(h.service.getString(R.string.reconnect_gave_up))
    }

    /** A session that synchronizes again starts the budget over; otherwise a flaky link runs out. */
    @Test
    fun aSuccessfulSessionResetsTheAttemptCounter() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())
        assertThat((h.service.sessionState.value as SessionState.ConnectionLost).attempt)
            .isEqualTo(1)
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.synchronize(h.openSocket(1))

        h.failConnection(1, connectionError())

        assertThat((h.service.sessionState.value as SessionState.ConnectionLost).attempt)
            .isEqualTo(1)
    }

    /**
     * The wake lock is taken once per session, not per synchronization: it is reference counted,
     * so acquiring on every reconnect would leave a count the single release does not balance.
     */
    @Test
    fun aReconnectDoesNotLeaveASecondWakeLockCountBehind() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.synchronize(h.openSocket(1))
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
    }

    /**
     * A user disconnect that races the socket dying must not reconnect: `disconnect()` tells the
     * state machine before the report arrives, so `lost()` finds the session already over.
     */
    @Test
    fun aDisconnectThatRacesTheSocketDyingDoesNotReconnect() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.service.disconnect()
        // The report the socket had already queued when the user pressed disconnect. Delivered
        // directly, because disconnect() quits the protocol looper.
        h.service.onConnectionDisconnected(connectionError())
        h.mainLooper.idle()

        assertThat(h.service.sessionState.value)
            .isEqualTo(SessionState.Disconnected())
        assertThat(h.service.isReconnecting).isFalse()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertThat(h.transports.tcps).hasSize(1)
    }

    @Test
    fun cancelReconnectStopsTheTimerAndEndsTheSession() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())

        h.service.cancelReconnect()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        assertThat(h.service.sessionState.value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        // The error stays visible: the UI is still showing why the session ended.
        assertThat(h.service.connectionError).isNotNull()
        assertThat(h.service.isReconnecting).isFalse()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.transports.tcps).hasSize(1) // no reconnect was attempted
    }

    /**
     * Nothing removes a pending retry post, so it arrives after the user has cancelled, and the
     * state machine must refuse it.
     */
    @Test
    fun aRetryThatFiresAfterTheUserCancelledIsRefused() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())
        val connection = h.service.getConnection()

        h.service.cancelReconnect()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS) // the post fires in here

        assertThat(h.service.getConnection()).isSameInstanceAs(connection)
        assertThat(h.service.sessionState.value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /**
     * Cancelling while a retry is in flight must stop that attempt too; otherwise a successful
     * attempt would take the wake lock and start the microphone for a session the user gave up on.
     */
    @Test
    fun cancelReconnectDuringAnAttemptInFlightDisconnectsThatAttempt() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())
        awaitUntil(description = "the retry opened a second socket") {
            h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
            h.transports.tcps.size > 1 && h.transports.tcps[1].connectThread != null
        }
        assertThat(h.service.sessionState.value).isInstanceOf(SessionState.Reconnecting::class.java)

        h.service.cancelReconnect()
        awaitUntil(description = "the attempt in flight was disconnected") {
            h.mainLooper.idle()
            h.transports.tcps[1].disconnectCalls > 0
        }
        h.mainLooper.idle()

        assertThat(h.service.sessionState.value).isInstanceOf(SessionState.Disconnected::class.java)
        // The cancelled session's error stays what the UI shows, also after the attempt's own
        // (error-free) disconnect report has arrived.
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
        assertThat(h.service.connectionError).isNotNull()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.warnings).doesNotContain(h.service.getString(R.string.reconnect_gave_up))
    }

    /**
     * The attempt in flight can end on its own error as the user cancels. That report arrives in
     * Disconnected with a CONNECTION_ERROR, but the user ended it, so no "Giving up." line.
     */
    @Test
    fun aLateErrorReportAfterACancelDoesNotClaimTheReconnectGaveUp() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())

        h.service.cancelReconnect()
        h.service.onConnectionDisconnected(connectionError())
        h.mainLooper.idle()

        assertThat(h.warnings).doesNotContain(h.service.getString(R.string.reconnect_gave_up))
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
    }

    /**
     * A disconnect during the backoff ends the session for good and must release what only
     * Disconnected releases. The connection already reported its end, so no report path runs.
     */
    @Test
    fun aDisconnectWhileWaitingToReconnectReleasesTheWakeLockAndTheNetworkCallback() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null) // waits for the network
        h.failConnection(0, connectionError())
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
        assertThat(networkCallbacks()).isNotEmpty()

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(networkCallbacks()).isEmpty()
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
    }

    /**
     * The other half of the same line: with a live connection, its own disconnect report is what
     * releases the session, so the wake lock still covers the teardown that report runs.
     */
    @Test
    fun aDisconnectDuringALiveSessionReleasesTheWakeLockWithTheConnectionsReport() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.service.disconnect()
        assertThat(h.service.isWakeLockHeldForTest()).isTrue() // the report is still queued

        h.mainLooper.idle()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
    }

    /** The same through the service going away, which disconnects. */
    @Test
    fun destroyingTheServiceWhileWaitingToReconnectReleasesTheWakeLock() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()

        h.destroy()

        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
    }

    /** `cancelReconnect` on a live session is a no-op: no wake lock release, no CONNECTION_LOST. */
    @Test
    fun cancelReconnectDuringALiveSessionChangesNothing() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.service.cancelReconnect()
        h.mainLooper.idle()

        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Connected)
        assertThat(h.service.connectionState).isEqualTo(HumlaService.ConnectionState.CONNECTED)
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
    }

    @Test
    fun cancellingAReconnectThatNeverStartedIsHarmless() {
        val h = start()

        h.service.cancelReconnect()

        assertThat(h.service.isReconnecting).isFalse()
        assertThat(networkCallbacks()).isEmpty()
    }

    private fun whisperTarget(h: HumlaServiceHarness) =
        WhisperTargetChannel(h.service.rootChannel!!, false, false, null)

    /**
     * The thirty whisper slots are the session's, not the service's; without the clear on
     * disconnect, a long-lived service runs out of them across connections.
     */
    @Test
    fun aNewSessionGetsItsWhisperSlotsBack() {
        val h = start()
        h.connectAndSynchronize()
        repeat(WhisperTargetList.TARGET_MAX - WhisperTargetList.TARGET_MIN + 1) {
            assertThat(h.service.registerWhisperTarget(whisperTarget(h)))
                .isNotEqualTo((-1).toByte())
        }
        assertThat(h.service.registerWhisperTarget(whisperTarget(h)))
            .isEqualTo((-1).toByte())

        h.service.disconnect()
        h.mainLooper.idle()
        h.service.connect()
        h.synchronize(h.openSocket(1))

        assertThat(h.service.registerWhisperTarget(whisperTarget(h)))
            .isNotEqualTo((-1).toByte())
    }

    // ---------------------------------------------------------------- connectivity

    /**
     * Without connectivity the service does **not** burn attempts: it registers the network
     * callback and waits. Idling a full minute past the backoff shows nothing was queued at all.
     */
    @Test
    fun aReconnectWithoutConnectivityWaitsForTheNetworkInstead() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)

        h.failConnection(0, connectionError())

        assertThat(h.service.isReconnecting).isTrue()
        assertThat(networkCallbacks()).hasSize(1)
        h.mainLooper.idleFor(60, TimeUnit.SECONDS)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /** With connectivity it polls instead, and registers no network callback. */
    @Test
    fun aReconnectWithConnectivityPollsAfterTheBackoffDelay() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())

        assertThat(networkCallbacks()).isEmpty()
        h.mainLooper.idleFor(9, TimeUnit.MILLISECONDS)
        assertThat(h.transports.tcps).hasSize(1)
        h.mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertThat(h.service.sessionState.value)
            .isInstanceOf(SessionState.Reconnecting::class.java)
        // Reconnecting is still "reconnecting" to the UI, and it still knows why.
        assertThat(h.service.isReconnecting).isTrue()
        assertThat(h.service.connectionError).isNotNull()
        // The socket is opened on the protocol thread, so the transport appears after the post.
        awaitUntil(description = "second connection attempt") { h.transports.tcps.size == 2 }
    }

    /** A default network coming back retries at once instead of waiting out the backoff. */
    @Test
    fun theNetworkCallbackReconnectsAsSoonAsTheNetworkIsBack() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(networkCallbacks()).hasSize(1)
        assertThat(h.transports.tcps).hasSize(1)

        networkAvailable(h)

        awaitUntil(description = "immediate retry") { h.transports.tcps.size == 2 }
        assertThat(networkCallbacks()).isEmpty()
    }

    /**
     * Once the session has ended, a late callback unregisters itself instead of reconnecting.
     * Reachable only when the callback beats `cancelReconnect`'s unregistration.
     */
    @Test
    fun aCallbackThatArrivesAfterTheSessionEndedDoesNotReconnect() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        val callback = networkCallbacks().single()

        h.service.cancelReconnect()
        callback.onAvailable(ShadowNetwork.newInstance(1))
        h.mainLooper.idle()

        assertThat(h.transports.tcps).hasSize(1)
        assertThat(networkCallbacks()).isEmpty()
    }

    /** `cancelReconnect` unregisters the callback on its own, without a callback to help it. */
    @Test
    fun cancellingWhileWaitingForTheNetworkUnregistersTheNetworkCallback() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(networkCallbacks()).hasSize(1)

        h.service.cancelReconnect()

        assertThat(networkCallbacks()).isEmpty()
    }

    /**
     * `onDestroy` unregisters it when the connection is already dead: no second disconnect report
     * arrives, so `releaseSessionResources` is never reached.
     */
    @Test
    fun destroyingTheServiceWhileWaitingForTheNetworkUnregistersTheNetworkCallback() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(networkCallbacks()).hasSize(1)

        h.destroy()
        harnesses.remove(h)

        assertThat(networkCallbacks()).isEmpty()
    }

    /**
     * A manual `connect()` while waiting for the network leaves the callback registered, so the
     * next callback finds a session that is no longer lost and must not restore connectivity. The
     * network stays down so that the state check is what fires.
     */
    @Test
    fun aCallbackAfterAManualConnectUnregistersItInsteadOfRetrying() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        val callback = networkCallbacks().single()

        h.service.connect() // Connecting, and the callback is still registered
        h.mainLooper.idle()
        val connection = h.service.getConnection()
        callback.onAvailable(ShadowNetwork.newInstance(1))
        h.mainLooper.idle()

        assertThat(networkCallbacks()).isEmpty()
        assertThat(h.service.getConnection()).isSameInstanceAs(connection)
    }

    // ---------------------------------------------------------------- the missing server

    /**
     * A connect without a configured server is reported as a failed attempt instead of crashing on
     * the main looper. `IHumlaService.reconnect()` is public API, so a bound client can reach it.
     */
    @Test
    fun aConnectWithoutATargetServerReportsAFailureInsteadOfCrashing() {
        val h = HumlaServiceHarness(server = null).also { harnesses += it }

        h.service.connect()
        h.mainLooper.idle()

        assertThat(h.transports.tcps).isEmpty()
        assertThat(h.service.sessionState.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.service.connectionState)
            .isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
        assertThat(h.disconnects).hasSize(1)
        assertThat(h.disconnects[0]!!.message).isEqualTo(h.service.getString(R.string.no_target_server))
    }
}
