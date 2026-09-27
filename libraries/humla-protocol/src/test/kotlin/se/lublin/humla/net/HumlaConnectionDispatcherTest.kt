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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import se.lublin.humla.model.ServerState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/**
 * [HumlaConnection] on the production dispatcher, with real threads: the socket is opened and
 * frames are handled on the one protocol context, one task at a time and in order however many
 * threads hand work over, and listener callbacks run on the callback executor.
 */
class HumlaConnectionDispatcherTest {
    private val protocol = Probe(Dispatchers.IO.limitedParallelism(1))
    private val callbackExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-callbacks") }
    private val callbackThread = AtomicReference<Thread>()
    private val transports = FakeTransports { protocol.isCurrent }
    private val listener = RecordingConnectionListener { Thread.currentThread() == callbackThread.get() }
    private val connection = HumlaConnection(
        ConnectionParams(ConnectionHarness.TEST_SERVER, forceTcp = true),
        listener,
        callbackExecutor,
        protocol,
        transports,
    )

    init {
        callbackExecutor.submit { callbackThread.set(Thread.currentThread()) }.get()
    }

    @After
    fun tearDown() {
        connection.disconnect()
        awaitUntil(description = "nothing of the connection left by this test") { connection.isTerminated }
        callbackExecutor.shutdown()
    }

    private fun establish(): FakeTcpTransport {
        connection.connect()
        awaitUntil(description = "tcp connect") { transports.tcps.singleOrNull()?.isConnectCalled == true }
        val tcp = transports.tcps.single()
        tcp.simulateConnected()
        awaitUntil(description = "onConnectionEstablished delivered") { listener.established.get() == 1 }
        return tcp
    }

    @Test
    fun theSocketIsOpenedOnTheProtocolContextAndCallbacksRunOnTheirExecutor() {
        val tcp = establish()

        assertThat(tcp.connectedInProtocolContext).isTrue()
        assertThat(listener.allOnCallbackThread).isTrue()
    }

    @Test
    fun anUnusedConnectionRunsNothing() {
        val unused = HumlaConnection(
            ConnectionParams(ConnectionHarness.TEST_SERVER), RecordingConnectionListener(), callbackExecutor, protocol,
        )

        unused.disconnect()

        assertThat(protocol.dispatched.get()).isEqualTo(0)
        assertThat(unused.isTerminated).isTrue()
    }

    /** Frames handed over by another thread are handled on the protocol context, one at a time, in order. */
    @Test
    fun protocolWorkRunsInOrderOneTaskAtATime() {
        val tcp = establish()
        val seen = CopyOnWriteArrayList<String>()
        val offContext = AtomicBoolean(false)
        connection.addTcpHandler {
            if (!protocol.isCurrent) offContext.set(true)
            seen += (it as Mumble.TextMessage).message
        }
        val texts = (0 until 500).map { "message $it" }

        thread(name = "fake-tcp-read") {
            texts.forEach { tcp.simulateMessage(HumlaTCPMessageType.TextMessage, textFrame(it)) }
        }
        repeat(4) { i -> thread(name = "poster $i") { repeat(100) { connection.post { Thread.yield() } } } }

        awaitUntil(description = "all messages handled") { seen.size == texts.size }
        assertThat(seen).containsExactlyElementsIn(texts).inOrder()
        assertThat(offContext.get()).isFalse()
        assertThat(protocol.maxConcurrent.get()).isEqualTo(1)
    }

    /**
     * The model is reduced and published on the protocol context: a burst of five thousand channel
     * states handed over by a reading thread reaches its readers as snapshots published there,
     * each behind the frames queued when the one before it was scheduled.
     */
    @Test
    fun aBurstOfChannelStatesIsPublishedAsSnapshotsOnTheProtocolContext() {
        val tcp = establish()
        val publishes = AtomicInteger()
        val latest = AtomicReference<ServerState?>()
        val publishedOffContext = AtomicBoolean(false)
        val publisher = object : ModelHandler.Publisher {
            override fun post(block: () -> Unit) = connection.post(block)

            override fun publish(state: ServerState) {
                if (!protocol.isCurrent) publishedOffContext.set(true)
                publishes.incrementAndGet()
                latest.set(state)
            }
        }
        connection.addTcpHandler(ModelHandler(ServerState.empty(), {}, publisher))
        val frames = (0 until 5_000).map { i ->
            Mumble.ChannelState.newBuilder().setChannelId(i).setName("channel $i")
                .apply { if (i > 0) parent = 0 }
                .build().toByteArray()
        }

        thread(name = "fake-tcp-read") { frames.forEach { tcp.simulateMessage(HumlaTCPMessageType.ChannelState, it) } }
        awaitUntil(timeoutMillis = 30_000, description = "the last snapshot has every channel") {
            latest.get()?.channels?.size == 5_000
        }

        assertThat(latest.get()!!.subchannelIds(0)).hasSize(4_999)
        assertThat(publishes.get()).isLessThan(5_000)
        assertThat(publishedOffContext.get()).isFalse()
    }

    /** Wraps [delegate], marking the threads that run its tasks and counting overlapping tasks. */
    private class Probe(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatched = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val running = AtomicInteger()
        private val current = ThreadLocal<Boolean>()

        val isCurrent: Boolean get() = current.get() == true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            delegate.dispatch(context) {
                maxConcurrent.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                current.set(true)
                try {
                    block.run()
                } finally {
                    current.remove()
                    running.decrementAndGet()
                }
            }
        }
    }
}
