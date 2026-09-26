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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.DialogServerSearchBinding
import se.lublin.mumla.databinding.FragmentPublicServerListBinding
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.ui.ServerRequest
import se.lublin.mumla.ui.ServiceViewModel
import se.lublin.mumla.ui.showConfirmDialog
import java.util.Locale

/** Displays the public servers, which can be sorted, filtered, matched, favourited and joined. */
class PublicServerListFragment :
    Fragment(),
    PublicServerAdapter.PublicServerAdapterMenuListener,
    MenuProvider {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private var binding: FragmentPublicServerListBinding? = null
    private var serverAdapter: PublicServerAdapter? = null
    private val pinger = ServerPinger()

    @VisibleForTesting
    internal var fetcher = PublicServerFetcher()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentPublicServerListBinding.inflate(inflater, container, false)
        this.binding = binding
        setUpServerGrid(binding.serverListGrid)
        binding.serverListGrid.adapter = serverAdapter
        binding.serverProgress.isVisible = serverAdapter == null
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        fillPublicList()
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
        val adapter = serverAdapter ?: return false
        when (menuItem.itemId) {
            R.id.menu_match_server -> showMatchDialog()
            R.id.menu_sort_server_item -> showSortDialog(adapter)
            R.id.menu_search_server_item -> showFilterDialog(adapter)
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
                server.username = usernameField.text.toString().ifEmpty { settings.defaultUsername }
                val repository = MumlaRepository.get(context)
                lifecycleScope.launch { repository.io { addServer(server) } }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setServers(servers: List<PublicServer>) {
        binding?.serverProgress?.isVisible = false
        val adapter = PublicServerAdapter(requireActivity(), servers, this, lifecycleScope, ::connect)
        serverAdapter = adapter
        binding?.serverListGrid?.adapter = adapter
    }

    private fun fillPublicList() {
        if (Settings.getInstance(requireContext()).isTorEnabled) {
            // The download would bypass Tor.
            binding?.serverProgress?.isVisible = false
            binding?.serverListTorNotice?.isVisible = true
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = fetcher.fetch()
            if (result == null) {
                Toast.makeText(requireContext(), R.string.error_fetching_servers, Toast.LENGTH_SHORT).show()
            } else {
                setServers(result)
            }
        }
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
        // The servers shown, as filtered and sorted.
        val candidates = serverAdapter?.shownServers.orEmpty()
        val progressDialog = MaterialAlertDialogBuilder(requireActivity())
            .setMessage(R.string.server_match_progress)
            .setCancelable(true)
            .create()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            val response = try {
                matchServer(candidates, countryCode, { withContext(Dispatchers.IO) { pinger.ping(it) } })
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
            MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.server_match_found)
                .setMessage(
                    getString(
                        R.string.server_match_info,
                        server.name,
                        server.host,
                        server.port,
                        response.currentUsers,
                        response.maximumUsers,
                        response.versionString,
                        server.country,
                        response.latency,
                    ),
                )
                .setPositiveButton(R.string.connect) { _, _ -> connect(server) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            requireActivity().showConfirmDialog(
                getString(R.string.server_match_expand_country),
                R.string.expand,
                title = getString(R.string.server_match_not_found),
            ) { findOptimalServer(null) }
        }
    }

    private fun showSortDialog(adapter: PublicServerAdapter) {
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.sortBy)
            .setItems(arrayOf(getString(R.string.name), getString(R.string.country))) { _, which ->
                when (which) {
                    SORT_NAME -> adapter.sort { lhs, rhs -> lhs.name.compareTo(rhs.name) }
                    SORT_COUNTRY -> adapter.sort(COUNTRY_ORDER)
                }
            }
            .show()
    }

    private fun showFilterDialog(adapter: PublicServerAdapter) {
        val dialog = DialogServerSearchBinding.inflate(layoutInflater)
        val nameText = dialog.serverSearchName
        val countryText = dialog.serverSearchCountry
        fun applyFilter() = adapter.filter(
            nameText.text.toString().uppercase(Locale.US),
            countryText.text.toString().uppercase(Locale.US),
        )

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
        serviceModel.requestConnect(ServerRequest.Public(server))
    }

    private companion object {
        const val SORT_NAME = 0
        const val SORT_COUNTRY = 1

        val COUNTRY_ORDER = Comparator<PublicServer> { lhs, rhs ->
            when {
                rhs.country == null -> -1
                lhs.country == null -> 1
                else -> lhs.country.compareTo(rhs.country)
            }
        }
    }
}
