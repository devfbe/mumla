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
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R

private const val HALF_OPEN = 0.5f

/**
 * The navigation drawer of [MumlaActivity]. [onItemSelected] receives the `DrawerAdapter.ITEM_*`
 * id of a tapped row; [serverName] is the connected server's name, or null while not connected.
 */
class MainDrawer(
    private val activity: AppCompatActivity,
    private val layout: DrawerLayout,
    list: RecyclerView,
    toolbar: Toolbar,
    private val serverName: () -> String?,
    private val onItemSelected: (Int) -> Unit,
) {
    private val adapter = DrawerAdapter(::onRowClicked)
    private val toggle = object :
        ActionBarDrawerToggle(activity, layout, toolbar, R.string.drawer_open, R.string.drawer_close) {
        override fun onDrawerClosed(drawerView: View) = activity.invalidateOptionsMenu()
        override fun onDrawerOpened(drawerView: View) = activity.invalidateOptionsMenu()

        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
            super.onDrawerSlide(drawerView, slideOffset)
            setStatusBarOverDrawer(slideOffset > HALF_OPEN)
        }
    }

    /**
     * The status bar's icons suit the dark app bar, or once the drawer is over it, the drawer's
     * surface, which is light in the light theme.
     */
    private fun setStatusBarOverDrawer(overDrawer: Boolean) {
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = overDrawer && !night
    }

    /** The donation row, if this flavor's resources define one. */
    private val donateRow: DrawerRow.Donate? = run {
        if (BuildConfig.FLAVOR != "foss") return@run null
        val resources = activity.resources
        // Looked up by name: only the foss flavor has them (kept for the shrinker in keep_foss.xml).
        val layoutId = resources.getIdentifier("list_drawer_headerdonate_foss", "xml", activity.packageName)
        val linkId = resources.getIdentifier("donate_link_foss", "string", activity.packageName)
        if (layoutId == 0 || linkId == 0) null else DrawerRow.Donate(layoutId, activity.getString(linkId))
    }

    init {
        list.adapter = adapter
        layout.addDrawerListener(toggle)
        refresh()
    }

    private fun rows(): List<DrawerRow> {
        val connectedServer = serverName()
        fun header(id: Int, title: Int) = DrawerRow.Header(id, activity.getString(title))
        fun item(id: Int, title: Int, icon: Int): DrawerRow.Item {
            val enabled = connectedServer != null || id !in DrawerAdapter.CONNECTED_ITEMS
            return DrawerRow.Item(id, activity.getString(title), icon, enabled)
        }
        return listOfNotNull(
            DrawerRow.Logo,
            donateRow,
            DrawerRow.Header(
                DrawerAdapter.HEADER_CONNECTED_SERVER,
                connectedServer ?: activity.getString(R.string.drawer_not_connected),
            ),
            item(DrawerAdapter.ITEM_SERVER, R.string.drawer_server, R.drawable.ic_action_channels),
            item(DrawerAdapter.ITEM_PINNED_CHANNELS, R.string.drawer_pinned, R.drawable.ic_action_comment),
            item(DrawerAdapter.ITEM_INFO, R.string.information, R.drawable.ic_action_info_dark),
            item(DrawerAdapter.ITEM_ACCESS_TOKENS, R.string.drawer_tokens, R.drawable.ic_action_save),
            header(DrawerAdapter.HEADER_SERVERS, R.string.drawer_header_servers),
            item(DrawerAdapter.ITEM_FAVOURITES, R.string.drawer_favorites, R.drawable.ic_action_favourite_on),
            item(DrawerAdapter.ITEM_PUBLIC, R.string.drawer_public, R.drawable.ic_action_search),
            header(DrawerAdapter.HEADER_GENERAL, R.string.general),
            item(DrawerAdapter.ITEM_SETTINGS, R.string.action_settings, R.drawable.ic_action_settings),
        )
    }

    private fun onRowClicked(row: DrawerRow) {
        layout.closeDrawers()
        when (row) {
            is DrawerRow.Item -> onItemSelected(row.id)
            is DrawerRow.Donate -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.link)))
            else -> Unit
        }
    }

    /** The title of the item with [id]. */
    fun title(id: Int): String? = adapter.currentList.filterIsInstance<DrawerRow.Item>().find { it.id == id }?.title

    /** Updates the rows, which depend on the connection. */
    fun refresh() = adapter.submitList(rows())

    fun syncState() = toggle.syncState()

    fun onConfigurationChanged(newConfig: Configuration) = toggle.onConfigurationChanged(newConfig)

    fun onOptionsItemSelected(item: MenuItem): Boolean = toggle.onOptionsItemSelected(item)
}
