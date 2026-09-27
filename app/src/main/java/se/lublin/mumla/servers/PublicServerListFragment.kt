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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.DialogServerSearchBinding
import se.lublin.mumla.databinding.FragmentPublicServerListBinding
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.ui.ServerRequest
import se.lublin.mumla.ui.ConnectRequests
import se.lublin.mumla.ui.showConfirmDialog
import se.lublin.mumla.util.appViewModels
import java.util.Locale

/** Displays the public servers, which can be sorted, filtered, matched, favourited and joined. */
class PublicServerListFragment :
    Fragment(),
    PublicServerAdapter.PublicServerAdapterMenuListener,
    MenuProvider {

    private val connectRequests: ConnectRequests by activityViewModels()
    private val publicServers by appViewModels { PublicServersViewModel.create(it, fetcher) }
    private var binding: FragmentPublicServerListBinding? = null

    @VisibleForTesting
    internal var fetcher = PublicServerFetcher()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentPublicServerListBinding.inflate(inflater, container, false)
        this.binding = binding
        setUpServerGrid(binding.serverListGrid)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        val adapter = PublicServerAdapter(this, publicServers.pings, ::connect)
        binding.serverListGrid.adapter = adapter
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { publicServers.pings.replies.collect(adapter::setReplies) }
                publicServers.state.collect { show(binding, adapter, it) }
            }
        }
    }

    private fun show(
        binding: FragmentPublicServerListBinding,
        adapter: PublicServerAdapter,
        state: PublicServersViewModel.State,
    ) {
        binding.serverProgress.isVisible = state == PublicServersViewModel.State.Loading
        // The download would bypass Tor.
        binding.serverListTorNotice.isVisible = state == PublicServersViewModel.State.TorBlocked
        if (state is PublicServersViewModel.State.Shown) adapter.submitList(state.servers)
        if (state == PublicServersViewModel.State.DownloadFailed) {
            Toast.makeText(requireContext(), R.string.error_fetching_servers, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_public_server_list, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        // Matching pings servers directly over UDP, which Tor cannot carry.
        menu.findItem(R.id.menu_match_server)?.isVisible = !Settings.getInstance(requireContext()).isTorEnabled
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (publicServers.state.value !is PublicServersViewModel.State.Shown) return false
        when (menuItem.itemId) {
            R.id.menu_match_server -> showMatchDialog()
            R.id.menu_sort_server_item -> showSortDialog()
            R.id.menu_search_server_item -> showFilterDialog()
            else -> return false
        }
        return true
    }

    override fun favouriteServer(server: PublicServer) {
        val context = requireActivity()
        val settings = Settings.getInstance(context)
        val usernameField = EditText(context).apply { hint = settings.defaultUsername }
        val horizontalPadding = resources.getDimension(R.dimen.padding_medium).toInt()
        val layout = FrameLayout(context).apply {
            addView(usernameField)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.addFavorite)
            .setView(layout)
            .setPositiveButton(R.string.add) { _, _ ->
                publicServers.favourite(server, usernameField.text.toString().ifEmpty { settings.defaultUsername })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMatchDialog() {
        requireActivity().showConfirmDialog(
            getString(R.string.server_match_description),
            R.string.search,
            title = getString(R.string.server_match),
        ) { findOptimalServer(Locale.getDefault().country) }
    }

    /** Looks for an empty, nearby server in [countryCode] (anywhere when null) and offers to join it. */
    private fun findOptimalServer(countryCode: String?) {
        val progressDialog = MaterialAlertDialogBuilder(requireActivity())
            .setMessage(R.string.server_match_progress)
            .setCancelable(true)
            .create()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            val response = try {
                publicServers.match(countryCode)
            } finally {
                progressDialog.dismiss()
            }
            showMatchResult(response)
        }
        progressDialog.setOnCancelListener { job.cancel(CancellationException("match cancelled")) }
        progressDialog.show()
    }

    private fun showMatchResult(response: ServerInfoResponse?) {
        val server = response?.server as PublicServer?
        if (response != null && server != null) {
            val info = getString(
                R.string.server_match_info,
                server.name,
                server.host,
                server.port,
                response.currentUsers,
                response.maximumUsers,
                response.versionString,
                server.country,
                response.latency,
            )
            requireActivity().showConfirmDialog(info, R.string.connect, getString(R.string.server_match_found)) {
                connect(server)
            }
        } else {
            requireActivity().showConfirmDialog(
                getString(R.string.server_match_expand_country),
                R.string.expand,
                title = getString(R.string.server_match_not_found),
            ) { findOptimalServer(null) }
        }
    }

    private fun showSortDialog() {
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.sortBy)
            .setItems(arrayOf(getString(R.string.name), getString(R.string.country))) { _, which ->
                when (which) {
                    SORT_NAME -> publicServers.sort(PublicServersViewModel.Order.NAME)
                    SORT_COUNTRY -> publicServers.sort(PublicServersViewModel.Order.COUNTRY)
                }
            }
            .show()
    }

    private fun showFilterDialog() {
        val dialog = DialogServerSearchBinding.inflate(layoutInflater)
        val nameText = dialog.serverSearchName
        val countryText = dialog.serverSearchCountry
        fun applyFilter() = publicServers.filter(nameText.text.toString(), countryText.text.toString())

        val alertDialog = MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.search)
            .setView(dialog.root)
            .setPositiveButton(R.string.search) { dialog, _ ->
                applyFilter()
                dialog.dismiss()
            }
            .create()

        for (field in listOf(nameText, countryText)) {
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH
            field.setOnEditorActionListener { _, _, _ ->
                applyFilter()
                alertDialog.dismiss()
                true
            }
        }
        nameText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                alertDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
        }
        alertDialog.show()
    }

    private fun connect(server: PublicServer) {
        connectRequests.request(ServerRequest.Public(server))
    }

    private companion object {
        const val SORT_NAME = 0
        const val SORT_COUNTRY = 1
    }
}
