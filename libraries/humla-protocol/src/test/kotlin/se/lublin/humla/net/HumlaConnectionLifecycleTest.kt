package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.ServerState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The lifecycle of a [HumlaConnection]: one ordered protocol context, the listener told exactly
 * once that the connection ended and nothing after that, every callback inert once it has ended,
 * and nothing of the connection left behind.
 */
class HumlaConnectionLifecycleTest {
    private val h = ConnectionHarness(forceTcp = true)
    private val connection = h.connection
    private val listener = h.listener

    @Test
    fun theSocketIsOpenedToTheResolvedEndpointAndCallbacksArriveOnTheirExecutor() {
        val tcp = h.establish()

        assertThat(tcp.connectHost).isEqualTo("127.0.0.1")
        assertThat(tcp.connectPort).isEqualTo(64738)
        assertThat(connection.endpoint).isEqualTo(Endpoint("127.0.0.1", 64738))
        assertThat(listener.established.get()).isEqualTo(1)
        assertThat(listener.allOnCallbackThread).isTrue()
        h.close()
    }

    @Test
    fun theServerIsOnlyKnownOnceSynchronized() {
        h.establish()
        h.receive(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").setOs("Linux").build())

        assertThat(connection.serverInfo).isNull()
        assertThat(connection.latency).isNull()

        h.synchronize(session = 42, maxBandwidth = 72_000)

        val info = connection.serverInfo!!
        assertThat(info.release).isEqualTo("1.4.0")
        assertThat(info.osName).isEqualTo("Linux")
        assertThat(info.maxBandwidth).isEqualTo(72_000)
        assertThat(info.host).isEqualTo("127.0.0.1")
        assertThat(connection.latency).isNotNull()
        h.close()
    }

    /**
     * The model is reduced and published on the protocol context: a burst of five thousand channel
     * states queued there together is published as one snapshot, behind the last of them.
     */
    @Test
    fun aBurstOfChannelStatesIsPublishedAsOneSnapshot() {
        val tcp = h.establish()
        val published = CopyOnWriteArrayList<ServerState>()
        val publisher = object : ModelHandler.Publisher {
            override fun post(block: () -> Unit) = connection.post(block)

            override fun publish(state: ServerState) {
                published += state
            }
        }
        connection.addTcpHandler(ModelHandler(ServerState.empty(), {}, publisher))

        for (i in 0 until 5_000) {
            val state = Mumble.ChannelState.newBuilder().setChannelId(i).setName("channel $i")
            if (i > 0) state.parent = 0
            tcp.simulateMessage(HumlaTCPMessageType.ChannelState, state.build().toByteArray())
        }
        h.runCurrent()

        assertThat(published).hasSize(1)
        assertThat(published.single().subchannelIds(0)).hasSize(4_999)
        h.close()
    }

    @Test
    fun aUserDisconnectIsReportedExactlyOnceEvenWhenTheSocketCloseIsReportedLater() {
        val tcp = h.establish()

        connection.disconnect()
        h.runCurrent()
        assertThat(tcp.disconnectCalls).isEqualTo(1)
        tcp.simulateSocketClosed() // what the real read loop does once the socket is closed
        h.runCurrent()

        assertThat(listener.disconnects).containsExactly(null)
        assertThat(connection.isConnected).isFalse()
        assertThat(connection.isTerminated).isTrue()
    }

    /**
     * The transport's own terminal callback, dispatched from inside the teardown, lands in the
     * cancelled scope: the report the listener gets comes from disconnect() itself, exactly once.
     */
    @Test
    fun theTransportsTerminalCallbackBehindTheTeardownIsDropped() {
        val tcp = h.establish()

        connection.disconnect()
        h.runCurrent()

        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(tcp.terminalDelivered.get()).isFalse()
        assertThat(listener.disconnects).containsExactly(null)
        assertThat(listener.events.last()).isEqualTo("disconnected")
    }

    @Test
    fun aServerRejectEndsTheConnectionOnceWithTheRejectReason() {
        val tcp = h.establish()
        val reject = Mumble.Reject.newBuilder()
            .setType(Mumble.Reject.RejectType.WrongServerPW)
            .setReason("wrong password")
            .build()

        h.receive(HumlaTCPMessageType.Reject, reject)
        tcp.simulateSocketClosed()
        h.runCurrent()

        val error = listener.disconnects.single()!!
        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(error.reason).isEqualTo(HumlaException.HumlaDisconnectReason.REJECT)
        assertThat(error.message).isEqualTo("Rejected: wrong password")
        assertThat(connection.error).isSameInstanceAs(error)
    }

    /** Handlers first: the model must still name the actor when the end is reported. */
    @Test
    fun theRemovalOfTheOwnSessionEndsTheConnectionAfterTheHandlersSawIt() {
        h.establish()
        h.synchronize(session = 7)
        val order = CopyOnWriteArrayList<String>()
        connection.addTcpHandler {
            if (it is Mumble.UserRemove) order += "handler, connected: ${connection.isConnected}"
        }

        h.receive(HumlaTCPMessageType.UserRemove, Mumble.UserRemove.newBuilder().setSession(7).setActor(3).build())

        assertThat(order).containsExactly("handler, connected: true")
        val error = listener.disconnects.single()!!
        assertThat(error.reason).isEqualTo(HumlaException.HumlaDisconnectReason.USER_REMOVE)
    }

    @Test
    fun theRemovalOfAnotherSessionLeavesTheConnectionUp() {
        h.establish()
        h.synchronize(session = 7)

        h.receive(HumlaTCPMessageType.UserRemove, Mumble.UserRemove.newBuilder().setSession(8).build())

        assertThat(connection.isSynchronized).isTrue()
        h.close()
    }

    @Test
    fun aTransportFailureIsReportedOnceAsTheConnectionsError() {
        val tcp = h.establish()
        val failure = HumlaException("socket reset", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)

        tcp.simulateFailure(failure)
        h.runCurrent()
        tcp.simulateSocketClosed()
        h.runCurrent()

        assertThat(listener.disconnects).containsExactly(failure)
    }

    @Test
    fun aHandshakeFailureIsReportedBeforeTheDisconnectThatFollowsIt() {
        val tcp = h.establish()

        tcp.simulateHandshakeFailure(emptyArray())
        h.runCurrent()

        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(listener.events).containsExactly("established", "handshakeFailed", "disconnected").inOrder()
        assertThat(listener.disconnects).containsExactly(null)
        assertThat(listener.allOnCallbackThread).isTrue()
    }

    /** A frame queued ahead of the teardown must not reach the handlers: the audio path is gone. */
    @Test
    fun framesArrivingBehindADisconnectAreNotDispatched() {
        val tcp = h.establish()
        val seen = AtomicInteger()
        connection.addTcpHandler { if (it is Mumble.Version) seen.incrementAndGet() }

        tcp.simulateMessage(HumlaTCPMessageType.Version, versionFrame)
        connection.disconnect()
        h.runCurrent()

        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(seen.get()).isEqualTo(0)
    }

    /** One bad message must not take the connection - and with it the session - down. */
    @Test
    fun aThrowingHandlerDoesNotEndTheConnection() {
        h.establish()
        val survivors = AtomicInteger()
        connection.addTcpHandler { if (it is Mumble.Version) error("handler is broken") }
        connection.addTcpHandler { if (it is Mumble.TextMessage) survivors.incrementAndGet() }

        h.receive(HumlaTCPMessageType.Version, Mumble.Version.newBuilder().setRelease("1.4.0").build())
        h.receive(HumlaTCPMessageType.TextMessage, Mumble.TextMessage.newBuilder().setMessage("still here").build())

        assertThat(survivors.get()).isEqualTo(1)
        assertThat(listener.disconnects).isEmpty()
        h.close()
    }

    /** What the handlers do not catch ends the connection with an error instead of the process. */
    @Test
    fun anErrorNothingCaughtEndsTheConnectionWithAnError() {
        val tcp = h.establish()
        connection.addTcpHandler { if (it is Mumble.Version) throw AssertionError("broken invariant") }

        tcp.simulateMessage(HumlaTCPMessageType.Version, versionFrame)
        h.runCurrent()

        val error = listener.disconnects.single()!!
        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(error.reason).isEqualTo(HumlaException.HumlaDisconnectReason.OTHER_ERROR)
        assertThat(error.cause).isInstanceOf(AssertionError::class.java)
        assertThat(listener.events.last()).isEqualTo("disconnected")
    }

    @Test
    fun serverSyncStartsThePingsEveryFiveSeconds() {
        val tcp = h.establish()

        h.synchronize(session = 42)

        assertThat(listener.synchronizedCount.get()).isEqualTo(1)
        assertThat(tcp.sentMessages.filterIsInstance<Mumble.Ping>()).hasSize(1)
        h.advanceBy(5_000)
        assertThat(tcp.sentMessages.filterIsInstance<Mumble.Ping>()).hasSize(2)
        h.close()
    }

    @Test
    fun theUdpTransportFailsIntoAWarningAndATunnelRequest() {
        val udpHarness = ConnectionHarness(forceTcp = false)
        val tcp = udpHarness.establish()
        val udp = udpHarness.transports.udps.single()
        assertThat(udp.connectCalls.get()).isEqualTo(1)

        udp.simulateError(java.io.IOException("socket closed"))
        udpHarness.runCurrent()

        assertThat(udpHarness.listener.warnings).containsExactly(ConnectionWarning.UDP_THREAD_FAILED)
        assertThat(udpHarness.listener.allOnCallbackThread).isTrue()
        assertThat(tcp.sent).contains(HumlaTCPMessageType.UDPTunnel)
        udpHarness.close()
    }

    @Test
    fun connectingAgainAfterADisconnectThrowsInsteadOfSilentlyDoingNothing() {
        h.establish()
        connection.disconnect()
        h.runCurrent()

        val thrown = assertThrows(IllegalStateException::class.java) { connection.connect() }

        assertThat(thrown).hasMessageThat().contains("single-use")
        assertThat(h.transports.tcps).hasSize(1)
    }

    @Test
    fun connectingTwiceThrows() {
        h.establish()

        val thrown = assertThrows(IllegalStateException::class.java) { connection.connect() }

        assertThat(thrown).hasMessageThat().contains("single-use")
        h.close()
    }

    /** A disconnect before the connection was ever started must still refuse a later connect(). */
    @Test
    fun connectingAfterADisconnectThatPrecededItThrowsAndNothingIsReported() {
        connection.disconnect()

        val thrown = assertThrows(IllegalStateException::class.java) { connection.connect() }
        h.runCurrent()

        assertThat(thrown).hasMessageThat().contains("single-use")
        assertThat(h.transports.tcps).isEmpty()
        assertThat(listener.disconnects).isEmpty() // nothing was started, so there is nothing to report
        assertThat(connection.isTerminated).isTrue()
    }

    /** Every piece of protocol work runs in the order it was handed over. */
    @Test
    fun protocolWorkRunsInSubmissionOrder() {
        val tcp = h.establish()
        val seen = CopyOnWriteArrayList<String>()
        connection.addTcpHandler { seen += (it as Mumble.TextMessage).message }
        val texts = (0 until 200).map { "message $it" }

        texts.forEachIndexed { i, text ->
            tcp.simulateMessage(HumlaTCPMessageType.TextMessage, textFrame(text))
            if (i % 50 == 0) connection.post { seen += "posted $i" }
        }
        h.runCurrent()

        val expected = texts.flatMapIndexed { i, text -> if (i % 50 == 0) listOf(text, "posted $i") else listOf(text) }
        assertThat(seen).containsExactlyElementsIn(expected).inOrder()
        h.close()
    }

    /** Observed via the UDP transport, because the terminal gate drops a late callback on its own. */
    @Test
    fun anEstablishedCallbackBehindADisconnectStartsNoSecondUdpTransport() {
        val udpHarness = ConnectionHarness(forceTcp = false)
        udpHarness.establish()

        udpHarness.inTheTeardownWindow { udpHarness.connection.onTCPConnectionEstablished() }

        assertThat(udpHarness.transports.udps).hasSize(1)
        assertThat(udpHarness.connection.isConnected).isFalse()
    }

    @Test
    fun aDatagramArrivingBehindADisconnectIsNotDispatched() {
        h.establish()
        val seen = AtomicInteger()
        connection.addVoiceHandler { seen.incrementAndGet() }

        h.inTheTeardownWindow { connection.onUDPDataReceived(voiceDatagram) }

        assertThat(seen.get()).isEqualTo(0)
    }

    @Test
    fun aCryptResyncBehindADisconnectPutsNothingOnTheWire() {
        val tcp = h.establish()
        val sentBefore = tcp.sent.toList()

        h.inTheTeardownWindow { connection.resyncCryptState() }

        assertThat(tcp.sent).containsExactlyElementsIn(sentBefore).inOrder()
    }

    /** Checked while the teardown is queued but has not run, so nothing else has changed yet. */
    @Test
    fun aSynchronizedConnectionIsNotSynchronizedFromTheDisconnectOnwards() {
        h.establish()
        h.synchronize()
        assertThat(connection.isSynchronized).isTrue()
        val insideTheWindow = AtomicReference<Boolean>()

        h.inTheTeardownWindow { insideTheWindow.set(connection.isSynchronized) }

        assertThat(insideTheWindow.get()).isFalse()
        assertThat(connection.isSynchronized).isFalse()
        assertThat(connection.serverInfo).isNull()
    }

    /**
     * Both branches of [HumlaConnection.sendUDPMessage] hand the bytes to a transport directly, so
     * its own connected check is the only guard while the audio thread still hands over frames.
     */
    @Test
    fun aVoicePacketSentBehindADisconnectReachesNeitherTransport() {
        val udpHarness = ConnectionHarness(forceTcp = false)
        val tcp = udpHarness.establish()
        val udp = udpHarness.transports.udps.single()
        udp.simulateError(java.io.IOException("down")) // unforced voice now goes through the tunnel
        udpHarness.runCurrent()
        val tcpSentBefore = tcp.sent.toList()

        udpHarness.inTheTeardownWindow {
            udpHarness.connection.sendUDPMessage(voiceDatagram, voiceDatagram.size, true) // straight to UDP
            udpHarness.connection.sendUDPMessage(voiceDatagram, voiceDatagram.size, false) // tunnelled
        }

        assertThat(udp.sent).isEmpty()
        assertThat(tcp.sent).containsExactlyElementsIn(tcpSentBefore).inOrder()
    }

    /** A clean end already decided must not gain an error afterwards. */
    @Test
    fun aTransportFailureBehindADisconnectDoesNotBecomeTheConnectionsError() {
        h.establish()

        h.inTheTeardownWindow {
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
        val udpHarness = ConnectionHarness(forceTcp = false)
        val tcp = udpHarness.establish()
        val connection = udpHarness.connection
        val frames = AtomicInteger()
        connection.addTcpHandler { if (it is Mumble.Version) frames.incrementAndGet() }
        val datagrams = AtomicInteger()
        connection.addVoiceHandler { datagrams.incrementAndGet() }
        val sentBefore = tcp.sent.toList()
        val invoked = CopyOnWriteArrayList<String>()

        udpHarness.inTheTeardownWindow { invoked += invokeEveryTransportCallback(connection) }

        assertThat(invoked).containsExactly(
            "onTCPConnectionDisconnect", "onTCPConnectionEstablished", "onTCPConnectionFailed",
            "onTCPMessageReceived", "onTLSCertificateChanged", "onTLSHandshakeFailed",
            "onUDPConnectionError", "onUDPDataReceived", "resyncCryptState",
        )
        assertThat(frames.get()).isEqualTo(0)
        assertThat(datagrams.get()).isEqualTo(0)
        assertThat(tcp.sent).containsExactlyElementsIn(sentBefore).inOrder()
        assertThat(udpHarness.transports.udps).hasSize(1)
        assertThat(connection.error).isNull()
        assertThat(udpHarness.listener.events).containsExactly("established", "disconnected").inOrder()
    }

    /**
     * A ServerSync still being handled when disconnect() arrives must not report the session as
     * synchronized after it ended. An entry guard cannot close this; the decision is made where the
     * callbacks are delivered, in their executor's order.
     */
    @Test
    fun aServerSyncStillInFlightWhenTheUserDisconnectsIsNotDeliveredBehindTheDisconnect() {
        val tcp = h.establish()
        // Under forced TCP the ServerSync handler's first act is this send: the user disconnects
        // while the protocol context is inside the handler.
        tcp.onSend = { type -> if (type == HumlaTCPMessageType.UDPTunnel) connection.disconnect() }

        h.synchronize(session = 7)

        assertThat(tcp.disconnectCalls).isEqualTo(1)
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
        assertThat(listener.synchronizedCount.get()).isEqualTo(0)
    }

    /** The certificate prompt would open on top of a session that has already ended. */
    @Test
    fun aHandshakeFailureBehindADisconnectPromptsNobody() {
        h.establish()

        h.inTheTeardownWindow { connection.onTLSHandshakeFailed(emptyArray()) }

        assertThat(listener.handshakeFailures).isEmpty()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    @Test
    fun aCertificateChangeBehindADisconnectPromptsNobody() {
        h.establish()

        h.inTheTeardownWindow { connection.onTLSCertificateChanged(emptyArray()) }

        assertThat(listener.certificateChanges).isEmpty()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    @Test
    fun aUdpErrorBehindADisconnectWarnsNobody() {
        h.establish()

        h.inTheTeardownWindow { connection.onUDPConnectionError(java.io.IOException("socket closed")) }

        assertThat(listener.warnings).isEmpty()
        assertThat(listener.events).containsExactly("established", "disconnected").inOrder()
    }

    private fun textFrame(text: String) = Mumble.TextMessage.newBuilder().setMessage(text).build().toByteArray()

    /**
     * Invokes every method of both transport listener interfaces on [connection].
     *
     * `methods`, not `declaredMethods`, so callbacks of a shared base interface are not skipped.
     * Static and synthetic members are not callbacks.
     */
    private fun invokeEveryTransportCallback(connection: HumlaConnection): List<String> {
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
        /** A well-formed Version frame, used wherever a callback wants TCP payload bytes. */
        val versionFrame: ByteArray = Mumble.Version.newBuilder().setRelease("1.4.0").build().toByteArray()

        /** A datagram whose leading type nibble marks it as Opus voice data. */
        val voiceDatagram: ByteArray = ByteArray(64).also {
            it[0] = ((HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) and 0xFF).toByte()
        }
    }
}
