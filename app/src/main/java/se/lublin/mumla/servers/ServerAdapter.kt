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

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.model.Server
import se.lublin.mumla.R

/**
 * Server cards with the ping status of [pings]: a card without a reply asks for one while pings are
 * allowed, and [setReplies] repaints the cards whose reply arrived. A tapped card goes to
 * [onServerClick]. Main thread only.
 */
abstract class ServerAdapter<E : Any>(
    private val pings: ServerPings,
    private val onServerClick: (E) -> Unit,
    private val serverOf: (E) -> Server,
) : ListAdapter<E, ServerAdapter.ServerViewHolder>(ServerDiff(serverOf)) {

    private var replies: Map<ServerAddress, ServerInfoResponse> = emptyMap()

    /** Repaints the cards whose address has a new reply. */
    fun setReplies(replies: Map<ServerAddress, ServerInfoResponse>) {
        val previous = this.replies
        this.replies = replies
        currentList.forEachIndexed { index, item ->
            val address = serverOf(item).address
            if (previous[address] !== replies[address]) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder =
        ServerViewHolder(LayoutInflater.from(parent.context).inflate(rowLayout, parent, false))

    /** The card layout, with the `server_row_*` views [ServerViewHolder] looks up. */
    @get:LayoutRes
    protected abstract val rowLayout: Int

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val item = getItem(position)
        val server = serverOf(item)
        val context = holder.itemView.context
        val response = replies[server.address]
        holder.itemView.setOnClickListener { onServerClick(item) }
        holder.name.text = server.name
        holder.user?.text = server.username
        holder.address?.text = server.host + if (server.port == 0) "" else ":${server.port}"
        holder.more.setOnClickListener { onServerOptionsClick(item, it) }

        val pinging = response == null && pings.allowed()
        val infoVisibility = if (pinging) View.INVISIBLE else View.VISIBLE
        holder.version.visibility = infoVisibility
        holder.users.visibility = infoVisibility
        holder.latency.visibility = infoVisibility
        holder.progress.visibility = if (pinging) View.VISIBLE else View.INVISIBLE

        when {
            pinging -> pings.request(server)
            response == null -> holder.showStatus(NO_STATUS)
            response.isDummy -> holder.showStatus(context.getString(R.string.offline))
            else -> {
                holder.version.text = "${context.getString(R.string.online)} (${response.versionString})"
                holder.users.text = "${response.currentUsers}/${response.maximumUsers}"
                holder.latency.text = "${response.latency}ms"
            }
        }
        onBindServer(holder, item)
    }

    /** Binds what a subclass's row shows beyond the common card. */
    protected open fun onBindServer(holder: ServerViewHolder, server: E) = Unit

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
    class ServerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.server_row_name)
        val version: TextView = view.findViewById(R.id.server_row_version_status)
        val users: TextView = view.findViewById(R.id.server_row_usercount)
        val latency: TextView = view.findViewById(R.id.server_row_latency)
        val progress: ProgressBar = view.findViewById(R.id.server_row_ping_progress)
        val more: ImageView = view.findViewById(R.id.server_row_more)
        val user: TextView? = view.findViewById(R.id.server_row_user)
        val address: TextView? = view.findViewById(R.id.server_row_address)
        val location: TextView? = view.findViewById(R.id.server_row_location)

        fun showStatus(status: String) {
            version.text = status
            users.text = ""
            latency.text = ""
        }
    }

    /** The same object is the same row; a stored server is also the same row after a reload. */
    private class ServerDiff<E : Any>(private val serverOf: (E) -> Server) : DiffUtil.ItemCallback<E>() {
        override fun areItemsTheSame(oldItem: E, newItem: E): Boolean {
            val old = serverOf(oldItem)
            return oldItem === newItem || (old.isSaved && old.id == serverOf(newItem).id)
        }

        override fun areContentsTheSame(oldItem: E, newItem: E): Boolean {
            val old = serverOf(oldItem)
            val new = serverOf(newItem)
            return old.name == new.name && old.host == new.host && old.port == new.port && old.username == new.username
        }
    }

    private companion object {
        const val NO_STATUS = "\u2013"
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
