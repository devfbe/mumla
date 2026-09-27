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

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import se.lublin.humla.HumlaSession
import se.lublin.humla.IHumlaSession
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.net.CryptState
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaSSLSocketFactory
import se.lublin.humla.net.HumlaTCP
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.net.HumlaUDP
import se.lublin.humla.net.ReconnectPolicy
import se.lublin.humla.net.TcpTransport
import se.lublin.humla.net.UdpTransport
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.SessionConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opens [HumlaSession]s whose connections reach no network: every attempt gets a transport that
 * never opens a socket, and the test plays the server through [synchronizeLatest] and
 * [loseLatest]. A connection runs inline on the calling thread; its callbacks reach the session
 * through the main looper, as a real connection's do. The audio pipeline is a fake.
 */
public class ScriptedConnections(reconnectBaseDelayMillis: Long, reconnectAttempts: Int) {
    private val policy = ReconnectPolicy(
        baseDelayMillis = reconnectBaseDelayMillis,
        maxAttempts = reconnectAttempts,
        maxJitterFraction = 0.0,
    )
    private val transports = CopyOnWriteArrayList<ScriptedTcp>()

    /** Connection attempts so far. */
    public val attempts: Int get() = transports.size

    /** How often the transport of attempt [index] (0-based) was closed. */
    public fun disconnectCalls(index: Int): Int = transports[index].disconnectCalls

    public fun session(context: Context, config: SessionConfig): IHumlaSession {
        val main = Handler(Looper.getMainLooper())
        return HumlaSession(
            context,
            config,
            main,
            communicationDevices = FakeCommunicationDevices(),
            connectionFactory = { params, listener ->
                HumlaConnection(params, listener, main::post, Dispatchers.Unconfined, Transports())
            },
            audioFactory = FakeAudioFactory(),
            reconnectPolicy = policy,
        )
    }

    /** The server of the latest attempt accepts it: TLS, the own user, ServerSync. */
    public fun synchronizeLatest(session: Int = 1) {
        val tcp = transports.last()
        tcp.listener.onTCPConnectionEstablished()
        tcp.receive(HumlaTCPMessageType.ChannelState, Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root"))
        tcp.receive(
            HumlaTCPMessageType.UserState,
            Mumble.UserState.newBuilder().setSession(session).setName("me").setChannelId(0),
        )
        tcp.receive(HumlaTCPMessageType.ServerSync, Mumble.ServerSync.newBuilder().setSession(session))
    }

    /** The latest attempt's socket fails with [message], as a dropped network does. */
    public fun loseLatest(message: String) {
        transports.last().listener.onTCPConnectionFailed(
            HumlaException(message, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR),
        )
    }

    private inner class Transports : HumlaConnection.TransportFactory {
        override fun createTcp(socketFactory: HumlaSSLSocketFactory, scope: CoroutineScope): TcpTransport =
            ScriptedTcp().also { transports += it }

        override fun createUdp(
            cryptState: CryptState,
            listener: HumlaUDP.UDPConnectionListener,
            scope: CoroutineScope,
        ): UdpTransport = SilentUdp()
    }

    private class SilentUdp : UdpTransport {
        override val isRunning: Boolean = false
        override fun connect(host: String, port: Int) = Unit
        override fun sendMessage(data: ByteArray, length: Int) = Unit
        override fun disconnect() = Unit
    }

    private class ScriptedTcp : TcpTransport {
        lateinit var listener: HumlaTCP.TCPConnectionListener
        @Volatile var disconnectCalls = 0

        override val isRunning: Boolean get() = disconnectCalls == 0

        override fun setTCPConnectionListener(listener: HumlaTCP.TCPConnectionListener?) {
            this.listener = checkNotNull(listener)
        }

        override fun connect(host: String, port: Int, useTor: Boolean) = Unit
        override fun sendMessage(message: MessageLite, messageType: HumlaTCPMessageType) = Unit
        override fun sendMessage(data: ByteArray, length: Int, messageType: HumlaTCPMessageType) = Unit

        override fun disconnect() {
            disconnectCalls++
        }

        fun receive(type: HumlaTCPMessageType, message: MessageLite.Builder) {
            val bytes = message.build().toByteArray()
            listener.onTCPMessageReceived(type, bytes.size, bytes)
        }
    }
}
