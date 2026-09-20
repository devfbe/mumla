package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.exception.NotSynchronizedException
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.HumlaCallbacks
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.HumlaObserver
import java.lang.reflect.Method
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Covers spec A1 for [HumlaConnection]: the socket is opened, frames are parsed and handlers are
 * dispatched on the "humla-protocol" thread, listener callbacks arrive on the main looper, and the
 * protocol thread's own lifecycle cannot swallow a disconnect report.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaConnectionProtocolThreadTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val transports = FakeTransports()
    private val listener = RecordingConnectionListener()
    private val connection = HumlaConnection(listener, transports)
    private val server = Server(-1, "test", "127.0.0.1", 64738, "user", "")

    /**
     * Threads that were already running when this test started. Thread.getAllStackTraces() is
     * JVM-global, so a protocol thread another test leaked would otherwise fail this one as pure
     * collateral damage; [tearDown] pins a leak on the test that caused it instead.
     */
    private val preexistingThreads = Thread.getAllStackTraces().keys.toSet()

    private fun liveProtocolThreads(): List<Thread> =
        Thread.getAllStackTraces().keys
            .filter { it !in preexistingThreads && it.isAlive && it.name == PROTOCOL_THREAD }

    @After
    fun tearDown() {
        connection.disconnect()
        mainLooper.idle()
        awaitUntil(description = "no live $PROTOCOL_THREAD thread left by this test") {
            liveProtocolThreads().isEmpty()
        }
    }

    /**
     * Waits for something the main looper still has to deliver, idling it as part of the wait.
     * Idling once before the wait is not the same thing: every one of these callbacks is posted
     * *after* the protocol thread has already set the flag a test could otherwise poll, so a wait
     * on the flag races the callback it is supposed to be waiting for.
     */
    private fun awaitOnMain(description: String, condition: () -> Boolean) =
        awaitUntil(description = description) { mainLooper.idle(); condition() }

    private fun connectAndEstablish(forceTcp: Boolean = true): FakeTcpTransport {
        connection.setForceTCP(forceTcp)
        connection.connect(server)
        awaitUntil(description = "tcp connect") { transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null }
        val tcp = transports.tcps[0]
        tcp.simulateConnected()
        awaitOnMain("onConnectionEstablished delivered") { listener.established.get() == 1 }
        return tcp
    }

    private fun synchronize(tcp: FakeTcpTransport, session: Int = 7) {
        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(session).setMaxBandwidth(72_000).build().toByteArray()
        )
        awaitOnMain("onConnectionSynchronized delivered") { listener.synchronizedCount.get() == 1 }
    }

    /**
     * Runs [event] on the protocol thread in the window production actually hits: [disconnect] has
     * been asked for and the teardown it posted is queued, but has not run yet - so both transports
     * are still wired up and a callback that is not guarded really does reach them.
     *
     * The gate is what makes that interleaving a fact rather than a hope. Without it the event and
     * the teardown race, and every assertion below would also hold for the run in which the
     * teardown won, which is the run that proves nothing.
     */
    private fun inTheTeardownWindow(tcp: FakeTcpTransport, event: () -> Unit) {
        val gate = CountDownLatch(1)
        connection.protocolHandler.post { gate.await() }
        connection.protocolHandler.post { event() }
        connection.disconnect()
        gate.countDown()
        awaitUntil(description = "teardown ran behind the queued callback") { tcp.disconnectCalls == 1 }
        mainLooper.idle()
    }

    @Test
    fun socketIsOpenedOnTheProtocolThreadAndCallbacksArriveOnMain() {
        val tcp = connectAndEstablish()

        assertThat(tcp.connectThread).isEqualTo(PROTOCOL_THREAD)
        assertThat(tcp.connectHost).isEqualTo("127.0.0.1")
        assertThat(tcp.connectPort).isEqualTo(64738)
        assertThat(listener.established.get()).isEqualTo(1)
        assertThat(listener.callbackLoopers).containsExactly(Looper.getMainLooper())
    }

    @Test
    fun messagesAreParsedAndDispatchedOnTheProtocolThread() {
        val tcp = connectAndEstablish()
        val handlerThreads = CopyOnWriteArrayList<String>()
        connection.addTCPMessageHandlers(object : HumlaTCPMessageListener.Stub() {
            override fun messageVersion(msg: Mumble.Version) { handlerThreads += Thread.currentThread().name }
        })

        tcp.simulateMessage(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray())

        awaitUntil { handlerThreads.isNotEmpty() }
        assertThat(handlerThreads).containsExactly(PROTOCOL_THREAD)
        // The parsed version is only readable once the connection is synchronized.
        assertThrows(NotSynchronizedException::class.java) { connection.getServerRelease() }
    }

    @Test
    fun fiveThousandChannelStatesDoNotStallAMainLooperTaskBeyond16ms() {
        val tcp = connectAndEstablish()
        val callbacks = HumlaCallbacks()
        val added = AtomicInteger()
        val addedOnMain = AtomicBoolean(true)
        callbacks.registerObserver(object : HumlaObserver() {
            override fun onChannelAdded(channel: IChannel) {
                added.incrementAndGet()
                if (Looper.myLooper() != Looper.getMainLooper()) addedOnMain.set(false)
            }
        })
        val silentLogger = object : HumlaLogger {
            override fun logInfo(message: String) {}
            override fun logWarning(message: String) {}
            override fun logError(message: String) {}
        }
        connection.addTCPMessageHandlers(ModelHandler(RuntimeEnvironment.getApplication(), callbacks, silentLogger, null, null))
        val processed = AtomicInteger()
        connection.addTCPMessageHandlers(object : HumlaTCPMessageListener.Stub() {
            override fun messageChannelState(msg: Mumble.ChannelState) { processed.incrementAndGet() }
        })
        val frames = (0 until 5_000).map { i ->
            Mumble.ChannelState.newBuilder().setChannelId(i).setName("channel $i").apply { if (i > 0) parent = 0 }.build().toByteArray()
        }

        thread(name = "fake-tcp-read") { frames.forEach { tcp.simulateMessage(HumlaTCPMessageType.ChannelState, it) } }
        awaitUntil(timeoutMillis = 30_000, description = "protocol thread processed all frames") { processed.get() == 5_000 }

        val probeRan = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { probeRan.set(true) }
        var tasksBeforeProbe = 0
        var eventsBeforeProbe = 0
        while (!probeRan.get()) {
            val before = added.get()
            mainLooper.runOneTask()
            if (!probeRan.get()) {
                tasksBeforeProbe++
                eventsBeforeProbe = maxOf(eventsBeforeProbe, added.get() - before)
            }
        }

        // Spec A1's budget is 16 ms of main-thread work. What production actually bounds is the
        // slice: at most MAX_EVENTS_PER_SLICE events (or SLICE_BUDGET_NANOS of self-measured work)
        // per main-looper task, which is what these two assertions pin down. A wall-clock
        // assertion here would fail on a GC pause or a loaded CI runner for reasons unrelated to
        // the code under test.
        assertThat(tasksBeforeProbe).isAtMost(1)
        assertThat(eventsBeforeProbe).isAtMost(HumlaCallbacks.MAX_EVENTS_PER_SLICE)
        mainLooper.idle()
        assertThat(added.get()).isEqualTo(5_000)
        assertThat(addedOnMain.get()).isTrue()
    }

    @Test
    fun userDisconnectIsReportedExactlyOnceEvenWhenTheSocketCloseIsReportedLater() {
        val tcp = connectAndEstablish()

        connection.disconnect()
        awaitUntil { tcp.disconnectCalls == 1 }
        tcp.simulateSocketClosed() // what the real read loop does once the socket is closed
        awaitOnMain("disconnect delivered") { listener.disconnects.isNotEmpty() }

        assertThat(listener.disconnects).containsExactly(null)
        assertThat(connection.isConnected).isFalse()
    }

    /**
     * The transport reports the disconnect from [TcpTransport.disconnect] itself, i.e. from inside
     * the teardown this connection posts to its own protocol looper. If the looper is quit before
     * that teardown has run, the post is refused; the real transport then hands its token back and
     * the read loop is the only route left - and it has none at all when it is stuck in a connect
     * without a timeout. A disconnect nobody reports is a session that never reconnects.
     */
    @Test
    fun theProtocolLooperStillAcceptsWorkWhileTheConnectionTearsItselfDown() {
        val tcp = connectAndEstablish()

        connection.disconnect()
        awaitUntil { tcp.disconnectCalls == 1 }

        assertThat(tcp.terminalPostAccepted.get()).isTrue()
    }

    @Test
    fun serverRejectEndsTheConnectionOnceWithTheRejectReason() {
        val tcp = connectAndEstablish()
        val reject = Mumble.Reject.newBuilder().setType(Mumble.Reject.RejectType.WrongServerPW).setReason("wrong password").build()

        tcp.simulateMessage(HumlaTCPMessageType.Reject, reject.toByteArray())
        awaitUntil { tcp.disconnectCalls == 1 }
        tcp.simulateSocketClosed()
        awaitOnMain("disconnect delivered") { listener.disconnects.isNotEmpty() }

        assertThat(listener.disconnects).hasSize(1)
        val error = listener.disconnects[0]!!
        assertThat(error.reason).isEqualTo(HumlaException.HumlaDisconnectReason.REJECT)
        assertThat(error.message).isEqualTo("Rejected: wrong password")
        assertThat(connection.error).isSameInstanceAs(error)
    }

    @Test
    fun transportFailureIsReportedOnceAsConnectionError() {
        val tcp = connectAndEstablish()
        val failure = HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

        tcp.simulateFailure(failure)
        awaitUntil { tcp.disconnectCalls == 1 }
        tcp.simulateSocketClosed()
        awaitOnMain("disconnect delivered") { listener.disconnects.isNotEmpty() }

        assertThat(listener.disconnects).containsExactly(failure)
    }

    @Test
    fun handshakeFailureIsReportedBeforeTheDisconnectThatFollowsIt() {
        val tcp = connectAndEstablish()

        tcp.simulateHandshakeFailure(emptyArray())
        awaitUntil { tcp.disconnectCalls == 1 }
        awaitOnMain("handshake failure and disconnect delivered") {
            listener.handshakeFailures.isNotEmpty() && listener.disconnects.isNotEmpty()
        }

        assertThat(listener.handshakeFailures).hasSize(1)
        assertThat(listener.disconnects).containsExactly(null)
        assertThat(listener.allOnMainLooper).isTrue()
    }

    /**
     * A frame that completed while the connection was being torn down must not reach the message
     * handlers: by the time the disconnect is delivered the consumer has shut its audio path down,
     * and a straggler packet would be decoded into it.
     */
    @Test
    fun framesArrivingBehindADisconnectAreNotDispatched() {
        val tcp = connectAndEstablish()
        val seen = AtomicInteger()
        connection.addTCPMessageHandlers(object : HumlaTCPMessageListener.Stub() {
            override fun messageVersion(msg: Mumble.Version) { seen.incrementAndGet() }
        })
        // Park the protocol thread so the frame is provably queued ahead of the teardown, which is
        // the interleaving production hits: the read thread keeps delivering while the main thread
        // has already asked for the disconnect and started shutting the audio path down.
        val gate = CountDownLatch(1)
        connection.protocolHandler.post { gate.await() }
        tcp.simulateMessage(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray())

        connection.disconnect()
        gate.countDown()
        awaitUntil(description = "teardown ran behind the queued frame") { tcp.disconnectCalls == 1 }

        assertThat(seen.get()).isEqualTo(0)
    }

    /** One bad message must not take the protocol thread - and with it the session - down. */
    @Test
    fun aThrowingHandlerDoesNotKillTheProtocolThread() {
        val tcp = connectAndEstablish()
        val survivors = AtomicInteger()
        connection.addTCPMessageHandlers(object : HumlaTCPMessageListener.Stub() {
            override fun messageVersion(msg: Mumble.Version) { throw IllegalStateException("handler is broken") }
        })
        connection.addTCPMessageHandlers(object : HumlaTCPMessageListener.Stub() {
            override fun messageTextMessage(msg: Mumble.TextMessage) { survivors.incrementAndGet() }
        })

        tcp.simulateMessage(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray())
        tcp.simulateMessage(HumlaTCPMessageType.TextMessage, Mumble.TextMessage.newBuilder().setMessage("still here").build().toByteArray())

        awaitUntil(description = "the protocol thread kept working after a handler threw") { survivors.get() == 1 }
        mainLooper.idle()
        assertThat(listener.disconnects).isEmpty()
    }

    @Test
    fun serverSyncStartsThePingTimerOnTheProtocolThread() {
        val tcp = connectAndEstablish()

        synchronize(tcp, session = 42)

        assertThat(listener.synchronizedCount.get()).isEqualTo(1)
        assertThat(connection.getSession()).isEqualTo(42)
        assertThat(connection.getMaxBandwidth()).isEqualTo(72_000)
        awaitUntil(description = "ping sent") { tcp.sent.contains(HumlaTCPMessageType.Ping) }
    }

    @Test
    fun theUdpTransportIsStartedOnTheProtocolThreadAndFailsIntoAWarning() {
        val tcp = connectAndEstablish(forceTcp = false)
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() }
        val udp = transports.udps[0]

        assertThat(udp.connectThread).isEqualTo(PROTOCOL_THREAD)
        assertThat(udp.connectCalls.get()).isEqualTo(1)

        udp.simulateError(java.io.IOException("socket closed"))
        awaitOnMain("warning delivered") { listener.warnings.isNotEmpty() }

        assertThat(listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)
        assertThat(listener.allOnMainLooper).isTrue()
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel)
    }

    @Test
    fun connectingAgainAfterADisconnectThrowsInsteadOfSilentlyDoingNothing() {
        connectAndEstablish()
        connection.disconnect()
        mainLooper.idle()

        // The protocol looper is gone; without the guard connect() would post into the void and
        // the caller would wait for a callback that never comes.
        val thrown = assertThrows(IllegalStateException::class.java) { connection.connect(server) }
        assertThat(thrown).hasMessageThat().contains("single-use")
        assertThat(transports.tcps).hasSize(1)
    }

    /**
     * The other half of the single-use guard, and the only one a test can reach: a disconnect that
     * arrives before the connection was ever started must still refuse a later connect(). Without
     * it connect() would queue its work on a looper that is already quitting and the caller would
     * wait in Connecting for a callback that never comes.
     */
    @Test
    fun connectingAfterADisconnectThatPrecededItThrows() {
        connection.disconnect()

        val thrown = assertThrows(IllegalStateException::class.java) { connection.connect(server) }

        assertThat(thrown).hasMessageThat().contains("single-use")
        assertThat(transports.tcps).isEmpty()
        assertThat(listener.disconnects).isEmpty() // nothing was started, so there is nothing to report
    }

    @Test
    fun anUnusedConnectionStartsNoProtocolThread() {
        val unused = HumlaConnection(RecordingConnectionListener(), FakeTransports())
        assertThat(liveProtocolThreads()).isEmpty()

        unused.disconnect() // must not start a thread either
        assertThat(liveProtocolThreads()).isEmpty()
    }

    @Test
    fun aConnectionThatIsDisconnectedLeavesNoProtocolThreadBehind() {
        connectAndEstablish()
        assertThat(liveProtocolThreads()).hasSize(1)

        connection.disconnect()

        awaitUntil(description = "protocol thread quit") { liveProtocolThreads().isEmpty() }
    }

    /**
     * [HumlaConnection.onTCPConnectionEstablished] behind a disconnect. The observable is the UDP
     * transport, not the listener: the terminal gate drops a late onConnectionEstablished whether
     * or not this guard is there, so an assertion on the listener would stay green without it.
     */
    @Test
    fun anEstablishedCallbackBehindADisconnectStartsNoSecondUdpTransport() {
        val tcp = connectAndEstablish(forceTcp = false)
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() }

        inTheTeardownWindow(tcp) { connection.onTCPConnectionEstablished() }

        assertThat(transports.udps).hasSize(1)
        assertThat(connection.isConnected).isFalse()
    }

    /** [HumlaConnection.onUDPDataReceived] behind a disconnect: the audio path is already down. */
    @Test
    fun aDatagramArrivingBehindADisconnectIsNotDispatched() {
        val tcp = connectAndEstablish()
        val seen = AtomicInteger()
        connection.addUDPMessageHandlers(object : HumlaUDPMessageListener.Stub() {
            override fun messageVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
                seen.incrementAndGet()
            }
        })

        inTheTeardownWindow(tcp) { connection.onUDPDataReceived(voiceDatagram) }

        assertThat(seen.get()).isEqualTo(0)
    }

    /**
     * [HumlaConnection.resyncCryptState] behind a disconnect. It sent through the transport
     * directly, which bypassed the connected check every other send goes through, so it was the one
     * callback that could still put bytes on a socket the user had already closed.
     */
    @Test
    fun aCryptResyncBehindADisconnectPutsNothingOnTheWire() {
        val tcp = connectAndEstablish()
        val sentBefore = tcp.sent.toList()

        inTheTeardownWindow(tcp) { connection.resyncCryptState() }

        assertThat(tcp.sent).containsExactlyElementsIn(sentBefore).inOrder()
    }

    /**
     * [HumlaConnection.onTCPConnectionFailed] behind a disconnect: it recorded the failure as this
     * connection's error after the disconnect had already been reported as clean, so the consumer
     * saw a null reason and a non-null [HumlaConnection.error] for the same connection.
     */
    @Test
    fun aTransportFailureBehindADisconnectDoesNotBecomeTheConnectionsError() {
        val tcp = connectAndEstablish()

        inTheTeardownWindow(tcp) {
            connection.onTCPConnectionFailed(
                HumlaException("late failure", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
            )
        }

        assertThat(connection.error).isNull()
        assertThat(listener.disconnects).containsExactly(null)
    }

    /**
     * The structural half of the guard set, and the reason it is written with reflection rather
     * than as a ninth hand-written case: every method of both transport listener interfaces has to
     * be inert in the teardown window, so a callback a later task adds inherits the requirement
     * instead of becoming the next mutation nobody thought to try. Adding a callback fails this
     * test until someone has decided what it does behind a disconnect.
     */
    @Test
    fun noTransportCallbackDoesAnyWorkBehindADisconnect() {
        val tcp = connectAndEstablish(forceTcp = false)
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() }
        val frames = AtomicInteger()
        connection.addTCPMessageHandlers(object : HumlaTCPMessageListener.Stub() {
            override fun messageVersion(msg: Mumble.Version) { frames.incrementAndGet() }
        })
        val datagrams = AtomicInteger()
        connection.addUDPMessageHandlers(object : HumlaUDPMessageListener.Stub() {
            override fun messageVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
                datagrams.incrementAndGet()
            }
        })
        val sentBefore = tcp.sent.toList()
        val invoked = CopyOnWriteArrayList<String>()

        inTheTeardownWindow(tcp) { invoked += invokeEveryTransportCallback() }

        assertThat(invoked).containsExactly(
            "onTCPConnectionDisconnect", "onTCPConnectionEstablished", "onTCPConnectionFailed",
            "onTCPMessageReceived", "onTLSHandshakeFailed",
            "onUDPConnectionError", "onUDPDataReceived", "resyncCryptState",
        )
        assertThat(frames.get()).isEqualTo(0)
        assertThat(datagrams.get()).isEqualTo(0)
        assertThat(tcp.sent).containsExactlyElementsIn(sentBefore).inOrder()
        assertThat(transports.udps).hasSize(1)
        assertThat(connection.error).isNull()
    }

    /** Invokes every declared method of both transport listener interfaces on [connection]. */
    private fun invokeEveryTransportCallback(): List<String> {
        val interfaces = listOf(
            HumlaTCP.TCPConnectionListener::class.java,
            HumlaUDP.UDPConnectionListener::class.java,
        )
        val names = mutableListOf<String>()
        for (iface in interfaces) {
            for (method in iface.declaredMethods.sortedBy { it.name }) {
                method.invoke(connection, *method.parameterTypes.map { argumentFor(method, it) }.toTypedArray())
                names += method.name
            }
        }
        return names
    }

    private fun argumentFor(method: Method, type: Class<*>): Any = when {
        type == HumlaTCPMessageType::class.java -> HumlaTCPMessageType.Version
        type == Int::class.javaPrimitiveType -> versionFrame.size
        // The only parameter type two callbacks share, and they need different bytes: a protobuf
        // for the TCP side, a datagram whose type nibble is a voice packet for the UDP side.
        type == ByteArray::class.java -> if (method.name == "onUDPDataReceived") voiceDatagram else versionFrame
        type == Array<X509Certificate>::class.java -> emptyArray<X509Certificate>()
        type == HumlaException::class.java ->
            HumlaException("late failure", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        Exception::class.java.isAssignableFrom(type) -> java.io.IOException("late udp failure")
        else -> error("No test argument for ${type.name} of ${method.name}; a new callback needs one")
    }

    private companion object {
        const val PROTOCOL_THREAD = "humla-protocol"

        /** A well-formed Version frame, used wherever a callback wants TCP payload bytes. */
        val versionFrame: ByteArray = Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray()

        /** A datagram whose leading type nibble marks it as Opus voice data. */
        val voiceDatagram: ByteArray = ByteArray(64).also {
            it[0] = ((HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) and 0xFF).toByte()
        }
    }
}
