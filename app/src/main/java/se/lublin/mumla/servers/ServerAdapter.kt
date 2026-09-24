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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * Server cards with a live ping status. Each server is pinged at most once at a time, on a shared
 * bounded dispatcher, and only while [scope] is active and [pingsAllowed] says so (never over Tor,
 * as the UDP ping would bypass it). Must be used from the main thread.
 */
abstract class ServerAdapter<E : Server>(
    context: Context,
    private val viewResource: Int,
    servers: MutableList<E>,
    private val scope: CoroutineScope,
    private val pinger: ServerPinger = ServerPinger(),
    private val pingDispatcher: CoroutineDispatcher = PING_DISPATCHER,
    private val pingsAllowed: () -> Boolean = { !Settings.getInstance(context).isTorEnabled() },
) : ArrayAdapter<E>(context, 0, servers) {

    private val responses = HashMap<Server, ServerInfoResponse>()
    private val inFlight = HashSet<Server>()

    override fun getItemId(position: Int): Long = getItem(position)!!.id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(viewResource, parent, false)
        val server = getItem(position)!!
        val response = responses[server]

        view.findViewById<TextView>(R.id.server_row_name).text = server.name
        view.findViewById<TextView?>(R.id.server_row_user)?.text = server.username
        view.findViewById<TextView?>(R.id.server_row_address)?.text =
            server.host + if (server.port == 0) "" else ":${server.port}"
        view.findViewById<ImageView?>(R.id.server_row_more)?.let { more ->
            more.setOnClickListener { onServerOptionsClick(server, more) }
        }

        val versionText = view.findViewById<TextView>(R.id.server_row_version_status)
        val latencyText = view.findViewById<TextView>(R.id.server_row_latency)
        val usersText = view.findViewById<TextView>(R.id.server_row_usercount)
        val progress = view.findViewById<ProgressBar>(R.id.server_row_ping_progress)

        val pinging = response == null && pingsAllowed()
        val infoVisibility = if (pinging) View.INVISIBLE else View.VISIBLE
        versionText.visibility = infoVisibility
        usersText.visibility = infoVisibility
        latencyText.visibility = infoVisibility
        progress.visibility = if (pinging) View.VISIBLE else View.INVISIBLE

        when {
            pinging -> requestPing(server)
            response == null -> {
                versionText.text = NO_STATUS
                usersText.text = ""
                latencyText.text = ""
            }
            response.isDummy -> {
                versionText.setText(R.string.offline)
                usersText.text = ""
                latencyText.text = ""
            }
            else -> {
                versionText.text = "${context.getString(R.string.online)} (${response.versionString})"
                usersText.text = "${response.currentUsers}/${response.maximumUsers}"
                latencyText.text = "${response.latency}ms"
            }
        }
        return view
    }

    private fun requestPing(server: E) {
        if (!inFlight.add(server)) return
        scope.launch {
            val result = withContext(pingDispatcher) { pinger.ping(server) }
            inFlight.remove(server)
            responses[server] = result
            notifyDataSetChanged()
        }
    }

    private fun onServerOptionsClick(server: Server, anchor: View) {
        PopupMenu(context, anchor).apply {
            inflate(popupMenuResource)
            setOnMenuItemClickListener { onPopupItemClick(server, it) }
            show()
        }
    }

    abstract val popupMenuResource: Int

    abstract fun onPopupItemClick(server: Server, menuItem: MenuItem): Boolean

    companion object {
        private const val MAX_CONCURRENT_PINGS = 16
        private const val NO_STATUS = "\u2013"

        /** Shared by every server list, so all of them together never exceed the ping bound. */
        private val PING_DISPATCHER: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PINGS)
    }
}
