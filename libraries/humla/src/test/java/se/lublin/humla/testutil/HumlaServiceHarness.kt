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

import android.os.Bundle
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
import se.lublin.humla.session.ReconnectPolicy
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Builds a [HumlaService] whose collaborators are fakes and drives it through a **real**
 * [HumlaConnection] over [FakeTransports], so the tests exercise the service's own wiring rather
 * than a mock of it. The one thing that is never faked is the state machine: it is the subject.
 *
 * Under Robolectric the test thread *is* the main thread and the main looper is paused, while the
 * protocol thread is a real one. Every wait here therefore polls state owned by the protocol
 * thread and drains the main looper explicitly; a poll on main-thread state would deadlock.
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
) {
    val transports = FakeTransports()
    val mainLooper: ShadowLooper = shadowOf(Looper.getMainLooper())
    val warnings = CopyOnWriteArrayList<String?>()

    /** One entry per handshake: the CELT versions the service announced. */
    val celtAnnouncements = CopyOnWriteArrayList<IntArray>()
    val disconnects = CopyOnWriteArrayList<HumlaException?>()

    private val controller: ServiceController<HumlaService> =
        Robolectric.buildService(HumlaService::class.java)
    val service: HumlaService = controller.get()

    init {
        service.connectionFactory = { listener ->
            HumlaConnection(listener, transports, Handler(Looper.getMainLooper()))
        }
        service.reconnectPolicy = reconnectPolicy
        service.celtVersions = {
            // The native library is not on the JVM; see HumlaService.celtVersions.
            intArrayOf(0x8000000b.toInt()).also { celtAnnouncements += it }
        }
        controller.create()
        service.registerObserver(object : HumlaObserver() {
            override fun onLogWarning(message: String?) { warnings += message }
            override fun onDisconnected(e: HumlaException?) { disconnects += e }
        })
        service.configureExtras(
            Bundle().apply {
                if (server != null) putParcelable(HumlaService.EXTRAS_SERVER, server)
                putBoolean(HumlaService.EXTRAS_AUTO_RECONNECT, autoReconnect)
                // Both are pre-existing preconditions of onConnectionEstablished, not conveniences:
                // Version.setRelease(null) and Authenticate.addAllTokens(null) each throw, so a
                // connection without them never gets past the handshake. ServerConnectTask always
                // writes them; this harness has to as well to reach the states it is about.
                putString(HumlaService.EXTRAS_CLIENT_NAME, "harness")
                putStringArrayList(HumlaService.EXTRAS_ACCESS_TOKENS, arrayListOf())
            },
        )
        mainLooper.idle()
    }

    fun destroy() {
        controller.destroy()
        mainLooper.idle()
        awaitUntil(description = "no protocol thread left behind") {
            mainLooper.idle()
            service.getConnection()?.protocolThread?.isAlive != true
        }
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
        awaitUntil(description = "connection $index established") {
            mainLooper.idle()
            service.getConnection()?.isConnected == true
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
            service.getConnectionState() == HumlaService.ConnectionState.CONNECTED
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
            service.getConnectionState() != HumlaService.ConnectionState.CONNECTED
        }
    }
}
