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
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ServerListRowBinding

/** The favourite servers' cards, with edit, share and delete in their menu. */
class FavouriteServerAdapter(
    context: Context,
    private val listener: FavouriteServerAdapterMenuListener,
    scope: CoroutineScope,
    onServerClick: (Server) -> Unit,
) : ServerAdapter<Server>(context, scope, onServerClick) {

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): ServerViewHolder {
        val binding = ServerListRowBinding.inflate(inflater, parent, false)
        return ServerViewHolder(
            binding.root,
            name = binding.serverRowName,
            version = binding.serverRowVersionStatus,
            users = binding.serverRowUsercount,
            latency = binding.serverRowLatency,
            progress = binding.serverRowPingProgress,
            more = binding.serverRowMore,
            user = binding.serverRowUser,
            address = binding.serverRowAddress,
        )
    }

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
