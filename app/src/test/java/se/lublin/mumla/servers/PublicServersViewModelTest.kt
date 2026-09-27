package se.lublin.mumla.servers

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.PublicServer

@RunWith(RobolectricTestRunner::class)
class PublicServersViewModelTest {
    private fun server(name: String, country: String?) =
        PublicServer(name, null, country, null, "$name.example", 64738, null, null)

    private val servers = listOf(server("Bravo", "Sweden"), server("alpha", "Norway"), server("Charlie", null))
    private val repository = MumlaRepository(mockk<MumlaDatabase>(relaxed = true), Dispatchers.Unconfined)

    private fun viewModel(tor: Boolean = false, fetched: List<PublicServer>? = servers): PublicServersViewModel {
        val fetcher = mockk<PublicServerFetcher> { coEvery { fetch() } returns fetched }
        return PublicServersViewModel(repository, fetcher, { tor }).also { idleMainLooper() }
    }

    private fun PublicServersViewModel.shown() =
        (state.value as PublicServersViewModel.State.Shown).servers.map { it.name }

    @Test
    fun `filtering matches name and country ignoring case, in list order, and forgets an earlier sort`() {
        val list = viewModel()
        list.sort(PublicServersViewModel.Order.NAME)
        list.filter("a", "")
        assertThat(list.shown()).containsExactly("Bravo", "alpha", "Charlie").inOrder()

        list.filter("", "nor")
        assertThat(list.shown()).containsExactly("alpha")
    }

    @Test
    fun `sorting orders the shown servers, by name or by country with the countryless last`() {
        val list = viewModel()
        list.filter("R", "")
        list.sort(PublicServersViewModel.Order.NAME)
        assertThat(list.shown()).containsExactly("Bravo", "Charlie").inOrder()

        list.filter("", "")
        list.sort(PublicServersViewModel.Order.COUNTRY)
        assertThat(list.shown()).containsExactly("alpha", "Bravo", "Charlie").inOrder()
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
        val list = PublicServersViewModel(repository, fetcher, { false }).also { idleMainLooper() }
        assertThat(list.state.value).isEqualTo(PublicServersViewModel.State.DownloadFailed)

        list.retry()
        idleMainLooper()

        assertThat(list.shown()).containsExactly("Bravo", "alpha", "Charlie").inOrder()
    }

    @Test
    fun `a favourite is stored with the username given`() {
        val database = mockk<MumlaDatabase>(relaxed = true)
        val repository = MumlaRepository(database, Dispatchers.Unconfined)
        val list = PublicServersViewModel(repository, mockk(relaxed = true), { true })

        list.favourite(servers[0], "me")

        verify { database.addServer(match { it.username == "me" && it.host == "Bravo.example" }) }
    }
}
