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
package se.lublin.mumla.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.collectEvents

/**
 * The [IMumlaService] of the activity, shared with its fragments: set while the activity has the
 * service bound, null otherwise. Also carries the fragments' requests to connect to a server,
 * which the activity carries out.
 */
class ServiceViewModel : ViewModel() {
    private val bound = MutableStateFlow<IMumlaService?>(null)
    private val serverRequests = Channel<ServerRequest>(Channel.UNLIMITED)

    val service: StateFlow<IMumlaService?> = bound.asStateFlow()

    /** Whether the bound service has a synchronized session; false while unbound. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isConnected: Flow<Boolean> = bound
        .flatMapLatest { service -> service?.sessionState?.map { it is SessionState.Connected } ?: flowOf(false) }
        .distinctUntilChanged()

    /** The requests of [requestConnect], each delivered once. */
    val connectRequests: Flow<ServerRequest> = serverRequests.receiveAsFlow()

    /** Publishes the service the activity bound, or null once it unbound it. */
    fun attach(service: IMumlaService?) {
        bound.value = service
    }

    fun requestConnect(request: ServerRequest) {
        serverRequests.trySend(request)
    }
}

/** A server a fragment asks the activity to connect to. */
sealed interface ServerRequest {
    data class Favourite(val server: Server) : ServerRequest

    /** A public server, for which the user is asked a username first. */
    data class Public(val server: PublicServer) : ServerRequest
}

/** Receives the bound service of a [ServiceViewModel]; every call on the main thread. */
interface ServiceClient {
    /** A service was bound; also called on [bindClient] if one already is. */
    fun onServiceBound(service: IMumlaService) = Unit

    /** The service passed to [onServiceBound] was unbound, or the client stopped listening. */
    fun onServiceUnbound() = Unit

    /** An event of the bound service. */
    fun onServiceEvent(event: HumlaEvent) = Unit
}

/**
 * Feeds [client] the bound service and its events until [owner] is destroyed. Events emitted
 * during [ServiceClient.onServiceBound] are not missed.
 */
fun ServiceViewModel.bindClient(owner: LifecycleOwner, client: ServiceClient): Job {
    var current: IMumlaService? = null
    var events: Job? = null
    fun unbind() {
        if (current == null) return
        events?.cancel()
        events = null
        current = null
        client.onServiceUnbound()
    }
    return owner.lifecycleScope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
        try {
            service.collect { service ->
                if (service === current) return@collect
                unbind()
                if (service != null) {
                    current = service
                    events = collectEvents(this, service, client::onServiceEvent)
                    client.onServiceBound(service)
                }
            }
        } finally {
            unbind()
        }
    }
}
