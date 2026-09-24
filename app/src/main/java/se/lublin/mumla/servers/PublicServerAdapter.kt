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
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.db.PublicServer
import java.util.Locale

class PublicServerAdapter(
    context: Context,
    servers: MutableList<PublicServer>,
    private val listener: PublicServerAdapterMenuListener,
    scope: CoroutineScope,
) : ServerAdapter<PublicServer>(context, R.layout.public_server_list_row, servers, scope) {

    private val unfilteredServers = ArrayList(servers)

    /** Shows only servers whose upper-cased name and country contain the given queries. */
    fun filter(queryName: String, queryCountry: String) {
        clear()
        for (server in unfilteredServers) {
            val name = server.name?.uppercase(Locale.US) ?: ""
            val country = server.country?.uppercase(Locale.US) ?: ""
            if (name.contains(queryName) && country.contains(queryCountry)) add(server)
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        view.findViewById<TextView>(R.id.server_row_location).text = getItem(position)!!.country
        return view
    }

    override val popupMenuResource: Int get() = R.menu.popup_public_server

    override fun onPopupItemClick(server: Server, menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_server_favourite) return false
        listener.favouriteServer(server)
        return true
    }

    interface PublicServerAdapterMenuListener {
        fun favouriteServer(server: Server)
    }
}
