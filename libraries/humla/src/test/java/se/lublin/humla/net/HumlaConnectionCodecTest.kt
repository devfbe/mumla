package se.lublin.humla.net

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList

/** Opus is the only codec: a server without it leaves voice off and says so once. */
@RunWith(RobolectricTestRunner::class)
class HumlaConnectionCodecTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val transports = FakeTransports()
    private val listener = RecordingConnectionListener()
    private val connection = HumlaConnection(listener, transports)

    @After
    fun tearDown() {
        connection.disconnect()
        mainLooper.idle()
        awaitUntil(description = "protocol thread gone") { !connection.protocolThread.isAlive }
    }

    private val handledVersions = CopyOnWriteArrayList<Int>()

    /** Connects and synchronizes; the codec is only readable on a synchronized connection. */
    private fun connect(): FakeTcpTransport {
        connection.addTcpHandler { if (it is Mumble.Version) { handledVersions += it.versionV1 } }
        connection.setForceTCP(true)
        connection.connect(Server(-1, "test", "127.0.0.1", 64738, "user", ""))
        awaitUntil(description = "tcp connect") {
            transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null
        }
        val tcp = transports.tcps[0]
        tcp.simulateConnected()
        val sync = Mumble.ServerSync.newBuilder().setSession(1).build()
        tcp.simulateMessage(HumlaTCPMessageType.ServerSync, sync.toByteArray())
        awaitUntil(description = "synchronized") { mainLooper.idle(); listener.synchronizedCount.get() == 1 }
        return tcp
    }

    private fun FakeTcpTransport.sendCodecVersion(opus: Boolean) = simulateMessage(
        HumlaTCPMessageType.CodecVersion,
        Mumble.CodecVersion.newBuilder()
            .setAlpha(0x8000000b.toInt()).setBeta(0).setPreferAlpha(true).setOpus(opus)
            .build().toByteArray(),
    )

    /** Waits until everything sent before has been handled and its callbacks delivered. */
    private fun FakeTcpTransport.drain() {
        val marker = handledVersions.size + 1000
        val version = Mumble.Version.newBuilder().setVersionV1(marker).build()
        simulateMessage(HumlaTCPMessageType.Version, version.toByteArray())
        awaitUntil(description = "marker handled") { handledVersions.contains(marker) }
        mainLooper.idle()
    }

    @Test
    fun aServerWithOpusSelectsOpusAndWarnsNobody() {
        val tcp = connect()

        tcp.sendCodecVersion(opus = true)
        tcp.drain()

        assertThat(connection.getCodec()).isEqualTo(HumlaUDPMessageType.UDPVoiceOpus)
        assertThat(listener.warnings).isEmpty()
    }

    @Test
    fun aServerWithoutOpusSelectsNoCodecAndWarnsOnce() {
        val tcp = connect()

        tcp.sendCodecVersion(opus = false)
        tcp.sendCodecVersion(opus = false)
        tcp.drain()

        assertThat(connection.getCodec()).isNull()
        assertThat(listener.warnings).containsExactly(ConnectionWarning.NO_OPUS)
    }

    @Test
    fun aServerThatGainsOpusSelectsItAgain() {
        val tcp = connect()

        tcp.sendCodecVersion(opus = false)
        tcp.sendCodecVersion(opus = true)
        tcp.drain()

        assertThat(connection.getCodec()).isEqualTo(HumlaUDPMessageType.UDPVoiceOpus)
    }
}
