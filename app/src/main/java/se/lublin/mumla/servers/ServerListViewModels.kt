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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.PublicServer
import java.util.Locale

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
 * filtered and sorted as the user asks, pinged, matched and favourited. Main thread.
 */
class PublicServersViewModel(
    private val repository: MumlaRepository,
    private val fetcher: PublicServerFetcher,
    private val torEnabled: () -> Boolean,
    pinger: ServerPinger = ServerPinger(),
) : ViewModel() {

    /** How far the list got. */
    sealed interface State {
        data object Loading : State

        /** Over Tor the list is not downloaded. */
        data object TorBlocked : State

        data object DownloadFailed : State

        data class Shown(val servers: List<PublicServer>) : State
    }

    enum class Order { NAME, COUNTRY }

    val pings = ServerPings(viewModelScope, { !torEnabled() }, pinger)

    private val mutableState = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var all: List<PublicServer> = emptyList()

    init {
        load()
    }

    /** Downloads the list again, e.g. after it failed. */
    fun retry() = load()

    private fun load() {
        if (torEnabled()) {
            mutableState.value = State.TorBlocked
        } else {
            mutableState.value = State.Loading
            viewModelScope.launch {
                val servers = fetcher.fetch()
                if (servers != null) all = servers
                mutableState.value = if (servers == null) State.DownloadFailed else State.Shown(servers)
            }
        }
    }

    private val shown: List<PublicServer> get() = (mutableState.value as? State.Shown)?.servers.orEmpty()

    /** The shown entry of [server], or null. */
    fun shownEntryOf(server: Server): PublicServer? = shown.firstOrNull { it.server == server }

    /** Shows only servers whose name and country contain the queries, ignoring case, in list order. */
    fun filter(name: String, country: String) {
        if (mutableState.value !is State.Shown) return
        val nameQuery = name.uppercase(Locale.US)
        val countryQuery = country.uppercase(Locale.US)
        mutableState.value = State.Shown(
            all.filter {
                it.name.uppercase(Locale.US).contains(nameQuery) &&
                    it.country.orEmpty().uppercase(Locale.US).contains(countryQuery)
            },
        )
    }

    fun sort(order: Order) {
        if (mutableState.value !is State.Shown) return
        mutableState.value = State.Shown(shown.sortedWith(if (order == Order.NAME) NAME_ORDER else COUNTRY_ORDER))
    }

    /** An empty, nearby server among those shown, in [countryCode] (anywhere when null); null if none. */
    suspend fun match(countryCode: String?): ServerInfoResponse? =
        matchServer(shown, countryCode, ping = { pings.ping(it.server) })

    /** Stores [server] as a favourite, logging in as [username]. */
    fun favourite(server: PublicServer, username: String) {
        repository.launchIo { addServer(server.server.copy(username = username)) }
    }

    companion object {
        private val NAME_ORDER = Comparator<PublicServer> { lhs, rhs -> lhs.name.compareTo(rhs.name) }

        private val COUNTRY_ORDER = Comparator<PublicServer> { lhs, rhs ->
            when {
                rhs.country == null -> -1
                lhs.country == null -> 1
                else -> lhs.country.compareTo(rhs.country)
            }
        }

        fun create(app: Application, fetcher: PublicServerFetcher) = PublicServersViewModel(
            MumlaRepository.get(app), fetcher, { Settings.getInstance(app).isTorEnabled },
        )
    }
}
