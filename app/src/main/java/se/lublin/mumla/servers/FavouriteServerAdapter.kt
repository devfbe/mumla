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

import android.view.MenuItem
import se.lublin.humla.model.Server
import se.lublin.mumla.R

/** The favourite servers' cards, with edit, share and delete in their menu. */
class FavouriteServerAdapter(
    private val listener: FavouriteServerAdapterMenuListener,
    pings: ServerPings,
    onServerClick: (Server) -> Unit,
) : ServerAdapter<Server>(pings, onServerClick, { it }) {

    override val rowLayout: Int get() = R.layout.server_list_row

    override val popupMenuResource: Int get() = R.menu.popup_favourite_server

    override fun onPopupItemClick(server: Server, menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_server_edit -> listener.editServer(server)
            R.id.menu_server_share -> listener.shareServer(server)
            R.id.menu_server_delete -> listener.deleteServer(server)
            else -> return false
        }
        return true
    }

    interface FavouriteServerAdapterMenuListener {
        fun editServer(server: Server)
        fun shareServer(server: Server)
        fun deleteServer(server: Server)
    }
}
