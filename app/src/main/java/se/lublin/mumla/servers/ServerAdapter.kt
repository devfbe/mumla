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
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * Server cards with a live ping status. Each address is pinged at most once at a time, on a shared
 * bounded dispatcher, and only while [scope] is active and [pingsAllowed] says so (never over Tor,
 * as the UDP ping would bypass it). A tapped card goes to [onServerClick]. Main thread only.
 */
@Suppress("LongParameterList") // The ping collaborators are injectable for tests.
abstract class ServerAdapter<E : Server>(
    context: Context,
    private val scope: CoroutineScope,
    private val onServerClick: (E) -> Unit,
    private val pinger: ServerPinger = ServerPinger(),
    private val pingDispatcher: CoroutineDispatcher = PING_DISPATCHER,
    private val pingsAllowed: () -> Boolean = { !Settings.getInstance(context).isTorEnabled },
) : ListAdapter<E, ServerAdapter.ServerViewHolder>(ServerDiff()) {

    /** Ping results by address: servers at the same address share one. */
    private val responses = HashMap<Address, ServerInfoResponse>()
    private val inFlight = HashSet<Address>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder =
        createHolder(LayoutInflater.from(parent.context), parent)

    /** Inflates a card into [parent] (without attaching it). */
    protected abstract fun createHolder(inflater: LayoutInflater, parent: ViewGroup): ServerViewHolder

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = getItem(position)
        val context = holder.itemView.context
        val response = responses[server.address]
        holder.itemView.setOnClickListener { onServerClick(server) }
        holder.name.text = server.name
        holder.user?.text = server.username
        holder.address?.text = server.host + if (server.port == 0) "" else ":${server.port}"
        holder.more.setOnClickListener { onServerOptionsClick(server, it) }

        val pinging = response == null && pingsAllowed()
        val infoVisibility = if (pinging) View.INVISIBLE else View.VISIBLE
        holder.version.visibility = infoVisibility
        holder.users.visibility = infoVisibility
        holder.latency.visibility = infoVisibility
        holder.progress.visibility = if (pinging) View.VISIBLE else View.INVISIBLE

        when {
            pinging -> requestPing(server)
            response == null -> holder.showStatus(NO_STATUS)
            response.isDummy -> holder.showStatus(context.getString(R.string.offline))
            else -> {
                holder.version.text = "${context.getString(R.string.online)} (${response.versionString})"
                holder.users.text = "${response.currentUsers}/${response.maximumUsers}"
                holder.latency.text = "${response.latency}ms"
            }
        }
        onBindServer(holder, server)
    }

    /** Binds what a subclass's row shows beyond the common card. */
    protected open fun onBindServer(holder: ServerViewHolder, server: E) = Unit

    private fun requestPing(server: E) {
        val address = server.address
        if (!inFlight.add(address)) return
        scope.launch {
            val result = withContext(pingDispatcher) { pinger.ping(server) }
            inFlight.remove(address)
            responses[address] = result
            currentList.forEachIndexed { index, shown -> if (shown.address == address) notifyItemChanged(index) }
        }
    }

    private fun onServerOptionsClick(server: E, anchor: View) {
        PopupMenu(anchor.context, anchor).apply {
            inflate(popupMenuResource)
            setOnMenuItemClickListener { onPopupItemClick(server, it) }
            show()
        }
    }

    abstract val popupMenuResource: Int

    abstract fun onPopupItemClick(server: E, menuItem: MenuItem): Boolean

    /** The views of a server card; those only some cards have are null. */
    @Suppress("LongParameterList") // One per view.
    class ServerViewHolder(
        view: View,
        val name: TextView,
        val version: TextView,
        val users: TextView,
        val latency: TextView,
        val progress: ProgressBar,
        val more: ImageView,
        val user: TextView? = null,
        val address: TextView? = null,
        val location: TextView? = null,
    ) : RecyclerView.ViewHolder(view) {
        fun showStatus(status: String) {
            version.text = status
            users.text = ""
            latency.text = ""
        }
    }

    private data class Address(val host: String, val port: Int)

    private val Server.address get() = Address(host, port)

    /** The same object is the same row; a stored server is also the same row after a reload. */
    private class ServerDiff<E : Server> : DiffUtil.ItemCallback<E>() {
        override fun areItemsTheSame(oldItem: E, newItem: E): Boolean =
            oldItem === newItem || (oldItem.isSaved && oldItem.id == newItem.id)

        override fun areContentsTheSame(oldItem: E, newItem: E): Boolean =
            oldItem.name == newItem.name && oldItem.host == newItem.host && oldItem.port == newItem.port &&
                oldItem.username == newItem.username
    }

    companion object {
        private const val MAX_CONCURRENT_PINGS = 16
        private const val NO_STATUS = "\u2013"

        /** Shared by every server list, so all of them together never exceed the ping bound. */
        private val PING_DISPATCHER: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PINGS)
    }
}

/**
 * Lays [list] out as a grid of as many server card columns as fit its window, with even spacing
 * between and around the cards.
 */
fun setUpServerGrid(list: RecyclerView) {
    val resources = list.resources
    val spacing = resources.getDimensionPixelSize(R.dimen.server_grid_spacing)
    val column = resources.getDimensionPixelSize(R.dimen.server_grid_column_width)
    val available = resources.displayMetrics.widthPixels - spacing
    list.layoutManager = GridLayoutManager(list.context, (available / (column + spacing)).coerceAtLeast(1))
    // Half the spacing around each card and inside the list's edges makes it whole everywhere.
    val half = spacing / 2
    list.setPadding(half, half, half, half)
    list.addItemDecoration(object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(half, half, half, half)
        }
    })
}
