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
package se.lublin.mumla.app

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ListDrawerHeaderBinding
import se.lublin.mumla.databinding.ListDrawerItemBinding

/** The rows of the navigation drawer; a tap on an enabled item or the donation row goes to [onClick]. */
class DrawerAdapter(private val onClick: (DrawerRow) -> Unit) :
    ListAdapter<DrawerRow, DrawerAdapter.RowHolder>(
        // A dozen rows: diffed on the calling thread rather than a background one.
        AsyncDifferConfig.Builder(DIFF).setBackgroundThreadExecutor(Runnable::run).build(),
    ) {

    override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
        DrawerRow.Logo -> R.layout.list_drawer_headerlogo
        is DrawerRow.Donate -> row.layout
        is DrawerRow.Header -> R.layout.list_drawer_header
        is DrawerRow.Item -> R.layout.list_drawer_item
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = getItem(position)
        val view = holder.itemView
        when (row) {
            DrawerRow.Logo -> Unit
            is DrawerRow.Donate -> view.setOnClickListener { onClick(row) }
            is DrawerRow.Header -> ListDrawerHeaderBinding.bind(view).drawerHeaderTitle.text = row.title
            is DrawerRow.Item -> bindItem(ListDrawerItemBinding.bind(view), row)
        }
    }

    private fun bindItem(binding: ListDrawerItemBinding, item: DrawerRow.Item) {
        binding.drawerItemTitle.text = item.title
        binding.drawerItemIcon.setImageResource(item.icon)
        // A disabled row is dimmed, text and icon alike.
        val color = (binding.drawerItemTitle.currentTextColor and RGB_MASK) or
            if (item.enabled) ENABLED_ALPHA else DISABLED_ALPHA
        binding.drawerItemTitle.setTextColor(color)
        binding.drawerItemIcon.setColorFilter(color, PorterDuff.Mode.MULTIPLY)
        binding.root.isEnabled = item.enabled
        if (item.enabled) binding.root.setOnClickListener { onClick(item) } else binding.root.setOnClickListener(null)
        binding.root.isClickable = item.enabled
    }

    class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        const val HEADER_CONNECTED_SERVER = 0
        const val ITEM_SERVER = MainScreen.CHANNELS
        const val ITEM_PINNED_CHANNELS = 2
        const val ITEM_INFO = 3
        const val ITEM_ACCESS_TOKENS = 4
        const val HEADER_SERVERS = 5
        const val ITEM_FAVOURITES = 6
        const val ITEM_PUBLIC = 8
        const val HEADER_GENERAL = 9
        const val ITEM_SETTINGS = 10

        /** The items that need a connection. */
        val CONNECTED_ITEMS = setOf(ITEM_SERVER, ITEM_PINNED_CHANNELS, ITEM_INFO, ITEM_ACCESS_TOKENS)

        private const val RGB_MASK = 0x00FFFFFF
        private const val ENABLED_ALPHA = 0xFF shl 24
        private const val DISABLED_ALPHA = 0x55 shl 24

        private val DIFF = object : DiffUtil.ItemCallback<DrawerRow>() {
            override fun areItemsTheSame(oldItem: DrawerRow, newItem: DrawerRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: DrawerRow, newItem: DrawerRow) = oldItem == newItem
        }
    }
}

sealed interface DrawerRow {
    /** Tells rows apart across updates. */
    val key: String

    data object Logo : DrawerRow {
        override val key = "logo"
    }

    /** The donation link of flavors that have one, drawn from [layout]. */
    data class Donate(@param:LayoutRes val layout: Int, val link: String) : DrawerRow {
        override val key = "donate"
    }

    data class Header(val id: Int, val title: String) : DrawerRow {
        override val key = "header-$id"
    }

    data class Item(
        val id: Int,
        val title: String,
        @param:DrawableRes val icon: Int,
        val enabled: Boolean,
    ) : DrawerRow {
        override val key = "item-$id"
    }
}
