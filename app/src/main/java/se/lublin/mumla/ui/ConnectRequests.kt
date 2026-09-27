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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import se.lublin.humla.model.Server
import se.lublin.mumla.db.PublicServer

/** Carries the fragments' requests to connect to a server to the activity, which carries them out. */
class ConnectRequests : ViewModel() {
    private val requests = Channel<ServerRequest>(Channel.UNLIMITED)

    /** The requests of [request], each delivered once. */
    val requested: Flow<ServerRequest> = requests.receiveAsFlow()

    fun request(request: ServerRequest) {
        requests.trySend(request)
    }
}

/** A server a fragment asks the activity to connect to. */
sealed interface ServerRequest {
    data class Favourite(val server: Server) : ServerRequest

    /** A public server, for which the user is asked a username first. */
    data class Public(val server: PublicServer) : ServerRequest
}
