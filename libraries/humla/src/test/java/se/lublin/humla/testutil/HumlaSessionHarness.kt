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

import android.app.Application
import android.os.Handler
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import se.lublin.humla.HumlaSession
import se.lublin.humla.IHumlaSession
import se.lublin.humla.audio.AudioSettings
import se.lublin.humla.audio.PipelineSettings
import se.lublin.humla.audio.routing.CommunicationDevices
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Server
import se.lublin.humla.net.ConnectionParams
import se.lublin.humla.net.FakeTcpTransport
import se.lublin.humla.net.FakeTransports
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.ReconnectPolicy
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.ConnectionConfig
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Builds a [HumlaSession] whose collaborators are fakes and drives it through a real
 * [HumlaConnection] over [FakeTransports]; the session state machine is never faked. The network
 * and the wake lock are the platform's, as Robolectric shadows them.
 *
 * Under Robolectric the test thread is the main thread with a paused looper, while the protocol
 * context runs on the IO pool for real. Waits poll protocol-side state and drain the main looper
 * explicitly; polling main-thread state would deadlock.
 */
internal class HumlaSessionHarness(
    autoReconnect: Boolean = false,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(
        baseDelayMillis = 10L,
        maxDelayMillis = 10L,
        maxAttempts = 3,
        maxJitterFraction = 0.0,
    ),
    server: Server? = Server(-1, "test", "127.0.0.1", 64738, "me", ""),
    /** null leaves the session to wrap the platform AudioManager, which is its own corner. */
    val devices: FakeCommunicationDevices? = FakeCommunicationDevices(),
) {
    val app: Application = RuntimeEnvironment.getApplication()
    val transports = FakeTransports()
    val audioFactory = FakeAudioFactory()
    val mainLooper: ShadowLooper = shadowOf(Looper.getMainLooper())
    val warnings = CopyOnWriteArrayList<String?>()

    val session: HumlaSession = testSession(
        SessionConfig(ConnectionConfig(server = server, clientName = "harness"), autoReconnect = autoReconnect),
        devices,
        reconnectPolicy,
        connectionFactory = { params, listener ->
            HumlaConnection(params, listener, Handler(Looper.getMainLooper())::post, transports = transports)
        },
        audioFactory = audioFactory,
    )

    init {
        session.onEvents { event ->
            if (event is HumlaEvent.LogMessage && event.level == HumlaEvent.Level.WARNING) warnings += event.text
        }
        mainLooper.idle()
    }

    fun string(id: Int): String = app.getString(id)

    fun close() {
        session.close()
        mainLooper.idle()
        awaitUntil(description = "nothing of the connection left behind") {
            mainLooper.idle()
            session.connection?.isTerminated != false
        }
    }

    /** Reconfigures the session the way the app does, before or during a connection. */
    fun configure(change: SessionConfig.() -> SessionConfig) {
        session.configure(session.config.change())
        mainLooper.idle()
    }

    fun configureAudio(change: AudioSettings.() -> AudioSettings) = configure { copy(audio = audio.change()) }

    fun configurePipeline(change: PipelineSettings.() -> PipelineSettings) =
        configureAudio { copy(pipeline = pipeline.change()) }

    /** Opens the socket of connection number [index] (0-based) and reports it established. */
    fun openSocket(index: Int): FakeTcpTransport {
        awaitUntil(description = "tcp transport $index") {
            mainLooper.idle()
            transports.tcps.size > index && transports.tcps[index].isConnectCalled
        }
        val tcp = transports.tcps[index]
        tcp.simulateConnected()
        // Waits for the handshake sent from onConnectionEstablished, not just isConnected:
        // `connected` is set before that callback is posted to the main looper.
        awaitUntil(description = "connection $index established") {
            mainLooper.idle()
            session.connection?.isConnected == true &&
                tcp.sent.contains(HumlaTCPMessageType.Authenticate)
        }
        return tcp
    }

    /**
     * Feeds the root channel, the session user and ServerSync, i.e. exactly what a server sends
     * before the session is usable, and drains the main looper.
     */
    fun synchronize(tcp: FakeTcpTransport, session: Int = 1) {
        feedSync(tcp, session)
        awaitUntil(description = "server sync delivered") {
            mainLooper.idle()
            this.session.state.value == SessionState.Connected
        }
    }

    /**
     * Feeds the same three frames as [synchronize] but waits only for the *protocol* thread to
     * have parsed them, leaving `onConnectionSynchronized` queued on the main looper. That is the
     * window in which a disconnect can beat the callback.
     */
    fun synchronizeWithoutDraining(tcp: FakeTcpTransport, session: Int = 1) {
        feedSync(tcp, session)
        awaitUntil(description = "server sync parsed") {
            this.session.connection?.isSynchronized == true
        }
    }

    private fun feedSync(tcp: FakeTcpTransport, session: Int) {
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
    }

    fun connectAndSynchronize(index: Int = 0): FakeTcpTransport {
        if (index == 0) session.connect()
        val tcp = openSocket(index)
        synchronize(tcp)
        return tcp
    }

    /** Fails connection [index] the way a dropped socket does, including the late close report. */
    fun failConnection(index: Int, error: HumlaException) {
        val connection = session.connection
        transports.tcps[index].simulateFailure(error)
        awaitUntil(description = "connection $index torn down") {
            mainLooper.idle()
            connection?.isConnected != true
        }
        transports.tcps[index].simulateSocketClosed()
        awaitUntil(description = "disconnect report delivered") {
            mainLooper.idle()
            session.state.value != SessionState.Connected
        }
    }
}

/** A session on Robolectric's platform with fakes where a test needs them. */
internal fun testSession(
    config: SessionConfig = SessionConfig(),
    devices: CommunicationDevices? = FakeCommunicationDevices(),
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    connectionFactory: ((ConnectionParams, HumlaConnection.Listener) -> HumlaConnection)? = null,
    audioFactory: FakeAudioFactory = FakeAudioFactory(),
): HumlaSession {
    val main = Handler(Looper.getMainLooper())
    val app = RuntimeEnvironment.getApplication()
    return if (connectionFactory == null) {
        HumlaSession(
            app, config, main,
            communicationDevices = devices, audioFactory = audioFactory, reconnectPolicy = reconnectPolicy,
        )
    } else {
        HumlaSession(
            app, config, main,
            communicationDevices = devices, connectionFactory = connectionFactory,
            audioFactory = audioFactory, reconnectPolicy = reconnectPolicy,
        )
    }
}

/** Why the session is not connected, in whichever state carries it. */
internal val IHumlaSession.reason: DisconnectReason?
    get() = when (val state = state.value) {
        is SessionState.Disconnected -> state.reason
        is SessionState.ConnectionLost -> state.reason
        is SessionState.Reconnecting -> state.reason
        SessionState.Connecting, SessionState.Connected -> null
    }

internal val IHumlaSession.isReconnecting: Boolean
    get() = state.value is SessionState.ConnectionLost || state.value is SessionState.Reconnecting

/** Stores the Bluetooth wish the way the app's preference does: as part of the audio settings. */
internal fun IHumlaSession.setBluetoothAutomatic(on: Boolean) {
    configure(config.copy(audio = config.audio.copy(bluetoothAutomatic = on)))
}
