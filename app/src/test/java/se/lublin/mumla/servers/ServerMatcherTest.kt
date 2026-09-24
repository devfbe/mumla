package se.lublin.mumla.servers

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import se.lublin.humla.Constants
import se.lublin.mumla.db.PublicServer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class ServerMatcherTest {
    private fun server(name: String, country: String = "SE") =
        PublicServer(name, "0", "EU", country, country, "$name.example", 64738, "", "")

    private fun reply(server: PublicServer, users: Int, latency: Int, version: Int = Constants.PROTOCOL_VERSION) =
        ServerInfoResponse(
            server,
            ByteBuffer.allocate(24).putInt(version).putLong(0).putInt(users).putInt(10).putInt(0).array(),
            latency,
        )

    @Test
    fun theFastestEmptyServerInTheCountryWins() = runTest {
        val a = server("a")
        val b = server("b")
        val busy = server("busy")
        val abroad = server("abroad", country = "DE")
        val replies = mapOf(
            a to reply(a, users = 0, latency = 80),
            b to reply(b, users = 0, latency = 30),
            busy to reply(busy, users = 3, latency = 5),
            abroad to reply(abroad, users = 0, latency = 1),
        )

        val match = matchServer(listOf(a, b, busy, abroad), "SE", { replies.getValue(it) })

        assertThat(match!!.server).isSameInstanceAs(b)
    }

    @Test
    fun serversOnAnotherProtocolVersionOrWithoutReplyAreIgnored() = runTest {
        val old = server("old")
        val silent = server("silent")
        val replies = mapOf(old to reply(old, users = 0, latency = 1, version = 0x10203), silent to ServerInfoResponse())

        assertThat(matchServer(listOf(old, silent), null, { replies.getValue(it) })).isNull()
    }

    @Test
    fun aCountryWithoutServersPingsNothing() = runTest {
        val pings = AtomicInteger()

        val match = matchServer(listOf(server("a")), "DE", { pings.incrementAndGet(); ServerInfoResponse() })

        assertThat(match).isNull()
        assertThat(pings.get()).isEqualTo(0)
    }

    @Test
    fun pingingStopsAfterTheSampleAndNeverExceedsTheConcurrencyBound() = runTest {
        val servers = (1..50).map { server("s$it") }
        val started = AtomicInteger()
        val active = AtomicInteger()
        var maxActive = 0

        matchServer(servers, null, {
            started.incrementAndGet()
            maxActive = maxOf(maxActive, active.incrementAndGet())
            delay(10)
            active.decrementAndGet()
            reply(it, users = 0, latency = 10)
        }, sampleSize = 20, concurrency = 5)

        assertThat(maxActive).isAtMost(5)
        assertThat(started.get()).isAtMost(20 + 5)
    }
}
