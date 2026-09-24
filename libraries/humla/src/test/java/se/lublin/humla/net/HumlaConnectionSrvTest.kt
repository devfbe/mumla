package se.lublin.humla.net

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.awaitUntil
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class HumlaConnectionSrvTest {
    private val transports = FakeTransports()
    private val lookups = CopyOnWriteArrayList<String>()
    private val originalLookup = Server.srvLookup
    private var connection: HumlaConnection? = null

    init {
        Server.srvLookup = Server.SrvLookup { host ->
            lookups += host
            InetSocketAddress.createUnresolved("srv-target.example", 1234)
        }
    }

    @After
    fun tearDown() {
        Server.srvLookup = originalLookup
        connection?.let { c ->
            c.disconnect()
            shadowOf(Looper.getMainLooper()).idle()
            awaitUntil(description = "protocol thread quit") { !c.protocolThread.isAlive }
        }
    }

    private fun connect(useTor: Boolean): FakeTcpTransport {
        val c = HumlaConnection(RecordingConnectionListener(), transports, Handler(Looper.getMainLooper()), { 0L })
        connection = c
        c.setUseTor(useTor)
        c.connect(Server(-1, "test", "mumble.example", 0, "user", ""))
        awaitUntil(description = "tcp connect") { transports.tcps.isNotEmpty() && transports.tcps[0].connectThread != null }
        return transports.tcps[0]
    }

    @Test
    fun withoutTorTheSrvRecordPicksTheEndpoint() {
        val tcp = connect(useTor = false)

        assertThat(lookups).containsExactly("mumble.example")
        assertThat(tcp.connectHost).isEqualTo("srv-target.example")
        assertThat(tcp.connectPort).isEqualTo(1234)
    }

    @Test
    fun overTorNoSrvLookupIsMadeAndTheProxyGetsTheEnteredHost() {
        val tcp = connect(useTor = true)

        assertThat(lookups).isEmpty()
        assertThat(tcp.connectHost).isEqualTo("mumble.example")
        assertThat(tcp.connectPort).isEqualTo(64738)
    }
}
