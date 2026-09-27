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

import se.lublin.mumla.db.PublicServer
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** How the public servers are ordered; both by their ping replies, the unanswered last. */
enum class PublicServerSort {
    /** The fullest first. */
    USERS,

    /** The fastest first. */
    PING,
}

/**
 * What the public server list shows: servers whose name or country contains [query] (ignoring case
 * and diacritics), in one of [countries] (any when empty), in [sort] order.
 */
data class PublicServerFilter(
    val query: String = "",
    val countries: Set<String> = emptySet(),
    val sort: PublicServerSort = PublicServerSort.USERS,
)

/** The [servers] [filter] lets through, in its order by their [replies]; ties keep list order. */
fun arrangePublicServers(
    servers: List<PublicServer>,
    filter: PublicServerFilter,
    replies: Map<ServerAddress, ServerInfoResponse>,
): List<PublicServer> {
    val query = filter.query.trim().folded()
    val matching = servers.filter { server ->
        (filter.countries.isEmpty() || server.country in filter.countries) &&
            (server.name.folded().contains(query) || server.country.orEmpty().folded().contains(query))
    }
    fun answer(server: PublicServer) = replies[server.server.address]?.takeUnless { it.isDummy }
    val key: (PublicServer) -> Int? = when (filter.sort) {
        PublicServerSort.USERS -> { server -> answer(server)?.currentUsers?.unaryMinus() }
        PublicServerSort.PING -> { server -> answer(server)?.latency }
    }
    return matching.sortedWith(compareBy(nullsLast(), key))
}

/** The countries [servers] are in, each once, in alphabetical order. */
fun countriesOf(servers: List<PublicServer>): List<String> =
    servers.mapNotNull { it.country?.takeIf(String::isNotBlank) }.distinct().sortedWith(Collator.getInstance())

private val DIACRITICS = Regex("\\p{Mn}+")

private fun String.folded(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT)
