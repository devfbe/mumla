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

package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.MessageLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.rules.ExternalResource
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.MumbleVersion
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A [HumlaConnection] over [FakeTransports] on the test thread: the protocol context is a
 * [StandardTestDispatcher] on virtual time and the listener's thread is a queue, both run by
 * [runCurrent], so every interleaving a test builds is deterministic. [clock] is the protocol's
 * clock, set by the test independently of the scheduler's time.
 */
@Suppress("LongParameterList") // Every setting a test varies.
internal class ConnectionHarness(
    forceTcp: Boolean = false,
    useTor: Boolean = false,
    server: Server = TEST_SERVER,
    resolver: ServerResolver = ServerResolver({ null }, Dispatchers.Unconfined),
    clientVersion: Long = MumbleVersion.CLIENT_V2,
    udpHealth: UdpHealthMonitor = UdpHealthMonitor(),
    udpRestartPolicy: ReconnectPolicy = ProtocolSession.UDP_RESTART_POLICY,
) {
    val scheduler = TestCoroutineScheduler()
    val transports = FakeTransports()
    val callbacks = QueueExecutor()
    val listener = RecordingConnectionListener { callbacks.isRunning }
    val clock = AtomicLong(0L)

    private val dispatcher = StandardTestDispatcher(scheduler)

    val connection = HumlaConnection(
        ConnectionParams(server, forceTcp = forceTcp, useTor = useTor),
        listener,
        callbacks,
        dispatcher,
        transports,
        resolver,
    ) { link, tunnel -> ProtocolSession(link, tunnel, clientVersion, udpHealth, udpRestartPolicy, clock::get) }

    val tcp: FakeTcpTransport get() = transports.tcps.single()

    /** Queues [block] on the protocol context directly, past the connection's scope and its cancellation. */
    fun onProtocolContext(block: () -> Unit) = dispatcher.dispatch(EmptyCoroutineContext, Runnable(block))

    /**
     * Runs [event] on the protocol context after [HumlaConnection.disconnect] was asked for but before
     * the teardown it queued, so both transports are still wired up.
     */
    fun inTheTeardownWindow(event: () -> Unit) {
        val tcp = tcp
        onProtocolContext(event)
        connection.disconnect()
        runCurrent()
        assertThat(tcp.disconnectCalls).isEqualTo(1)
    }

    /** Runs the protocol tasks and listener callbacks that are due, until neither has any left. */
    fun runCurrent() {
        do scheduler.runCurrent() while (callbacks.runAll())
    }

    /** Moves virtual time on by [millis], running everything that comes due. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun advanceBy(millis: Long) {
        scheduler.advanceTimeBy(millis)
        runCurrent()
    }

    fun atSeconds(seconds: Long) = clock.set(seconds * NANOS_PER_SECOND)

    /** Connects and reports the socket open; returns its transport. */
    fun establish(): FakeTcpTransport {
        connection.connect()
        runCurrent()
        tcp.simulateConnected()
        runCurrent()
        return tcp
    }

    fun receive(type: HumlaTCPMessageType, message: MessageLite) {
        tcp.simulateMessage(type, message.toByteArray())
        runCurrent()
    }

    fun synchronize(session: Int = 1, maxBandwidth: Int? = null) = receive(
        HumlaTCPMessageType.ServerSync,
        Mumble.ServerSync.newBuilder().setSession(session).apply { maxBandwidth?.let(::setMaxBandwidth) }.build(),
    )

    /** Disconnects and checks that nothing of the connection is left. */
    fun close() {
        connection.disconnect()
        runCurrent()
        assertThat(connection.isTerminated).isTrue()
    }

    companion object {
        val TEST_SERVER = Server(-1, "test", "127.0.0.1", 64738, "user", "")
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** The harnesses a test adds, each closed after it, which also checks that nothing is left. */
internal class ConnectionHarnesses : ExternalResource() {
    private val added = mutableListOf<ConnectionHarness>()

    fun add(harness: ConnectionHarness): ConnectionHarness = harness.also { added += it }

    override fun after() = added.forEach { it.close() }
}

/** A TCP frame as the wire carries it; [length] may lie about the payload. */
internal fun tcpFrame(type: Int, payload: ByteArray = ByteArray(0), length: Int = payload.size): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).apply {
        writeShort(type)
        writeInt(length)
        write(payload)
    }
    return bytes.toByteArray()
}

internal fun textFrame(text: String): ByteArray = Mumble.TextMessage.newBuilder().setMessage(text).build().toByteArray()

/** A datagram of [size] bytes whose leading type nibble marks it as Opus voice data. */
internal fun voiceDatagram(size: Int): ByteArray = ByteArray(size).also {
    it[0] = ((HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) and 0xFF).toByte()
}

/** A thread's task queue, run by the test: tasks wait until [runAll]. */
class QueueExecutor : Executor {
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    @Volatile
    var isRunning = false
        private set

    override fun execute(command: Runnable) {
        tasks += command
    }

    /** Runs the queued tasks, and those they queue, in order; false if there were none. */
    fun runAll(): Boolean {
        var ran = false
        while (true) {
            val task = tasks.poll() ?: return ran
            ran = true
            isRunning = true
            try {
                task.run()
            } finally {
                isRunning = false
            }
        }
    }
}
