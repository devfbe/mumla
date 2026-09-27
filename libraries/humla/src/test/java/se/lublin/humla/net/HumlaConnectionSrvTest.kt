package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import se.lublin.humla.model.Server
import java.util.concurrent.CopyOnWriteArrayList

/** The connection opens its socket where the resolver says; over Tor the proxy resolves the host. */
class HumlaConnectionSrvTest {
    private val lookups = CopyOnWriteArrayList<String>()
    private val resolver = ServerResolver({ host ->
        lookups += host
        Endpoint("srv-target.example", 1234)
    }, Dispatchers.Unconfined)

    private fun connect(useTor: Boolean): ConnectionHarness {
        val server = Server(-1, "test", "mumble.example", 0, "user", "")
        val h = ConnectionHarness(useTor = useTor, server = server, resolver = resolver)
        h.establish()
        return h
    }

    @Test
    fun withoutTorTheSrvRecordPicksTheEndpoint() {
        val h = connect(useTor = false)

        assertThat(lookups).containsExactly("mumble.example")
        assertThat(h.tcp.connectHost).isEqualTo("srv-target.example")
        assertThat(h.tcp.connectPort).isEqualTo(1234)
        assertThat(h.transports.udps.single().connectHost).isEqualTo("srv-target.example")
        h.close()
    }

    @Test
    fun overTorNoSrvLookupIsMadeAndTheProxyGetsTheEnteredHost() {
        val h = connect(useTor = true)

        assertThat(lookups).isEmpty()
        assertThat(h.tcp.connectHost).isEqualTo("mumble.example")
        assertThat(h.tcp.connectPort).isEqualTo(64738)
        assertThat(h.tcp.connectUseTor).isTrue()
        h.close()
    }
}
