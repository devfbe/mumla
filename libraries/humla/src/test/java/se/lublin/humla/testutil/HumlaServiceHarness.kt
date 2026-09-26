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

package se.lublin.humla.testutil

import android.os.Handler
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowLooper
import se.lublin.humla.HumlaService
import se.lublin.humla.model.Server
import se.lublin.humla.net.FakeTcpTransport
import se.lublin.humla.net.FakeTransports
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.net.ReconnectPolicy
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.session.HumlaEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Builds a [HumlaService] whose collaborators are fakes and drives it through a real
 * [HumlaConnection] over [FakeTransports]; the session state machine is never faked.
 *
 * Under Robolectric the test thread is the main thread with a paused looper, while the protocol
 * thread is real. Waits poll protocol-thread state and drain the main looper explicitly; polling
 * main-thread state would deadlock.
 */
class HumlaServiceHarness(
    private val autoReconnect: Boolean = false,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(
        baseDelayMillis = 10L,
        maxDelayMillis = 10L,
        maxAttempts = 3,
        maxJitterFraction = 0.0,
    ),
    server: Server? = Server(-1, "test", "127.0.0.1", 64738, "me", ""),
    /** null leaves the service to wrap the platform AudioManager, which is its own corner. */
    val devices: FakeCommunicationDevices? = FakeCommunicationDevices(),
) {
    val transports = FakeTransports()
    val audioFactory = FakeAudioFactory()
    val mainLooper: ShadowLooper = shadowOf(Looper.getMainLooper())
    val warnings = CopyOnWriteArrayList<String?>()

    val disconnects = CopyOnWriteArrayList<HumlaException?>()

    private val controller: ServiceController<HumlaService> =
        Robolectric.buildService(HumlaService::class.java)
    val service: HumlaService = controller.get()

    init {
        service.connectionFactory = { listener ->
            HumlaConnection(listener, transports, Handler(Looper.getMainLooper()))
        }
        service.reconnectPolicy = reconnectPolicy
        service.audioFactory = audioFactory
        service.communicationDevices = devices
        controller.create()
        service.onEvents { event ->
            when (event) {
                is HumlaEvent.LogMessage -> if (event.level == HumlaEvent.Level.WARNING) warnings += event.text
                is HumlaEvent.Disconnected -> disconnects += event.error
                else -> Unit
            }
        }
        service.configure(SessionConfig(server = server, clientName = "harness", autoReconnect = autoReconnect))
        mainLooper.idle()
    }

    fun destroy() {
        controller.destroy()
        mainLooper.idle()
        awaitUntil(description = "nothing of the connection left behind") {
            mainLooper.idle()
            service.getConnection()?.isTerminated != false
        }
    }

    /** Reconfigures the service the way MumlaService does, before or during a session. */
    fun configure(change: SessionConfig.() -> SessionConfig) {
        service.configure(service.sessionConfig.change())
        mainLooper.idle()
    }

    /** Waits until something has been posted to the main looper, then runs it. */
    fun drainMainWhenPosted() {
        awaitUntil(description = "a task on the main looper") { !mainLooper.isIdle }
        mainLooper.idle()
    }

    /** Opens the socket of connection number [index] (0-based) and reports it established. */
    fun openSocket(index: Int): FakeTcpTransport {
        awaitUntil(description = "tcp transport $index") {
            mainLooper.idle()
            transports.tcps.size > index && transports.tcps[index].connectThread != null
        }
        val tcp = transports.tcps[index]
        tcp.simulateConnected()
        // Waits for the handshake sent from onConnectionEstablished, not just isConnected:
        // `connected` is set before that callback is posted to the main looper.
        awaitUntil(description = "connection $index established") {
            mainLooper.idle()
            service.getConnection()?.isConnected == true &&
                tcp.sent.contains(HumlaTCPMessageType.Authenticate)
        }
        return tcp
    }

    /**
     * Feeds the root channel, the session user and ServerSync, i.e. exactly what a server sends
     * before the session is usable, and drains the main looper.
     */
    fun synchronize(tcp: FakeTcpTransport, session: Int = 1) {
        tcp.simulateMessage(
            HumlaTCPMessageType.ChannelState,
            Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build().toByteArray(),
        )
        tcp.simulateMessage(
            HumlaTCPMessageType.UserState,
            Mumble.UserState.newBuilder().setSession(session).setName("me").setChannelId(0).build().toByteArray(),
        )
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(session).setMaxBandwidth(72_000).build().toByteArray(),
        )
        awaitUntil(description = "server sync delivered") {
            mainLooper.idle()
            service.connectionState == HumlaService.ConnectionState.CONNECTED
        }
    }

    /**
     * Feeds the same three frames as [synchronize] but waits only for the *protocol* thread to
     * have parsed them, leaving `onConnectionSynchronized` queued on the main looper. That is the
     * window in which a disconnect can beat the callback.
     */
    fun synchronizeWithoutDraining(tcp: FakeTcpTransport, session: Int = 1) {
        tcp.simulateMessage(
            HumlaTCPMessageType.ChannelState,
            Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build().toByteArray(),
        )
        tcp.simulateMessage(
            HumlaTCPMessageType.UserState,
            Mumble.UserState.newBuilder().setSession(session).setName("me").setChannelId(0).build().toByteArray(),
        )
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(session).setMaxBandwidth(72_000).build().toByteArray(),
        )
        awaitUntil(description = "server sync parsed") {
            service.getConnection()?.isSynchronized == true
        }
    }

    fun connectAndSynchronize(index: Int = 0): FakeTcpTransport {
        if (index == 0) service.connect()
        val tcp = openSocket(index)
        synchronize(tcp)
        return tcp
    }

    /** Fails connection [index] the way a dropped socket does, including the late close report. */
    fun failConnection(index: Int, error: HumlaException) {
        val connection = service.getConnection()
        transports.tcps[index].simulateFailure(error)
        awaitUntil(description = "connection $index torn down") {
            mainLooper.idle()
            connection?.isConnected != true
        }
        transports.tcps[index].simulateSocketClosed()
        awaitUntil(description = "disconnect report delivered") {
            mainLooper.idle()
            service.connectionState != HumlaService.ConnectionState.CONNECTED
        }
    }
}
