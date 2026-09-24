/*
 * Copyright (C) 2026 The Mumla Authors
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
package se.lublin.mumla.app

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.MenuItem
import android.view.View
import android.widget.ListView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R

/**
 * The navigation drawer of [MumlaActivity]. [onItemSelected] receives the `DrawerAdapter.ITEM_*`
 * id of a tapped row; [serverName] is the connected server's name, or null while not connected.
 */
class MainDrawer(
    private val activity: AppCompatActivity,
    toolbar: Toolbar,
    private val serverName: () -> String?,
    private val onItemSelected: (Int) -> Unit,
) : DrawerAdapter.DrawerDataProvider {

    private val layout: DrawerLayout = activity.findViewById(R.id.drawer_layout)
    private val adapter = DrawerAdapter(activity, this)
    private val toggle = object :
        ActionBarDrawerToggle(activity, layout, toolbar, R.string.drawer_open, R.string.drawer_close) {
        override fun onDrawerClosed(drawerView: View) = activity.invalidateOptionsMenu()
        override fun onDrawerOpened(drawerView: View) = activity.invalidateOptionsMenu()
    }

    init {
        val list = activity.findViewById<ListView>(R.id.left_drawer)
        list.addHeaderView(activity.layoutInflater.inflate(R.layout.list_drawer_headerlogo, list, false), null, false)
        if (BuildConfig.FLAVOR == "foss") addDonateRow(list)
        list.setOnItemClickListener { _, _, _, id ->
            layout.closeDrawers()
            onItemSelected(id.toInt())
        }
        list.adapter = adapter
        layout.addDrawerListener(toggle)
    }

    /** The donation row, if this flavor's resources define one. */
    private fun addDonateRow(list: ListView) {
        val resources = activity.resources
        val layoutId = resources.getIdentifier("list_drawer_headerdonate_foss", "xml", activity.packageName)
        val linkId = resources.getIdentifier("donate_link_foss", "string", activity.packageName)
        if (layoutId == 0 || linkId == 0) return
        val row = activity.layoutInflater.inflate(layoutId, list, false)
        list.addHeaderView(row, null, true)
        row.setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(linkId))))
            layout.closeDrawers()
        }
    }

    /** The title of the row with [id]. */
    fun title(id: Int): String? = adapter.getItemWithId(id)?.title

    /** Redraws the rows, which depend on the connection. */
    fun refresh() = adapter.notifyDataSetChanged()

    fun syncState() = toggle.syncState()

    fun onConfigurationChanged(newConfig: Configuration) = toggle.onConfigurationChanged(newConfig)

    fun onOptionsItemSelected(item: MenuItem): Boolean = toggle.onOptionsItemSelected(item)

    override fun isConnected(): Boolean = serverName() != null

    override fun getConnectedServerName(): String = serverName().orEmpty()
}
