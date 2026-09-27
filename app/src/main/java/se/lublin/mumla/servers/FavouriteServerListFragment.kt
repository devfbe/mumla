/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.databinding.FragmentServerListBinding
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.ui.ServerRequest
import se.lublin.mumla.ui.ConnectRequests
import se.lublin.mumla.ui.showConfirmDialog

/** Displays the favourite servers, and lets the user connect to and edit them. */
@Suppress("TooManyFunctions") // Fragment, menu and card menu callbacks.
class FavouriteServerListFragment :
    Fragment(),
    FavouriteServerAdapter.FavouriteServerAdapterMenuListener,
    MenuProvider {

    private val connectRequests: ConnectRequests by activityViewModels()
    private val repository get() = MumlaRepository.get(requireContext())
    private var serverAdapter: FavouriteServerAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentServerListBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentServerListBinding.bind(view)
        setUpServerGrid(binding.serverListGrid)
        val adapter = FavouriteServerAdapter(requireContext(), this, viewLifecycleOwner.lifecycleScope) {
            connectRequests.request(ServerRequest.Favourite(it))
        }
        // As the platform grid's empty view: shown until there are servers to show.
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = showEmpty()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = showEmpty()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = showEmpty()

            fun showEmpty() {
                binding.serverListGridEmpty.isVisible = adapter.itemCount == 0
            }
        })
        binding.serverListGridEmpty.isVisible = true
        binding.serverListGrid.adapter = adapter
        serverAdapter = adapter
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        serverAdapter = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        updateServers()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_server_list, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_add_server_item -> showEditDialog(null, ServerEditFragment.Action.ADD, false)
            R.id.menu_quick_connect -> showEditDialog(null, ServerEditFragment.Action.CONNECT, true)
            else -> return false
        }
        return true
    }

    override fun editServer(server: Server) {
        showEditDialog(server, ServerEditFragment.Action.EDIT, false)
    }

    private fun showEditDialog(server: Server?, action: ServerEditFragment.Action, ignoreTitle: Boolean) {
        // Shown by the activity's fragment manager, where MumlaActivity takes the result.
        ServerEditFragment.newInstance(server, action, ignoreTitle).show(parentFragmentManager, "serverInfo")
    }

    override fun shareServer(server: Server) {
        val serverUrl = "mumble://${server.host}${if (server.port == 0) "" else ":${server.port}"}/"
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.shareMessage, serverUrl))
            .setType("text/plain")
        startActivity(intent)
    }

    override fun deleteServer(server: Server) {
        requireContext().showConfirmDialog(getString(R.string.confirm_delete_server), R.string.delete) {
            serverAdapter?.let { adapter -> adapter.submitList(adapter.currentList - server) }
            lifecycleScope.launch { repository.io { removeServer(server) } }
        }
    }

    private fun updateServers() {
        viewLifecycleOwner.lifecycleScope.launch {
            val servers = repository.io { getServers() }
            serverAdapter?.submitList(servers)
        }
    }
}
