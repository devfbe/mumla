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

import android.content.Context
import android.view.MenuItem
import kotlinx.coroutines.CoroutineScope
import se.lublin.mumla.R
import se.lublin.mumla.db.PublicServer
import java.util.Locale

/** The public servers' cards, which can be filtered and sorted, with "favourite" in their menu. */
class PublicServerAdapter(
    context: Context,
    private val servers: List<PublicServer>,
    private val listener: PublicServerAdapterMenuListener,
    scope: CoroutineScope,
    onServerClick: (PublicServer) -> Unit,
) : ServerAdapter<PublicServer>(context, scope, onServerClick) {

    /** The servers shown, as filtered and sorted; the list catches up with this asynchronously. */
    var shownServers: List<PublicServer> = servers
        private set(value) {
            field = value
            submitList(value)
        }

    init {
        submitList(servers)
    }

    /** Shows only servers whose upper-cased name and country contain the given queries, in list order. */
    fun filter(queryName: String, queryCountry: String) {
        shownServers = servers.filter { server ->
            server.name.uppercase(Locale.US).contains(queryName) &&
                server.country.orEmpty().uppercase(Locale.US).contains(queryCountry)
        }
    }

    fun sort(comparator: Comparator<PublicServer>) {
        shownServers = shownServers.sortedWith(comparator)
    }

    override val rowLayout: Int get() = R.layout.public_server_list_row

    override fun onBindServer(holder: ServerViewHolder, server: PublicServer) {
        holder.location?.text = server.country
    }

    override val popupMenuResource: Int get() = R.menu.popup_public_server

    override fun onPopupItemClick(server: PublicServer, menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_server_favourite) return false
        listener.favouriteServer(server)
        return true
    }

    fun interface PublicServerAdapterMenuListener {
        fun favouriteServer(server: PublicServer)
    }
}
