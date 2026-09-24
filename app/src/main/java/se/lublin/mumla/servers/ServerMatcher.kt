/*
 * Copyright (C) 2014 Andrew Comminos
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

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import se.lublin.humla.Constants
import se.lublin.mumla.db.PublicServer

/**
 * Finds an empty public server with low latency: pings up to [sampleSize] of [servers] (only
 * those in [countryCode] when it is given), at most [concurrency] at a time, and returns the
 * fastest reply from an empty server speaking our protocol version, or null if there is none.
 */
@OptIn(FlowPreview::class)
suspend fun matchServer(
    servers: List<PublicServer>,
    countryCode: String?,
    ping: suspend (PublicServer) -> ServerInfoResponse,
    sampleSize: Int = 20,
    concurrency: Int = 5,
): ServerInfoResponse? {
    val candidates = if (countryCode == null) servers else servers.filter { it.countryCode == countryCode }
    if (candidates.isEmpty()) return null
    return candidates.asFlow()
        .flatMapMerge(concurrency) { server -> flow { emit(ping(server)) } }
        .take(minOf(sampleSize, candidates.size))
        .filter { !it.isDummy && it.currentUsers == 0 && it.version == Constants.PROTOCOL_VERSION }
        .toList()
        .minByOrNull { it.latency }
}
