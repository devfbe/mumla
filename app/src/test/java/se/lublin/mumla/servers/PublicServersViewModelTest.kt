package se.lublin.mumla.servers

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.testing.drainMainUntil
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class PublicServersViewModelTest {
    private fun server(name: String, country: String?, countryCode: String? = null) =
        PublicServer(name, null, country, countryCode, "$name.example", 64738, null, null)

    private val servers = listOf(server("Bravo", "Sweden"), server("alpha", "Norway"), server("Charlie", null))
    private val repository = MumlaRepository(mockk<MumlaDatabase>(relaxed = true), Dispatchers.Unconfined)

    /** Answers every ping with the users [users] gives the server's name, none by default. */
    private fun pinger(users: (String) -> Int = { 0 }) = mockk<ServerPinger> {
        coEvery { ping(any()) } answers {
            val server = firstArg<Server>()
            val reply = ByteBuffer.allocate(24).putInt(0, MIN_MATCH_VERSION).putInt(12, users(server.name)).array()
            ServerInfoResponse(server, reply, 10)
        }
    }

    private fun viewModel(
        tor: Boolean = false,
        fetched: List<PublicServer>? = servers,
        savedState: SavedStateHandle = SavedStateHandle(),
        pinger: ServerPinger = pinger(),
    ): PublicServersViewModel {
        val fetcher = mockk<PublicServerFetcher> { coEvery { fetch() } returns fetched }
        return PublicServersViewModel(repository, fetcher, { tor }, savedState, pinger, Dispatchers.Unconfined)
            .also { idleMainLooper() }
    }

    private fun PublicServersViewModel.shown() = (state.value as PublicServersViewModel.State.Shown).servers.map { it.name }

    @Test
    fun `the downloaded servers are shown with the countries to choose from`() {
        val state = viewModel().state.value as PublicServersViewModel.State.Shown

        assertThat(state.servers.map { it.name }).containsExactly("Bravo", "alpha", "Charlie").inOrder()
        assertThat(state.countries).containsExactly("Norway", "Sweden").inOrder()
    }

    @Test
    fun `the query, the countries and the sort narrow and order the shown servers`() {
        val list = viewModel()

        list.setQuery("A")
        idleMainLooper()
        assertThat(list.shown()).containsExactly("Bravo", "alpha", "Charlie").inOrder()

        list.setCountry("Norway", selected = true)
        list.setCountry("Sweden", selected = true)
        idleMainLooper()
        assertThat(list.shown()).containsExactly("Bravo", "alpha").inOrder()

        list.setCountry("Sweden", selected = false)
        idleMainLooper()
        assertThat(list.shown()).containsExactly("alpha")

        list.clearCountries()
        list.setQuery("")
        idleMainLooper()
        assertThat(list.shown()).hasSize(3)
        assertThat(list.filter.value).isEqualTo(PublicServerFilter())
    }

    @Test
    fun `arriving ping replies reorder the list by users`() {
        val list = viewModel(pinger = pinger { name -> if (name == "Charlie") 5 else 1 })
        servers.forEach { list.pings.request(it.server) }
        drainMainUntil(description = "all replies") { list.pings.replies.value.size == servers.size }

        drainMainUntil(description = "Charlie first") { list.shown().first() == "Charlie" }
        assertThat(list.shown()).containsExactly("Charlie", "Bravo", "alpha").inOrder()
    }

    @Test
    fun `the filter is kept in the saved state and restored from it`() {
        val savedState = SavedStateHandle()
        viewModel(savedState = savedState).apply {
            setQuery("a")
            setCountry("Norway", selected = true)
            setSort(PublicServerSort.PING)
        }

        val restored = viewModel(savedState = savedState)

        assertThat(restored.filter.value)
            .isEqualTo(PublicServerFilter("a", setOf("Norway"), PublicServerSort.PING))
        assertThat(restored.shown()).containsExactly("alpha")
    }

    @Test
    fun `the default sort is by users`() {
        assertThat(viewModel().filter.value.sort).isEqualTo(PublicServerSort.USERS)
    }

    @Test
    fun `matching looks among the shown servers only`() {
        val list = viewModel(
            fetched = listOf(server("Near", "Norway", "NO"), server("Far", "Sweden", "SE")),
        )
        list.setCountry("Sweden", selected = true)
        idleMainLooper()

        val match = runBlocking { list.match(null) }

        assertThat(match?.server?.name).isEqualTo("Far")
        assertThat(list.shownEntryOf(match!!.server!!)?.country).isEqualTo("Sweden")
    }

    @Test
    fun `over Tor nothing is downloaded or pinged`() {
        val list = viewModel(tor = true)

        assertThat(list.state.value).isEqualTo(PublicServersViewModel.State.TorBlocked)
        assertThat(list.pings.allowed()).isFalse()
    }

    @Test
    fun `a failed download says so`() {
        val list = viewModel(fetched = null)

        assertThat(list.state.value).isEqualTo(PublicServersViewModel.State.DownloadFailed)
    }

    @Test
    fun `a retry after a failed download downloads again`() {
        val fetcher = mockk<PublicServerFetcher> { coEvery { fetch() } returnsMany listOf(null, servers) }
        val list = PublicServersViewModel(repository, fetcher, { false }, SavedStateHandle(), pinger(), Dispatchers.Unconfined)
            .also { idleMainLooper() }
        assertThat(list.state.value).isEqualTo(PublicServersViewModel.State.DownloadFailed)

        list.retry()
        idleMainLooper()

        assertThat(list.shown()).containsExactly("Bravo", "alpha", "Charlie").inOrder()
    }

    @Test
    fun `a favourite is stored with the username given`() {
        val database = mockk<MumlaDatabase>(relaxed = true)
        val repository = MumlaRepository(database, Dispatchers.Unconfined)
        val list = PublicServersViewModel(repository, mockk(relaxed = true), { true }, SavedStateHandle())

        list.favourite(servers[0], "me")

        verify { database.addServer(match { it.username == "me" && it.host == "Bravo.example" }) }
    }
}
