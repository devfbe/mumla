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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.PublicServer
import java.util.Locale

/** Displays the public servers, which can be sorted, filtered, matched, favourited and joined. */
class PublicServerListFragment :
    Fragment(),
    AdapterView.OnItemClickListener,
    PublicServerAdapter.PublicServerAdapterMenuListener,
    MenuProvider {

    private lateinit var connectHandler: FavouriteServerListFragment.ServerConnectHandler
    private lateinit var databaseProvider: DatabaseProvider
    private var servers: MutableList<PublicServer> = mutableListOf()
    private var serverGrid: GridView? = null
    private var serverProgress: ProgressBar? = null
    private var serverAdapter: PublicServerAdapter? = null
    private val pinger = ServerPinger()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        connectHandler = context as? FavouriteServerListFragment.ServerConnectHandler
            ?: throw ClassCastException("$context must implement ServerConnectHandler")
        databaseProvider = context as? DatabaseProvider
            ?: throw ClassCastException("$context must implement DatabaseProvider")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_public_server_list, container, false)
        serverGrid = view.findViewById<GridView>(R.id.server_list_grid).also { grid ->
            grid.onItemClickListener = this
            serverAdapter?.let { grid.adapter = it }
        }
        serverProgress = view.findViewById<ProgressBar>(R.id.serverProgress).also {
            it.visibility = if (serverAdapter == null) View.VISIBLE else View.GONE
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        fillPublicList()
    }

    override fun onDestroyView() {
        serverGrid = null
        serverProgress = null
        super.onDestroyView()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_public_server_list, menu)
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

    override fun favouriteServer(server: Server) {
        val context = requireActivity()
        val settings = Settings.getInstance(context)
        val usernameField = EditText(context).apply { hint = settings.getDefaultUsername() }
        val horizontalPadding = resources.getDimension(R.dimen.padding_medium).toInt()
        val layout = FrameLayout(context).apply {
            addView(usernameField)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.addFavorite)
            .setView(layout)
            .setPositiveButton(R.string.add) { _, _ ->
                server.username = usernameField.text.toString().ifEmpty { settings.getDefaultUsername() }
                databaseProvider.database.addServer(server)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setServers(servers: List<PublicServer>) {
        this.servers = servers.toMutableList()
        serverProgress?.visibility = View.GONE
        val adapter = PublicServerAdapter(requireActivity(), this.servers, this, lifecycleScope)
        serverAdapter = adapter
        serverGrid?.adapter = adapter
    }

    private fun fillPublicList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = PublicServerFetcher().fetch()
            if (result == null) {
                Toast.makeText(requireContext(), R.string.error_fetching_servers, Toast.LENGTH_SHORT).show()
            } else {
                setServers(result)
            }
        }
    }

    private fun showMatchDialog() {
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.server_match)
            .setMessage(R.string.server_match_description)
            .setPositiveButton(R.string.search) { _, _ -> findOptimalServer(Locale.getDefault().country) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Looks for an empty, nearby server in [countryCode] (anywhere when null) and offers to join it. */
    private fun findOptimalServer(countryCode: String?) {
        val candidates = servers.toList()
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
                .setPositiveButton(R.string.connect) { _, _ -> connectHandler.connectToPublicServer(server) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.server_match_not_found)
                .setMessage(R.string.server_match_expand_country)
                .setPositiveButton(R.string.expand) { _, _ -> findOptimalServer(null) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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
        val dialogView = LayoutInflater.from(requireActivity()).inflate(R.layout.dialog_server_search, null)
        val nameText = dialogView.findViewById<EditText>(R.id.server_search_name)
        val countryText = dialogView.findViewById<EditText>(R.id.server_search_country)
        fun applyFilter() = adapter.filter(
            nameText.text.toString().uppercase(Locale.US),
            countryText.text.toString().uppercase(Locale.US),
        )

        val alertDialog = MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.search)
            .setView(dialogView)
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

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        serverAdapter?.getItem(position)?.let(connectHandler::connectToPublicServer)
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
