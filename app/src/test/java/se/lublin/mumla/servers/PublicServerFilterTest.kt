package se.lublin.mumla.servers

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.mumla.db.PublicServer
import java.nio.ByteBuffer

class PublicServerFilterTest {
    private fun server(name: String, country: String?) =
        PublicServer(name, null, country, null, "$name.example", 64738, null, null)

    private fun reply(server: PublicServer, users: Int, latency: Int) = ServerInfoResponse(
        server.server,
        ByteBuffer.allocate(24).putInt(0, MIN_MATCH_VERSION).putInt(12, users).putInt(16, 50).array(),
        latency,
    )

    private val zurich = server("Zürich Talk", "Switzerland")
    private val oslo = server("Oslo Gaming", "Norway")
    private val bergen = server("Bergen", "Norway")
    private val nowhere = server("Nowhere", null)
    private val servers = listOf(zurich, oslo, bergen, nowhere)

    private fun arrange(
        filter: PublicServerFilter = PublicServerFilter(),
        replies: Map<ServerAddress, ServerInfoResponse> = emptyMap(),
        list: List<PublicServer> = servers,
    ) = arrangePublicServers(list, filter, replies).map { it.name }

    private fun replies(vararg entries: ServerInfoResponse) = entries.associateBy { it.server!!.address }

    @Test
    fun `no filter keeps every server, in list order while none answered`() {
        assertThat(arrange()).containsExactly("Zürich Talk", "Oslo Gaming", "Bergen", "Nowhere").inOrder()
    }

    @Test
    fun `the query matches the name ignoring case`() {
        assertThat(arrange(PublicServerFilter(query = "GAMING"))).containsExactly("Oslo Gaming")
    }

    @Test
    fun `the query matches the country`() {
        assertThat(arrange(PublicServerFilter(query = "norw"))).containsExactly("Oslo Gaming", "Bergen").inOrder()
    }

    @Test
    fun `the query ignores diacritics on either side`() {
        assertThat(arrange(PublicServerFilter(query = "zurich"))).containsExactly("Zürich Talk")
        assertThat(arrange(PublicServerFilter(query = "BÉRGEN"))).containsExactly("Bergen")
    }

    @Test
    fun `the query ignores surrounding blanks, and a blank query matches everything`() {
        assertThat(arrange(PublicServerFilter(query = "  oslo "))).containsExactly("Oslo Gaming")
        assertThat(arrange(PublicServerFilter(query = "   "))).hasSize(servers.size)
    }

    @Test
    fun `a query nothing matches leaves nothing`() {
        assertThat(arrange(PublicServerFilter(query = "xyz"))).isEmpty()
    }

    @Test
    fun `selected countries keep only their servers, not the countryless ones`() {
        assertThat(arrange(PublicServerFilter(countries = setOf("Norway"))))
            .containsExactly("Oslo Gaming", "Bergen").inOrder()
        assertThat(arrange(PublicServerFilter(countries = setOf("Norway", "Switzerland"))))
            .containsExactly("Zürich Talk", "Oslo Gaming", "Bergen").inOrder()
    }

    @Test
    fun `the query and the countries must both match`() {
        assertThat(arrange(PublicServerFilter(query = "gam", countries = setOf("Norway", "Switzerland"))))
            .containsExactly("Oslo Gaming")
        assertThat(arrange(PublicServerFilter(query = "talk", countries = setOf("Norway")))).isEmpty()
    }

    @Test
    fun `by users the fullest come first, ties in list order, then offline and unanswered in list order`() {
        val answers = replies(
            reply(zurich, users = 3, latency = 90),
            reply(bergen, users = 7, latency = 10),
            reply(nowhere, users = 3, latency = 5),
        ) + (oslo.server.address to ServerInfoResponse())
        val list = listOf(zurich, oslo, bergen, nowhere, server("Silent", "Norway"))

        assertThat(arrange(PublicServerFilter(sort = PublicServerSort.USERS), answers, list))
            .containsExactly("Bergen", "Zürich Talk", "Nowhere", "Oslo Gaming", "Silent").inOrder()
    }

    @Test
    fun `by ping the fastest come first, ties in list order, then offline and unanswered in list order`() {
        val answers = replies(
            reply(zurich, users = 3, latency = 40),
            reply(bergen, users = 7, latency = 10),
            reply(nowhere, users = 0, latency = 40),
        ) + (oslo.server.address to ServerInfoResponse())
        val list = listOf(server("Silent", "Norway"), zurich, oslo, bergen, nowhere)

        assertThat(arrange(PublicServerFilter(sort = PublicServerSort.PING), answers, list))
            .containsExactly("Bergen", "Zürich Talk", "Nowhere", "Silent", "Oslo Gaming").inOrder()
    }

    @Test
    fun `sorting applies to the filtered servers only`() {
        val answers = replies(reply(zurich, users = 9, latency = 1), reply(bergen, users = 1, latency = 2))

        assertThat(arrange(PublicServerFilter(countries = setOf("Norway")), answers))
            .containsExactly("Bergen", "Oslo Gaming").inOrder()
    }

    @Test
    fun `the countries to choose from are each named country once, alphabetically`() {
        val list = servers + server("Stavanger", "Norway") + server("Wien", "Österreich") + server("Blank", " ")

        assertThat(countriesOf(list)).containsExactly("Norway", "Österreich", "Switzerland").inOrder()
    }
}
