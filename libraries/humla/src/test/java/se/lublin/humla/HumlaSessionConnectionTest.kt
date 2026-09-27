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
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.ClientCertificate
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.EventRecorder
import se.lublin.humla.testutil.HumlaSessionHarness
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.testutil.collectOnMain
import se.lublin.humla.testutil.isReconnecting
import se.lublin.humla.testutil.reason
import se.lublin.humla.util.VoiceTargetMode
import java.util.concurrent.TimeUnit

/**
 * The session lifecycle: a state machine with exponential backoff that decides the wake lock, the
 * attempt counter and the user's wish to reconnect. Drives a real
 * [se.lublin.humla.net.HumlaConnection] over fake transports.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaSessionConnectionTest {
    private val harnesses = mutableListOf<HumlaSessionHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
    }

    private fun start(autoReconnect: Boolean = false): HumlaSessionHarness =
        HumlaSessionHarness(autoReconnect = autoReconnect).also { harnesses += it }

    private fun connectionError() =
        HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

    private fun connectivityManager() = RuntimeEnvironment.getApplication()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun networkCallbacks() = shadowOf(connectivityManager()).networkCallbacks.toList()

    /** Tells every registered callback that a default network came up. */
    private fun networkAvailable(harness: HumlaSessionHarness) {
        val network = ShadowNetwork.newInstance(1)
        networkCallbacks().forEach { it.onAvailable(network) }
        harness.mainLooper.idle()
    }

    @Test
    fun connectWalksDisconnectedToConnectingToConnected() {
        val h = start()
        assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()

        h.session.connect()
        h.mainLooper.idle()
        assertThat(h.session.state.value).isEqualTo(SessionState.Connecting)

        h.synchronize(h.openSocket(0))

        assertThat(h.session.state.value).isEqualTo(SessionState.Connected)
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue()
    }

    /** A second connect while one is in flight is the state machine's business, not the socket's. */
    @Test
    fun aSecondConnectWhileConnectedOpensNoSecondSocket() {
        val h = start()
        h.connectAndSynchronize()

        val connection = h.session.connection
        h.session.connect()
        h.mainLooper.idle()

        // `connection` rather than `transports.tcps.size`: a transport appears on the protocol
        // context only later, so a size check would pass either way.
        assertThat(h.session.connection).isSameInstanceAs(connection)
        assertThat(h.session.state.value).isEqualTo(SessionState.Connected)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /** The handshake names the configured server's user and carries the access tokens. */
    @Test
    fun theHandshakeCarriesTheCredentialsAndTheAccessTokens() {
        val h = start()
        h.configure { copy(accessTokens = listOf("red")) }

        h.session.connect()
        h.openSocket(0)

        val sent = h.transports.tcps[0].sentMessages
        assertThat(sent.filterIsInstance<Mumble.Version>().single().release).isEqualTo("harness")
        val auth = sent.filterIsInstance<Mumble.Authenticate>().single()
        assertThat(auth.username).isEqualTo("me")
        assertThat(auth.tokensList).containsExactly("red")
    }

    @Test
    fun listeningToAChannelAddsAndRemovesItForTheOwnSession() {
        val h = start()
        val tcp = h.connectAndSynchronize()

        h.session.actions.setListening(5, true)
        h.session.actions.setListening(6, false)

        val states = tcp.sentMessages.filterIsInstance<Mumble.UserState>()
        assertThat(states.map { it.session }).containsExactly(1, 1)
        assertThat(states[0].listeningChannelAddList).containsExactly(5)
        assertThat(states[0].listeningChannelRemoveList).isEmpty()
        assertThat(states[1].listeningChannelRemoveList).containsExactly(6)
        assertThat(states[1].listeningChannelAddList).isEmpty()
        assertThat(states.none { it.hasChannelId() }).isTrue()
    }

    /**
     * The settings the session opens a `HumlaConnection` with. The certificate and trust store
     * matter only once a TLS socket opens, so the connection's parameters are read back.
     */
    @Test
    fun everyConnectionSettingReachesTheConnection() {
        val h = start()
        h.configure {
            // Force TCP without Tor, so the two fields differ: with both true, Tor would mask a
            // missing forceTcp through `tunnelVoice`.
            copy(connection = connection.copy(
                forceTcp = true,
                useTor = false,
                certificate = ClientCertificate(byteArrayOf(1, 2, 3), "cert-pw"),
                trustStorePath = "/store",
                trustStorePassword = "store-pw",
                trustStoreFormat = "BKS",
            ))
        }

        h.session.connect()
        h.mainLooper.idle()
        val connection = h.session.connection!!

        assertThat(connection.params.forceTcp).isEqualTo(true)
        assertThat(connection.params.useTor).isEqualTo(false)
        assertThat(connection.params.certificate).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(connection.params.certificatePassword).isEqualTo("cert-pw")
        assertThat(connection.params.trustStore?.path).isEqualTo("/store")
        assertThat(connection.params.trustStore?.password).isEqualTo("store-pw")
        assertThat(connection.params.trustStore?.format).isEqualTo("BKS")
    }

    /** The other configuration, where Tor is on: `useTor` is what carries it to the connection. */
    @Test
    fun torReachesTheConnectionAsItsOwnFlag() {
        val h = start()
        h.configure { copy(connection = connection.copy(useTor = true)) }

        h.session.connect()
        h.mainLooper.idle()

        assertThat(h.session.connection!!.params.useTor).isEqualTo(true)
    }

    @Test
    fun aConnectionErrorWithAutoReconnectEntersConnectionLostAndKeepsTheWakeLock() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())

        val state = h.session.state.value as SessionState.ConnectionLost
        assertThat(state.attempt).isEqualTo(1)
        assertThat(state.reconnectInMillis).isEqualTo(10L)
        assertThat(h.session.isReconnecting).isTrue()
        // Only Disconnected releases it.
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue()
        assertThat(h.session.reason).isNotNull()
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
            assertThat(h.session.reason).isNotNull()
            assertThat(h.session.isReconnecting).isEqualTo(shouldReconnect)
            assertThat(h.session.lifecycle.isWakeLockHeld).isEqualTo(shouldReconnect)
        }
    }

    /** A clean disconnect never reconnects, whatever `autoReconnect` says. */
    @Test
    fun aCleanDisconnectGoesToDisconnectedAndNeverReconnects() {
        for (autoReconnect in listOf(false, true)) {
            val h = start(autoReconnect = autoReconnect)
            h.connectAndSynchronize()

            h.session.disconnect()
            // Derived from the session state, so already over before the connection reports back.
            assertThat(h.session.state.value).isNotEqualTo(SessionState.Connected)
            h.mainLooper.idle()

            assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected())
            assertThat(h.session.isReconnecting).isFalse()
            assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
            // The giving-up line belongs to a spent auto-reconnect, not to every disconnect.
            assertThat(h.warnings).doesNotContain(h.string(R.string.reconnect_gave_up))
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
            assertThat(h.session.state.value)
                .isInstanceOf(SessionState.ConnectionLost::class.java)
            h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS) // backoff timer fires
            h.openSocket(index)
            h.failConnection(index, connectionError())
        }
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.openSocket(3)
        h.failConnection(3, connectionError())

        assertThat(h.session.state.value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        assertThat((h.session.state.value as SessionState.Disconnected).reason).isNotNull()
        assertThat(h.session.isReconnecting).isFalse()
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
        assertThat(h.warnings).contains(h.string(R.string.reconnect_gave_up))
    }

    /** A session that synchronizes again starts the budget over; otherwise a flaky link runs out. */
    @Test
    fun aSuccessfulSessionResetsTheAttemptCounter() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.failConnection(0, connectionError())
        assertThat((h.session.state.value as SessionState.ConnectionLost).attempt)
            .isEqualTo(1)
        h.mainLooper.idleFor(10, TimeUnit.MILLISECONDS)
        h.synchronize(h.openSocket(1))

        h.failConnection(1, connectionError())

        assertThat((h.session.state.value as SessionState.ConnectionLost).attempt)
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
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue()

        h.session.disconnect()
        h.mainLooper.idle()

        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
    }

    /**
     * A user disconnect that races the socket dying must not reconnect: `disconnect()` tells the
     * state machine before the report arrives, so `lost()` finds the session already over.
     */
    @Test
    fun aDisconnectThatRacesTheSocketDyingDoesNotReconnect() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.session.disconnect()
        // The report the socket had already queued when the user pressed disconnect. Delivered
        // directly, because the closed connection reports nothing more.
        h.session.onConnectionDisconnected(connectionError())
        h.mainLooper.idle()

        assertThat(h.session.state.value)
            .isEqualTo(SessionState.Disconnected())
        assertThat(h.session.isReconnecting).isFalse()
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertThat(h.transports.tcps).hasSize(1)
    }

    @Test
    fun cancelReconnectStopsTheTimerAndEndsTheSession() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())

        h.session.cancelReconnect()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        assertThat(h.session.state.value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        // The error stays visible: the UI is still showing why the session ended.
        assertThat(h.session.reason).isNotNull()
        assertThat(h.session.isReconnecting).isFalse()
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
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
        val connection = h.session.connection

        h.session.cancelReconnect()
        h.mainLooper.idleFor(100, TimeUnit.MILLISECONDS) // the post fires in here

        assertThat(h.session.connection).isSameInstanceAs(connection)
        assertThat(h.session.state.value)
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
            h.transports.tcps.size > 1 && h.transports.tcps[1].isConnectCalled
        }
        assertThat(h.session.state.value).isInstanceOf(SessionState.Reconnecting::class.java)

        h.session.cancelReconnect()
        awaitUntil(description = "the attempt in flight was disconnected") {
            h.mainLooper.idle()
            h.transports.tcps[1].disconnectCalls > 0
        }
        h.mainLooper.idle()

        assertThat(h.session.state.value).isInstanceOf(SessionState.Disconnected::class.java)
        // The cancelled session's error stays what the UI shows, also after the attempt's own
        // (error-free) disconnect report has arrived.
        assertThat(h.session.reason).isNotNull()
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
        assertThat(h.warnings).doesNotContain(h.string(R.string.reconnect_gave_up))
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

        h.session.cancelReconnect()
        h.session.onConnectionDisconnected(connectionError())
        h.mainLooper.idle()

        assertThat(h.warnings).doesNotContain(h.string(R.string.reconnect_gave_up))
        assertThat(h.session.reason).isNotNull()
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
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue()
        assertThat(networkCallbacks()).isNotEmpty()

        h.session.disconnect()
        h.mainLooper.idle()

        assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected())
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
        assertThat(networkCallbacks()).isEmpty()
    }

    /**
     * The other half of the same line: with a live connection, its own disconnect report is what
     * releases the session, so the wake lock still covers the teardown that report runs.
     */
    @Test
    fun aDisconnectDuringALiveSessionReleasesTheWakeLockWithTheConnectionsReport() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.session.disconnect()
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue() // the report is still queued

        h.mainLooper.idle()
        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
    }

    /** The same through the session being closed, which disconnects. */
    @Test
    fun closingTheSessionWhileWaitingToReconnectReleasesTheWakeLock() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        h.failConnection(0, connectionError())
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue()

        h.close()

        assertThat(h.session.lifecycle.isWakeLockHeld).isFalse()
    }

    /** `cancelReconnect` on a live session is a no-op: no wake lock release, no CONNECTION_LOST. */
    @Test
    fun cancelReconnectDuringALiveSessionChangesNothing() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.session.cancelReconnect()
        h.mainLooper.idle()

        assertThat(h.session.state.value).isEqualTo(SessionState.Connected)
        assertThat(h.session.lifecycle.isWakeLockHeld).isTrue()
    }

    @Test
    fun cancellingAReconnectThatNeverStartedIsHarmless() {
        val h = start()

        h.session.cancelReconnect()

        assertThat(h.session.isReconnecting).isFalse()
        assertThat(networkCallbacks()).isEmpty()
    }

    private fun whisperTarget() =
        WhisperTargetChannel(ChannelState(0, "Root"), false, false, null)

    /**
     * The whisper slots are the connection's, not the session's: a new connection starts with
     * normal speech and the slots cleared.
     */
    @Test
    fun aNewConnectionStartsWithoutThePreviousWhisperTarget() {
        val h = start()
        h.connectAndSynchronize()
        assertThat(h.session.actions.whisperTo(whisperTarget())).isTrue()
        assertThat(h.session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.WHISPER)

        h.session.disconnect()
        h.mainLooper.idle()
        assertThat(h.session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(h.session.actions.whisperTarget).isNull()
        h.session.connect()
        h.synchronize(h.openSocket(1))

        assertThat(h.session.actions.whisperTo(whisperTarget())).isTrue()
    }

    /**
     * Without connectivity the session does **not** burn attempts: it registers the network
     * callback and waits. Idling a full minute past the backoff shows nothing was queued at all.
     */
    @Test
    fun aReconnectWithoutConnectivityWaitsForTheNetworkInstead() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)

        h.failConnection(0, connectionError())

        assertThat(h.session.isReconnecting).isTrue()
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
        assertThat(h.session.state.value)
            .isInstanceOf(SessionState.Reconnecting::class.java)
        // Reconnecting is still "reconnecting" to the UI, and it still knows why.
        assertThat(h.session.isReconnecting).isTrue()
        assertThat(h.session.reason).isNotNull()
        // The socket is opened on the protocol context, so the transport appears later.
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

        h.session.cancelReconnect()
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

        h.session.cancelReconnect()

        assertThat(networkCallbacks()).isEmpty()
    }

    /**
     * `onDestroy` unregisters it when the connection is already dead: no second disconnect report
     * arrives, so `releaseSessionResources` is never reached.
     */
    @Test
    fun closingTheSessionWhileWaitingForTheNetworkUnregistersTheNetworkCallback() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(networkCallbacks()).hasSize(1)

        h.close()
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

        h.session.connect() // Connecting, and the callback is still registered
        h.mainLooper.idle()
        val connection = h.session.connection
        callback.onAvailable(ShadowNetwork.newInstance(1))
        h.mainLooper.idle()

        assertThat(networkCallbacks()).isEmpty()
        assertThat(h.session.connection).isSameInstanceAs(connection)
    }

    /** A connect without a configured server ends as a failed attempt instead of crashing on the main looper. */
    @Test
    fun aConnectWithoutATargetServerReportsAFailureInsteadOfCrashing() {
        val h = HumlaSessionHarness(server = null).also { harnesses += it }

        h.session.connect()
        h.mainLooper.idle()

        assertThat(h.transports.tcps).isEmpty()
        assertThat(h.session.state.value)
            .isEqualTo(SessionState.Disconnected(DisconnectReason.Failed(h.string(R.string.no_target_server), null)))
    }

    /** A state collector that ends the session as it starts connecting leaves no attempt behind. */
    @Test
    fun aCollectorThatDisconnectsOnConnectingStartsNoAttempt() {
        val h = start()
        collectOnMain(h.session.state) { if (it == SessionState.Connecting) h.session.disconnect() }

        h.session.connect()
        h.mainLooper.idle()

        assertThat(h.session.connection).isNull()
        assertThat(h.session.state.value).isEqualTo(SessionState.Disconnected())
    }

    /**
     * A connection replaced by the next attempt may still report its end; the session ignores it,
     * so a clean reconnect after a disconnect is not ended by the old connection's late report.
     */
    @Test
    fun theLateReportOfAReplacedConnectionDoesNotEndTheNextAttempt() {
        val h = start()
        h.connectAndSynchronize()

        h.session.disconnect()
        h.session.connect() // before the old connection's report was delivered
        h.mainLooper.idle()

        assertThat(h.session.state.value).isEqualTo(SessionState.Connecting)
        h.synchronize(h.openSocket(1))
        assertThat(h.session.state.value).isEqualTo(SessionState.Connected)
    }

    @Test
    fun aSentMessageIsPublishedForTheChatLog() {
        val h = start()
        h.connectAndSynchronize()
        val recorder = EventRecorder(h.session)

        val returned = h.session.actions.sendChannelTextMessage(0, "hi", tree = false)

        val published = recorder.of<HumlaEvent.MessageSent>().single().message
        assertThat(published).isSameInstanceAs(returned)
        assertThat(published.message).isEqualTo("hi")
        assertThat(published.actorName).isEqualTo("me")
        assertThat(published.targetChannels.map { it.id }).containsExactly(0)
    }

    /** A kick names its actor from the model it came with. */
    @Test
    fun aKickEndsTheSessionWithTheActorsName() {
        val h = start(autoReconnect = true)
        val tcp = h.connectAndSynchronize()
        tcp.simulateMessage(
            HumlaTCPMessageType.UserState,
            Mumble.UserState.newBuilder().setSession(4).setName("Mod").setChannelId(0).build().toByteArray(),
        )

        tcp.simulateMessage(
            HumlaTCPMessageType.UserRemove,
            Mumble.UserRemove.newBuilder().setSession(1).setActor(4).setReason("spam").build().toByteArray(),
        )
        awaitUntil(description = "the kick is reported") {
            h.mainLooper.idle()
            h.session.state.value is SessionState.Disconnected
        }

        // Not a network failure, so no reconnect either.
        assertThat(h.session.state.value)
            .isEqualTo(SessionState.Disconnected(DisconnectReason.Kicked("spam", "Mod", banned = false)))
    }
}
