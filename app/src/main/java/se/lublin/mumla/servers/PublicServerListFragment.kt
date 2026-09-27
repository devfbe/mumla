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
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.core.view.MenuProvider
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.FragmentPublicServerListBinding
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.ui.ConnectRequests
import se.lublin.mumla.ui.ServerRequest
import se.lublin.mumla.ui.showConfirmDialog
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Displays the public servers, which can be searched, narrowed to countries, sorted, matched,
 * favourited and joined.
 */
class PublicServerListFragment :
    Fragment(),
    PublicServerAdapter.PublicServerAdapterMenuListener,
    MenuProvider {

    private val connectRequests: ConnectRequests by activityViewModels()
    private val publicServers: PublicServersViewModel by viewModels {
        viewModelFactory {
            initializer {
                PublicServersViewModel.create(requireActivity().application, fetcher, pinger, createSavedStateHandle())
            }
        }
    }
    private var binding: FragmentPublicServerListBinding? = null

    @VisibleForTesting
    internal var fetcher = PublicServerFetcher()

    @VisibleForTesting
    internal var pinger = ServerPinger()

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
        setUpControls(binding)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { publicServers.pings.replies.collect(adapter::setReplies) }
                launch { publicServers.filter.collect { showFilter(binding, it) } }
                launch { announceCounts(binding) }
                publicServers.state.collect { show(binding, adapter, it) }
            }
        }
    }

    private fun setUpControls(binding: FragmentPublicServerListBinding) {
        binding.serverSearch.setText(publicServers.filter.value.query)
        binding.serverSearch.doAfterTextChanged { publicServers.setQuery(it.toString()) }
        binding.serverSort.addOnButtonCheckedListener { _, id, checked ->
            if (checked) publicServers.setSort(SORT_BUTTONS.entries.first { it.value == id }.key)
        }
        binding.serverListRetry.setOnClickListener { publicServers.retry() }
    }

    private fun show(
        binding: FragmentPublicServerListBinding,
        adapter: PublicServerAdapter,
        state: PublicServersViewModel.State,
    ) {
        val shown = state as? PublicServersViewModel.State.Shown
        binding.serverProgress.isVisible = state == PublicServersViewModel.State.Loading
        // The download would bypass Tor.
        binding.serverListTorNotice.isVisible = state == PublicServersViewModel.State.TorBlocked
        binding.serverListError.isVisible = state == PublicServersViewModel.State.DownloadFailed
        binding.serverListControls.isVisible = shown != null
        binding.serverListEmpty.isVisible = shown?.servers?.isEmpty() == true
        if (shown != null) {
            adapter.submitList(shown.servers)
            showCountries(binding, shown.countries)
        }
    }

    /** Offers "All" and a chip per country, once per list of [countries]. */
    private fun showCountries(binding: FragmentPublicServerListBinding, countries: List<String>) {
        val group = binding.serverCountryChips
        if (group.tag == countries) return
        group.tag = countries
        group.removeAllViews()
        group.addView(countryChip(binding, null, getString(R.string.public_server_country_all)))
        countries.forEach { group.addView(countryChip(binding, it, it)) }
        showFilter(binding, publicServers.filter.value)
    }

    /** A chip that selects [country], or all countries when it is null. */
    private fun countryChip(binding: FragmentPublicServerListBinding, country: String?, label: String): Chip {
        val group = binding.serverCountryChips
        val chip = layoutInflater.inflate(R.layout.public_server_country_chip, group, false) as Chip
        chip.text = label
        chip.tag = country
        chip.setOnClickListener {
            if (country == null) publicServers.clearCountries() else publicServers.setCountry(country, chip.isChecked)
            showFilter(binding, publicServers.filter.value)
        }
        return chip
    }

    private fun showFilter(binding: FragmentPublicServerListBinding, filter: PublicServerFilter) {
        for (chip in binding.serverCountryChips.children) {
            val country = chip.tag as String?
            (chip as Chip).isChecked = if (country == null) filter.countries.isEmpty() else country in filter.countries
        }
        binding.serverSort.check(SORT_BUTTONS.getValue(filter.sort))
    }

    /** Shows the number of servers shown once it settles, which screen readers announce politely. */
    @OptIn(FlowPreview::class)
    private suspend fun announceCounts(binding: FragmentPublicServerListBinding) {
        publicServers.state
            .mapNotNull { (it as? PublicServersViewModel.State.Shown)?.servers?.size }
            .distinctUntilChanged()
            .debounce(COUNT_SETTLE_TIME)
            .collect { count ->
                binding.serverListCount.text = resources.getQuantityString(R.plurals.public_server_count, count, count)
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
        val matching = menuItem.itemId == R.id.menu_match_server &&
            publicServers.state.value is PublicServersViewModel.State.Shown
        if (matching) showMatchDialog()
        return matching
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
        val server = response?.server?.let(publicServers::shownEntryOf)
        if (response != null && server != null) {
            val info = getString(
                R.string.server_match_info,
                server.name,
                server.server.host,
                server.server.port,
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

    private fun connect(server: PublicServer) {
        connectRequests.request(ServerRequest.Public(server))
    }

    private companion object {
        /** How long the shown count must hold before it is announced, so typing is not read out. */
        val COUNT_SETTLE_TIME = 700.milliseconds

        val SORT_BUTTONS = mapOf(
            PublicServerSort.USERS to R.id.server_sort_users,
            PublicServerSort.PING to R.id.server_sort_ping,
        )
    }
}
