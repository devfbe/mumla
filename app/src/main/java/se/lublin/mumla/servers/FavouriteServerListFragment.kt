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
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.databinding.FragmentServerListBinding
import se.lublin.mumla.util.appViewModels
import se.lublin.mumla.ui.ServerRequest
import se.lublin.mumla.ui.ConnectRequests
import se.lublin.mumla.ui.showConfirmDialog

/**
 * Displays the favourite servers, and lets the user connect to, add and edit them. Without any, it
 * offers to add one or to browse the public servers, which it asks the activity for with a
 * fragment result under [REQUEST_BROWSE_PUBLIC].
 */
class FavouriteServerListFragment :
    Fragment(),
    FavouriteServerAdapter.FavouriteServerAdapterMenuListener {

    private val connectRequests: ConnectRequests by activityViewModels()
    private val favourites by appViewModels(FavouriteServersViewModel::create)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentServerListBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentServerListBinding.bind(view)
        setUpServerGrid(binding.serverListGrid)
        binding.serverListGrid.updatePadding(
            bottom = binding.serverListGrid.paddingBottom +
                resources.getDimensionPixelSize(R.dimen.server_list_fab_clearance),
        )
        binding.serverListAdd.setOnClickListener { showEditDialog(null, ServerEditFragment.Mode.ADD) }
        binding.serverListEmptyAdd.setOnClickListener { showEditDialog(null, ServerEditFragment.Mode.ADD) }
        binding.serverListEmptyBrowse.setOnClickListener { setFragmentResult(REQUEST_BROWSE_PUBLIC, Bundle.EMPTY) }
        val adapter = FavouriteServerAdapter(this, favourites.pings) {
            connectRequests.request(ServerRequest.Favourite(it))
        }
        // As the platform grid's empty view: shown until there are servers to show, instead of the add button.
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = showEmpty()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = showEmpty()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = showEmpty()

            fun showEmpty() = showEmptyState(binding, adapter.itemCount == 0)
        })
        showEmptyState(binding, true)
        binding.serverListGrid.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { favourites.pings.replies.collect(adapter::setReplies) }
                favourites.servers.collect { it?.let(adapter::submitList) }
            }
        }
    }

    private fun showEmptyState(binding: FragmentServerListBinding, empty: Boolean) {
        binding.serverListGridEmpty.isVisible = empty
        binding.serverListAdd.isVisible = !empty
    }

    override fun onResume() {
        super.onResume()
        // Edits are made elsewhere and stored by the activity.
        favourites.reload()
    }

    override fun editServer(server: Server) {
        showEditDialog(server, ServerEditFragment.Mode.EDIT)
    }

    private fun showEditDialog(server: Server?, mode: ServerEditFragment.Mode) {
        // Shown by the activity's fragment manager, where MumlaActivity takes the result.
        ServerEditFragment.newInstance(server, mode).show(parentFragmentManager, "serverInfo")
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
            favourites.delete(server)
        }
    }

    companion object {
        const val REQUEST_BROWSE_PUBLIC = "browse_public_servers"
    }
}
