package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.HumlaService
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.MAX_EVENTS_PER_SLICE
import se.lublin.humla.session.inMainThreadSlices
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.testutil.collectOnMain
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [HumlaConnection] threading: the socket is opened, frames are parsed and handlers are dispatched
 * on the "humla-protocol" thread, listener callbacks arrive on the main looper, and the protocol
 * thread's own lifecycle cannot swallow a disconnect report.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaConnectionProtocolThreadTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val transports = FakeTransports()
    private val listener = RecordingConnectionListener()
    private val connection = HumlaConnection(listener, transports)
    private val server = Server(-1, "test", "127.0.0.1", 64738, "user", "")

    @After
    fun tearDown() {
        connection.disconnect()
        mainLooper.idle()
        awaitUntil(description = "nothing of the connection left by this test") { connection.isTerminated }
    }

    /**
     * Waits for something the main looper still has to deliver, idling it as part of the wait. The
     * callbacks are posted *after* the protocol thread sets the flag a test could otherwise poll.
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
     * Runs [event] on the protocol thread after [disconnect] has been asked for but before the
     * teardown it posted has run, so both transports are still wired up. The gate forces that
     * interleaving.
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
        connection.addTcpHandler { if (it is Mumble.Version) { handlerThreads += Thread.currentThread().name } }

        tcp.simulateMessage(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray())

        awaitUntil { handlerThreads.isNotEmpty() }
        assertThat(handlerThreads).containsExactly(PROTOCOL_THREAD)
        // The parsed version is only readable once the connection is synchronized.
        assertThrows(IllegalStateException::class.java) { connection.getServerRelease() }
    }

    @Test
    fun fiveThousandChannelStatesDoNotStallAMainLooperTask() {
        val tcp = connectAndEstablish()
        val events = MutableSharedFlow<HumlaEvent>(
            extraBufferCapacity = HumlaService.EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val added = AtomicInteger()
        val lastAdded = AtomicInteger(-1)
        val addedOnMain = AtomicBoolean(true)
        val collector = collectOnMain(events.inMainThreadSlices()) { event ->
            if (event is HumlaEvent.ChannelAdded) {
                added.incrementAndGet()
                lastAdded.set(event.channel.id)
                if (Looper.myLooper() != Looper.getMainLooper()) addedOnMain.set(false)
            }
        }
        connection.addTcpHandler(ModelHandler({ events.tryEmit(it) }, null, null))
        val processed = AtomicInteger()
        connection.addTcpHandler { if (it is Mumble.ChannelState) { processed.incrementAndGet() } }
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

        // What production bounds is the slice: at most MAX_EVENTS_PER_SLICE events per main-looper
        // task. A wall-clock assertion would be flaky on CI.
        assertThat(tasksBeforeProbe).isAtMost(1)
        assertThat(eventsBeforeProbe).isAtMost(MAX_EVENTS_PER_SLICE)
        mainLooper.idle()
        // All of them fit into the buffer, and they arrive in order on the main thread.
        assertThat(added.get()).isEqualTo(5_000)
        assertThat(lastAdded.get()).isEqualTo(4_999)
        assertThat(addedOnMain.get()).isTrue()
        collector.cancel()
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
     * The transport's own terminal callback, dispatched from inside the teardown, lands in the
     * cancelled scope: the report the listener gets comes from disconnect() itself, exactly once.
     */
    @Test
    fun theTransportsTerminalCallbackBehindTheTeardownIsDropped() {
        val tcp = connectAndEstablish()

        connection.disconnect()
        awaitUntil { tcp.disconnectCalls == 1 }
        awaitUntil(description = "protocol thread quit") { !connection.protocolThread.isAlive }
        awaitOnMain("disconnect delivered") { listener.disconnects.isNotEmpty() }

        assertThat(tcp.terminalDelivered.get()).isFalse()
        assertThat(listener.disconnects).containsExactly(null)
        assertThat(listener.events.last()).isEqualTo("disconnected")
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
     * A frame that completed during teardown must not reach the message handlers: the consumer has
     * already shut its audio path down.
     */
    @Test
    fun framesArrivingBehindADisconnectAreNotDispatched() {
        val tcp = connectAndEstablish()
        val seen = AtomicInteger()
        connection.addTcpHandler { if (it is Mumble.Version) { seen.incrementAndGet() } }
        // Park the protocol thread so the frame is provably queued ahead of the teardown.
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
        connection.addTcpHandler { if (it is Mumble.Version) { error("handler is broken") } }
        connection.addTcpHandler { if (it is Mumble.TextMessage) { survivors.incrementAndGet() } }

        tcp.simulateMessage(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray())
        tcp.simulateMessage(HumlaTCPMessageType.TextMessage, Mumble.TextMessage.newBuilder().setMessage("still here").build().toByteArray())

        awaitUntil(description = "the protocol thread kept working after a handler threw") { survivors.get() == 1 }
        mainLooper.idle()
        assertThat(listener.disconnects).isEmpty()
    }

    /** What the handlers do not catch ends the connection with an error instead of the process. */
    @Test
    fun anErrorNothingCaughtEndsTheConnectionWithAnError() {
        val tcp = connectAndEstablish()
        connection.addTcpHandler { if (it is Mumble.Version) { throw AssertionError("broken invariant") } }

        tcp.simulateMessage(HumlaTCPMessageType.Version, versionFrame)
        awaitUntil { tcp.disconnectCalls == 1 }
        awaitOnMain("disconnect delivered") { listener.disconnects.isNotEmpty() }

        val error = listener.disconnects.single()!!
        assertThat(error.reason).isEqualTo(HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        assertThat(error.cause).isInstanceOf(AssertionError::class.java)
        assertThat(listener.events.last()).isEqualTo("disconnected")
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

    /** A disconnect before the connection was ever started must still refuse a later connect(). */
    @Test
    fun connectingAfterADisconnectThatPrecededItThrows() {
        connection.disconnect()

        val thrown = assertThrows(IllegalStateException::class.java) { connection.connect(server) }

        assertThat(thrown).hasMessageThat().contains("single-use")
        assertThat(transports.tcps).isEmpty()
        // The main looper is paused in Robolectric; idle it so a phantom disconnect would show up.
        mainLooper.idle()
        assertThat(listener.disconnects).isEmpty() // nothing was started, so there is nothing to report
    }

    /**
     * Asserts on the connection's own thread object rather than on a name filter over
     * Thread.getAllStackTraces(), which a renamed thread would silently escape.
     */
    @Test
    fun anUnusedConnectionStartsNoProtocolThread() {
        val unused = HumlaConnection(RecordingConnectionListener(), FakeTransports())
        assertThat(unused.protocolThread.isAlive).isFalse()

        unused.disconnect() // must not start a thread either
        assertThat(unused.protocolThread.isAlive).isFalse()
    }

    @Test
    fun aConnectionThatIsDisconnectedLeavesNoProtocolThreadAndNoCoroutineBehind() {
        connectAndEstablish()
        assertThat(connection.protocolThread.isAlive).isTrue()
        assertThat(connection.isTerminated).isFalse()

        connection.disconnect()

        awaitUntil(description = "protocol thread quit and scope completed") { connection.isTerminated }
    }

    /** Every piece of protocol work runs on the one protocol thread, in the order it was handed over. */
    @Test
    fun protocolWorkRunsInOrderOnTheOneProtocolThread() {
        val tcp = connectAndEstablish()
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        connection.addTcpHandler { seen += Thread.currentThread().name to (it as Mumble.TextMessage).message }
        val texts = (0 until 200).map { "message $it" }

        thread(name = "fake-tcp-read") {
            texts.forEach { tcp.simulateMessage(HumlaTCPMessageType.TextMessage, textFrame(it)) }
        }

        awaitUntil(description = "all messages handled") { seen.size == texts.size }
        assertThat(seen.map { it.second }).containsExactlyElementsIn(texts).inOrder()
        assertThat(seen.map { it.first }.toSet()).containsExactly(PROTOCOL_THREAD)
    }

    private fun textFrame(text: String) = Mumble.TextMessage.newBuilder().setMessage(text).build().toByteArray()

    /**
     * [HumlaConnection.onTCPConnectionEstablished] behind a disconnect. Observed via the UDP
     * transport, because the terminal gate drops a late onConnectionEstablished on its own.
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
        connection.addVoiceHandler { _ -> seen.incrementAndGet() }

        inTheTeardownWindow(tcp) { connection.onUDPDataReceived(voiceDatagram) }

        assertThat(seen.get()).isEqualTo(0)
    }

    /**
     * [HumlaConnection.resyncCryptState] behind a disconnect: it sends through the transport
     * directly, bypassing the connected check of the other sends.
     */
    @Test
    fun aCryptResyncBehindADisconnectPutsNothingOnTheWire() {
        val tcp = connectAndEstablish()
        val sentBefore = tcp.sent.toList()

        inTheTeardownWindow(tcp) { connection.resyncCryptState() }

        assertThat(tcp.sent).containsExactlyElementsIn(sentBefore).inOrder()
    }

    /**
     * [HumlaConnection.isSynchronized] from the disconnect onwards, checked while the teardown is
     * queued but has not run, so the flag the handshake set is still there.
     */
    @Test
    fun aSynchronizedConnectionIsNotSynchronizedFromTheDisconnectOnwards() {
        val tcp = connectAndEstablish()
        synchronize(tcp)
        assertThat(connection.isSynchronized).isTrue()
        val insideTheWindow = AtomicReference<Boolean>()

        inTheTeardownWindow(tcp) { insideTheWindow.set(connection.isSynchronized) }

        assertThat(insideTheWindow.get()).isFalse()
        assertThat(connection.isSynchronized).isFalse()
        assertThrows(IllegalStateException::class.java) { connection.getSession() }
    }

    /**
     * [HumlaConnection.sendUDPMessage] behind a disconnect. Both of its branches hand the bytes to a
     * transport directly, so its own [HumlaConnection.isConnected] check is the only guard while
     * the audio thread is still handing over frames.
     */
    @Test
    fun aVoicePacketSentBehindADisconnectReachesNeitherTransport() {
        val tcp = connectAndEstablish(forceTcp = false)
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() }
        val udp = transports.udps[0]
        val tcpSentBefore = tcp.sent.toList()

        inTheTeardownWindow(tcp) {
            connection.sendUDPMessage(voiceDatagram, voiceDatagram.size, true) // straight to UDP
            connection.setForceTCP(true)
            connection.sendUDPMessage(voiceDatagram, voiceDatagram.size, false) // tunneled over TCP
        }

        assertThat(udp.sent).isEmpty()
        assertThat(tcp.sent).containsExactlyElementsIn(tcpSentBefore).inOrder()
    }

    /**
     * [HumlaConnection.onTCPConnectionFailed] behind a disconnect must not record an error for a
     * connection whose disconnect was already reported as clean.
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
     * Every method of both transport listener interfaces must be inert in the teardown window.
     * Written with reflection so a newly added callback fails this test until its behaviour behind
     * a disconnect has been decided.
     */
    @Test
    fun noTransportCallbackDoesAnyWorkBehindADisconnect() {
        val tcp = connectAndEstablish(forceTcp = false)
        awaitUntil(description = "udp started") { transports.udps.isNotEmpty() }
        val frames = AtomicInteger()
        connection.addTcpHandler { if (it is Mumble.Version) { frames.incrementAndGet() } }
        val datagrams = AtomicInteger()
        connection.addVoiceHandler { _ -> datagrams.incrementAndGet() }
        val sentBefore = tcp.sent.toList()
        val invoked = CopyOnWriteArrayList<String>()

        inTheTeardownWindow(tcp) { invoked += invokeEveryTransportCallback() }

        assertThat(invoked).containsExactly(
            "onTCPConnectionDisconnect", "onTCPConnectionEstablished", "onTCPConnectionFailed",
            "onTCPMessageReceived", "onTLSCertificateChanged", "onTLSHandshakeFailed",
            "onUDPConnectionError", "onUDPDataReceived", "resyncCryptState",
        )
        assertThat(frames.get()).isEqualTo(0)
        assertThat(datagrams.get()).isEqualTo(0)
        assertThat(tcp.sent).containsExactlyElementsIn(sentBefore).inOrder()
        assertThat(transports.udps).hasSize(1)
        assertThat(connection.error).isNull()
        assertThat(listener.events.last()).isEqualTo("disconnected")
    }

    /**
     * Terminality: a ServerSync still being handled when disconnect() arrives must not report the
     * session as synchronized after it ended. An entry guard cannot close this; the decision is
     * made where the callbacks are delivered, in the main looper's FIFO order.
     */
    @Test
    fun aServerSyncStillInFlightWhenTheUserDisconnectsIsNotDeliveredBehindTheDisconnect() {
        val tcp = connectAndEstablish(forceTcp = true)
        val insideTheHandler = CountDownLatch(1)
        val release = CountDownLatch(1)
        // messageServerSync's first act under forced TCP is this send, so it parks the protocol
        // thread inside the handler, before it posts onConnectionSynchronized.
        tcp.onSend = { type ->
            if (type == HumlaTCPMessageType.UDPTunnel) {
                insideTheHandler.countDown()
                release.await()
            }
        }

        tcp.simulateMessage(
            HumlaTCPMessageType.ServerSync,
            Mumble.ServerSync.newBuilder().setSession(7).build().toByteArray()
        )
        check(insideTheHandler.await(5, TimeUnit.SECONDS)) { "the ServerSync handler was never reached" }

        connection.disconnect()
        release.countDown()
        awaitUntil(description = "teardown ran behind the parked handler") { tcp.disconnectCalls == 1 }
        mainLooper.idle()

        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
        assertThat(listener.synchronizedCount.get()).isEqualTo(0)
    }

    /**
     * onTLSHandshakeFailed behind a disconnect: the certificate prompt would open on top of a
     * session that has already ended.
     */
    @Test
    fun aHandshakeFailureBehindADisconnectPromptsNobody() {
        val tcp = connectAndEstablish()

        inTheTeardownWindow(tcp) { connection.onTLSHandshakeFailed(emptyArray()) }

        assertThat(listener.handshakeFailures).isEmpty()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    @Test
    fun aCertificateChangeBehindADisconnectPromptsNobody() {
        val tcp = connectAndEstablish()

        inTheTeardownWindow(tcp) { connection.onTLSCertificateChanged(emptyArray()) }

        assertThat(listener.certificateChanges).isEmpty()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    /** onUDPConnectionError behind a disconnect must not warn in the chat of a finished session. */
    @Test
    fun aUdpErrorBehindADisconnectWarnsNobody() {
        val tcp = connectAndEstablish()

        inTheTeardownWindow(tcp) { connection.onUDPConnectionError(java.io.IOException("socket closed")) }

        assertThat(listener.warnings).isEmpty()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    /**
     * Invokes every method of both transport listener interfaces on [connection].
     *
     * `methods`, not `declaredMethods`, so callbacks of a shared base interface are not skipped.
     * Static and synthetic members are not callbacks.
     */
    private fun invokeEveryTransportCallback(): List<String> {
        val interfaces = listOf(
            HumlaTCP.TCPConnectionListener::class.java,
            HumlaUDP.UDPConnectionListener::class.java,
        )
        val names = mutableListOf<String>()
        for (iface in interfaces) {
            val callbacks = iface.methods
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .sortedBy { it.name }
            for (method in callbacks) {
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
