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
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.model.WhisperTargetList
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

        val connection = h.service.getConnection()
        h.service.connect()
        h.mainLooper.idle()

        // `getConnection()` and not `transports.tcps.size`: startSession sets mConnection on this
        // thread, while a transport appears on the protocol thread a moment later -- so a size
        // check here passes whether or not a second session was started (measured, G1).
        assertThat(h.service.getConnection()).isSameInstanceAs(connection)
        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Connected)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /**
     * The handshake announces the CELT bitstream version the seam provides. Without this the seam
     * had no reader at all: `FakeTcpTransport` records the message *type*, not its content, so
     * replacing the loop with a constant left the whole suite green (measured, S8).
     */
    @Test
    fun theHandshakeAnnouncesTheCeltVersionsFromTheSeam() {
        val h = start()
        assertThat(h.celtAnnouncements).isEmpty()

        h.service.connect()
        h.openSocket(0)

        assertThat(h.celtAnnouncements).hasSize(1)
        assertThat(h.celtAnnouncements[0].toList()).containsExactly(0x8000000b.toInt())
    }

    /**
     * Effect pass (spec 4.04): four settings the service writes into a `HumlaConnection` it does
     * not own. Three of them have no reader at all on the JVM -- the certificate and the trust
     * store only matter once a TLS socket is opened -- so the connection's own fields are the only
     * place they can be read back, and without this all four survived their mutations (E1-E4).
     */
    @Test
    fun everyConnectionSettingReachesTheConnection() {
        val h = start()
        h.configure {
            // FORCE_TCP without TOR, so the two fields differ: Tor sets `mForceTcp` too
            // (`mForceTcp or mUseTor`), and with both true a deleted `setForceTCP` is masked by
            // `setUseTor` through `shouldForceTCP()`.
            putBoolean(HumlaService.EXTRAS_FORCE_TCP, true)
            putBoolean(HumlaService.EXTRAS_USE_TOR, false)
            putByteArray(HumlaService.EXTRAS_CERTIFICATE, byteArrayOf(1, 2, 3))
            putString(HumlaService.EXTRAS_CERTIFICATE_PASSWORD, "cert-pw")
            putString(HumlaService.EXTRAS_TRUST_STORE, "/store")
            putString(HumlaService.EXTRAS_TRUST_STORE_PASSWORD, "store-pw")
            putString(HumlaService.EXTRAS_TRUST_STORE_FORMAT, "BKS")
        }

        h.service.connect()
        h.mainLooper.idle()
        val connection = h.service.getConnection()!!

        assertThat(field(connection, "forceTcp")).isEqualTo(true)
        assertThat(field(connection, "useTor")).isEqualTo(false)
        assertThat(field(connection, "certificate") as ByteArray?).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(field(connection, "certificatePassword")).isEqualTo("cert-pw")
        assertThat(field(connection, "trustStorePath")).isEqualTo("/store")
        assertThat(field(connection, "trustStorePassword")).isEqualTo("store-pw")
        assertThat(field(connection, "trustStoreFormat")).isEqualTo("BKS")
    }

    /** The other configuration, where Tor is on: `useTor` is what carries it to the connection. */
    @Test
    fun torReachesTheConnectionAsItsOwnFlag() {
        val h = start()
        h.configure { putBoolean(HumlaService.EXTRAS_USE_TOR, true) }

        h.service.connect()
        h.mainLooper.idle()

        assertThat(field(h.service.getConnection()!!, "useTor")).isEqualTo(true)
    }

    private fun field(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("no field $name on ${target.javaClass}")
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

    /**
     * The wake lock is taken **once per session**, not once per synchronization: it is reference
     * counted, so an unconditional `acquire()` across a reconnect leaves a count that the single
     * release on Disconnected does not balance, and the CPU never sleeps again (measured, G5).
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
     * A user disconnect that races the socket dying must not reconnect. `disconnect()` tells the
     * state machine **before** the report arrives, so `lost()` finds a session that is already
     * over and returns Disconnected instead of scheduling a retry (measured, S23: without that
     * line an error arriving after disconnect() starts an auto-reconnect the user cancelled).
     */
    @Test
    fun aDisconnectThatRacesTheSocketDyingDoesNotReconnect() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.service.disconnect()
        // The report the socket had already queued when the user pressed disconnect. Delivered
        // straight, because disconnect() quits the protocol looper and a fake transport can no
        // longer post through it -- the interleaving is the point, not the route it takes.
        h.service.onConnectionDisconnected(connectionError())
        h.mainLooper.idle()

        assertThat(h.service.getSessionState().value)
            .isEqualTo(SessionState.Disconnected())
        assertThat(h.service.isReconnecting()).isFalse()
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

        assertThat(h.service.getSessionState().value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        // The error stays visible: the UI is still showing why the session ended.
        assertThat(h.service.getConnectionError()).isNotNull()
        assertThat(h.service.isReconnecting()).isFalse()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.transports.tcps).hasSize(1) // no reconnect was attempted
    }

    /**
     * The one mechanism that decides whether a scheduled retry may run. Nothing removes the
     * pending post, so it really does arrive here after the user has cancelled -- and the state
     * machine refuses it (measured, G14: without the check the cancelled session reconnects).
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
        assertThat(h.service.getSessionState().value)
            .isInstanceOf(SessionState.Disconnected::class.java)
        assertThat(h.transports.tcps).hasSize(1)
    }

    /**
     * Cancelling while a retry is in flight (Reconnecting) must stop that attempt too. Before the
     * fix the state machine went to Disconnected but the connection kept going: had it succeeded,
     * onConnectionSynchronized would have set CONNECTED, taken the wake lock and started the
     * microphone for a session the user had given up on -- with the service out of the foreground.
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
        assertThat(h.service.getSessionState().value).isInstanceOf(SessionState.Reconnecting::class.java)

        h.service.cancelReconnect()
        awaitUntil(description = "the attempt in flight was disconnected") {
            h.mainLooper.idle()
            h.transports.tcps[1].disconnectCalls > 0
        }
        h.mainLooper.idle()

        assertThat(h.service.getSessionState().value).isInstanceOf(SessionState.Disconnected::class.java)
        // The cancelled session's error stays what the UI shows, also after the attempt's own
        // (error-free) disconnect report has arrived.
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
        assertThat(h.service.getConnectionError()).isNotNull()
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(h.warnings).doesNotContain(h.service.getString(R.string.reconnect_gave_up))
    }

    /**
     * The attempt in flight can also end on its own error at the moment the user cancels. Its
     * report then arrives in Disconnected with a CONNECTION_ERROR -- which is not the reconnect
     * giving up, the user ended it, so no "Giving up." line.
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
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTION_LOST)
    }

    /**
     * A disconnect while the reconnect waits out its backoff ends the session for good, so it has
     * to give back what only Disconnected releases. The connection already reported its end when
     * it was lost and reports nothing a second time, so nothing on the disconnect-report path runs:
     * the wake lock stayed held and the connectivity receiver registered until the next session.
     */
    @Test
    fun aDisconnectWhileWaitingToReconnectReleasesTheWakeLockAndTheReceiver() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null) // waits for the network
        h.failConnection(0, connectionError())
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
        assertThat(connectivityReceivers()).isNotEmpty()

        h.service.disconnect()
        h.mainLooper.idle()

        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Disconnected())
        assertThat(h.service.isWakeLockHeldForTest()).isFalse()
        assertThat(connectivityReceivers()).isEmpty()
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.DISCONNECTED)
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

    /**
     * `cancelReconnect` on a live session is a no-op, both halves. Without the state machine's
     * answer it would release the wake lock and put the service into CONNECTION_LOST while the
     * connection is up (measured, G15).
     */
    @Test
    fun cancelReconnectDuringALiveSessionChangesNothing() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()

        h.service.cancelReconnect()
        h.mainLooper.idle()

        assertThat(h.service.getSessionState().value).isEqualTo(SessionState.Connected)
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTED)
        assertThat(h.service.isWakeLockHeldForTest()).isTrue()
    }

    @Test
    fun cancellingAReconnectThatNeverStartedIsHarmless() {
        val h = start()

        h.service.cancelReconnect()

        assertThat(h.service.isReconnecting()).isFalse()
        assertThat(connectivityReceivers()).isEmpty()
    }

    private fun whisperTarget(h: HumlaServiceHarness) =
        WhisperTargetChannel(h.service.getRootChannel(), false, false, null)

    /**
     * The thirty whisper slots are the session's, not the service's. Without the clear on the
     * disconnect path a long-lived service runs out of them after thirty whispers spread over any
     * number of connections, and `registerWhisperTarget` starts answering -1 for good (E10).
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
        // Reconnecting is still "reconnecting" to the UI, and it still knows why (G16, G18).
        assertThat(h.service.isReconnecting()).isTrue()
        assertThat(h.service.getConnectionError()).isNotNull()
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

    /**
     * `cancelReconnect` takes the receiver off by itself, with no broadcast to help it. The test
     * above delivers one, and the receiver's own first arm unregisters too -- two guards over one
     * observable, and the mutation that deletes the one in `releaseSessionResources` survived it
     * (measured, S29).
     */
    @Test
    fun cancellingWhileWaitingForTheNetworkUnregistersTheReceiver() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(connectivityReceivers()).hasSize(1)

        h.service.cancelReconnect()

        assertThat(connectivityReceivers()).isEmpty()
    }

    /**
     * And `onDestroy` takes it off on the one path where nothing else can: the connection is
     * already dead, so destroying the service produces no second disconnect report and
     * `releaseSessionResources` is never reached (measured, S30).
     */
    @Test
    fun destroyingTheServiceWhileWaitingForTheNetworkUnregistersTheReceiver() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        assertThat(connectivityReceivers()).hasSize(1)

        h.destroy()
        harnesses.remove(h)

        assertThat(connectivityReceivers()).isEmpty()
    }

    /**
     * The receiver's own first arm, on the one path that reaches it: a manual `connect()` while
     * the service is waiting for the network leaves the receiver registered -- `startSession` has
     * no business unregistering it -- so the next broadcast finds a session that is no longer
     * lost. Without the arm the receiver would ask the state machine to restore connectivity in a
     * state that has nothing to restore (measured, S26 and G12). The network deliberately stays
     * down, so the arm that has to fire is the state one and not `isOnline()`.
     */
    @Test
    fun aBroadcastAfterAManualConnectUnregistersTheReceiverInsteadOfRetrying() {
        val h = start(autoReconnect = true)
        h.connectAndSynchronize()
        shadowOf(connectivityManager()).setActiveNetworkInfo(null)
        h.failConnection(0, connectionError())
        val receiver = connectivityReceivers().single().broadcastReceiver

        h.service.connect() // Connecting, and the receiver is still registered
        h.mainLooper.idle()
        val connection = h.service.getConnection()
        receiver.onReceive(RuntimeEnvironment.getApplication(), Intent())
        h.mainLooper.idle()

        assertThat(connectivityReceivers()).isEmpty()
        assertThat(h.service.getConnection()).isSameInstanceAs(connection)
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
