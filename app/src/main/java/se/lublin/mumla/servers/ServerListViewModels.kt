/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.servers

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.PublicServer

/** Where a server answers pings; servers at one address share a reply. */
data class ServerAddress(val host: String, val port: Int)

val Server.address: ServerAddress get() = ServerAddress(host, port)

/**
 * The ping replies of a server list, by address. Each address is pinged at most once at a time, on
 * a shared bounded dispatcher, and only while [allowed] says so: never over Tor, which the UDP ping
 * would bypass. Main thread.
 */
class ServerPings(
    private val scope: CoroutineScope,
    val allowed: () -> Boolean,
    private val pinger: ServerPinger = ServerPinger(),
    private val dispatcher: CoroutineDispatcher = PING_DISPATCHER,
) {
    private val mutableReplies = MutableStateFlow<Map<ServerAddress, ServerInfoResponse>>(emptyMap())
    val replies: StateFlow<Map<ServerAddress, ServerInfoResponse>> = mutableReplies.asStateFlow()

    private val inFlight = HashSet<ServerAddress>()

    /** Pings [server] unless its address answered already or is being pinged. */
    fun request(server: Server) {
        val address = server.address
        if (!allowed() || address in mutableReplies.value || !inFlight.add(address)) return
        scope.launch {
            val reply = withContext(dispatcher) { pinger.ping(server) }
            inFlight.remove(address)
            mutableReplies.update { it + (address to reply) }
        }
    }

    /** Pings [server] now, without keeping the reply; for matching. */
    suspend fun ping(server: Server): ServerInfoResponse = withContext(dispatcher) { pinger.ping(server) }

    private companion object {
        const val MAX_CONCURRENT_PINGS = 16

        /** Shared by every server list, so all of them together never exceed the ping bound. */
        val PING_DISPATCHER: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PINGS)
    }
}

/** Whether pings may go out: not while connections go through Tor. */
private fun pingsAllowed(app: Application): () -> Boolean = { !Settings.getInstance(app).isTorEnabled }

/** The favourite servers, read from the database off the main thread, with their pings. Main thread. */
class FavouriteServersViewModel(
    private val repository: MumlaRepository,
    pingsAllowed: () -> Boolean,
    pinger: ServerPinger = ServerPinger(),
) : ViewModel() {
    val pings = ServerPings(viewModelScope, pingsAllowed, pinger)

    private val mutableServers = MutableStateFlow<List<Server>?>(null)

    /** The stored servers; null until read. */
    val servers: StateFlow<List<Server>?> = mutableServers.asStateFlow()

    /** Reads the servers again, e.g. after an edit elsewhere. */
    fun reload() {
        viewModelScope.launch { mutableServers.value = repository.io { getServers() } }
    }

    /** Removes [server] from the list at once and from the database in the background. */
    fun delete(server: Server) {
        mutableServers.update { it?.minus(server) }
        repository.launchIo { removeServer(server) }
    }

    companion object {
        fun create(app: Application) = FavouriteServersViewModel(MumlaRepository.get(app), pingsAllowed(app))
    }
}

/**
 * The public server list: downloaded once (never over Tor, which the download would bypass),
 * filtered and sorted as the user asks (kept in [savedState]), pinged, matched and favourited. The
 * list is arranged on [arrangeDispatcher]. Main thread.
 */
class PublicServersViewModel(
    private val repository: MumlaRepository,
    private val fetcher: PublicServerFetcher,
    private val torEnabled: () -> Boolean,
    private val savedState: SavedStateHandle,
    pinger: ServerPinger = ServerPinger(),
    arrangeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** How far the list got. */
    sealed interface State {
        data object Loading : State

        /** Over Tor the list is not downloaded. */
        data object TorBlocked : State

        data object DownloadFailed : State

        /** The [servers] the filter lets through, in its order, and all the downloaded ones' [countries]. */
        data class Shown(val servers: List<PublicServer>, val countries: List<String>) : State
    }

    val pings = ServerPings(viewModelScope, { !torEnabled() }, pinger)

    private val mutableFilter = MutableStateFlow(
        PublicServerFilter(
            query = savedState[KEY_QUERY] ?: "",
            countries = savedState.get<ArrayList<String>>(KEY_COUNTRIES).orEmpty().toSet(),
            sort = savedState.get<String>(KEY_SORT)?.let(PublicServerSort::valueOf) ?: PublicServerSort.USERS,
        ),
    )
    val filter: StateFlow<PublicServerFilter> = mutableFilter.asStateFlow()

    /** The download's state; [State.Shown] holds every server here, unfiltered. */
    private val download = MutableStateFlow<State>(State.Loading)

    val state: StateFlow<State> = combine(download, mutableFilter, pings.replies) { download, filter, replies ->
        when (download) {
            is State.Shown -> download.copy(servers = arrangePublicServers(download.servers, filter, replies))
            else -> download
        }
    }.flowOn(arrangeDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, State.Loading)

    init {
        load()
    }

    /** Downloads the list again, e.g. after it failed. */
    fun retry() = load()

    private fun load() {
        if (torEnabled()) {
            download.value = State.TorBlocked
        } else {
            download.value = State.Loading
            viewModelScope.launch {
                val servers = fetcher.fetch()
                download.value = if (servers == null) State.DownloadFailed else State.Shown(servers, countriesOf(servers))
            }
        }
    }

    private val shown: List<PublicServer> get() = (state.value as? State.Shown)?.servers.orEmpty()

    /** The shown entry of [server], or null. */
    fun shownEntryOf(server: Server): PublicServer? = shown.firstOrNull { it.server == server }

    fun setQuery(query: String) = updateFilter { it.copy(query = query) }

    fun setCountry(country: String, selected: Boolean) =
        updateFilter { it.copy(countries = if (selected) it.countries + country else it.countries - country) }

    /** Shows the servers of every country again. */
    fun clearCountries() = updateFilter { it.copy(countries = emptySet()) }

    fun setSort(sort: PublicServerSort) = updateFilter { it.copy(sort = sort) }

    private fun updateFilter(change: (PublicServerFilter) -> PublicServerFilter) {
        val filter = mutableFilter.updateAndGet(change)
        savedState[KEY_QUERY] = filter.query
        savedState[KEY_COUNTRIES] = ArrayList(filter.countries)
        savedState[KEY_SORT] = filter.sort.name
    }

    /** An empty, nearby server among those shown, in [countryCode] (anywhere when null); null if none. */
    suspend fun match(countryCode: String?): ServerInfoResponse? =
        matchServer(shown, countryCode, ping = { pings.ping(it.server) })

    /** Stores [server] as a favourite, logging in as [username]. */
    fun favourite(server: PublicServer, username: String) {
        repository.launchIo { addServer(server.server.copy(username = username)) }
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_COUNTRIES = "countries"
        private const val KEY_SORT = "sort"

        fun create(
            app: Application,
            fetcher: PublicServerFetcher,
            pinger: ServerPinger,
            savedState: SavedStateHandle,
        ) = PublicServersViewModel(
            MumlaRepository.get(app), fetcher, { Settings.getInstance(app).isTorEnabled }, savedState, pinger,
        )
    }
}
