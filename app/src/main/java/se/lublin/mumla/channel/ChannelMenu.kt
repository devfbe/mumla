/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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
package se.lublin.mumla.channel

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.Flow
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.ui.showConfirmDialog

/** Our permissions in a channel, for a menu that depends on them. */
interface MenuPermissions {
    fun permissions(channel: Int): Flow<Int>

    fun requestPermissions(channel: Int)
}

/** The popup menu of a channel's row: join, edit, pin, link, shout and so on. */
class ChannelMenu(
    private val context: Context,
    private val channel: Int,
    private val state: () -> ChannelMenuState?,
    private val actions: Actions,
) : PermissionsPopupMenu.IOnMenuPrepareListener {

    /** What the items do, for the channel they were picked on. */
    interface Actions : MenuPermissions {
        fun join(channel: Int)
        fun addChannel(parent: Int)
        fun editChannel(channel: Int)
        fun removeChannel(channel: Int)
        fun showDescription(channel: Int)
        fun setPinned(channel: Int, pinned: Boolean)
        fun setLinked(channel: Int, linked: Boolean)
        fun setListening(channel: Int, listen: Boolean)
        fun unlinkAll(channel: Int)
        fun shout(channel: Int, includeLinked: Boolean, includeSubchannels: Boolean)
    }

    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        // TODO This breaks uMurmur ACL. Put in a fix based on server version perhaps?
        // menu_channel_add visible with (permissions & (Permissions.MAKE_CHANNEL | Permissions.MAKE_TEMP_CHANNEL)) > 0
        val canWrite = permissions and Permissions.WRITE > 0
        menu.findItem(R.id.context_channel_edit).isVisible = canWrite
        menu.findItem(R.id.context_channel_remove).isVisible = canWrite
        val state = state() ?: return
        menu.findItem(R.id.context_channel_view_description).isVisible = state.hasDescription
        menu.findItem(R.id.context_channel_pin).isChecked = state.isPinned
        menu.findItem(R.id.context_channel_link).isChecked = state.isLinkedToOwn
        // Offered with the Listen permission, and always to stop listening; never for the own channel.
        menu.findItem(R.id.context_channel_listen).apply {
            isChecked = state.isListening
            isVisible = !state.isOwn && (state.isListening || permissions and Permissions.LISTEN > 0)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per item.
    override fun onMenuItemClick(item: MenuItem): Boolean {
        val state = state() ?: return false
        when (item.itemId) {
            R.id.context_channel_join -> actions.join(channel)
            R.id.context_channel_add -> actions.addChannel(channel)
            R.id.context_channel_edit -> actions.editChannel(channel)
            R.id.context_channel_remove -> context.showConfirmDialog(
                context.getString(R.string.confirm_delete_channel),
                title = context.getString(R.string.confirm),
            ) { actions.removeChannel(channel) }
            R.id.context_channel_view_description -> actions.showDescription(channel)
            R.id.context_channel_pin -> actions.setPinned(channel, !state.isPinned)
            R.id.context_channel_link -> actions.setLinked(channel, !item.isChecked)
            R.id.context_channel_listen -> actions.setListening(channel, !state.isListening)
            R.id.context_channel_unlink_all -> actions.unlinkAll(channel)
            R.id.context_channel_shout -> showShoutDialog()
            else -> return false
        }
        return true
    }

    /** Asks which channels to include, then whispers to them. */
    private fun showShoutDialog() {
        val subchannelBox = CheckBox(context).apply { setText(R.string.shout_include_subchannels) }
        val linkedBox = CheckBox(context).apply { setText(R.string.shout_include_linked) }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(subchannelBox)
            addView(linkedBox)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.shout_configure)
            .setView(layout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                actions.shout(channel, linkedBox.isChecked, subchannelBox.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showPopup(anchor: View) {
        PermissionsPopupMenu(
            context, anchor, R.menu.context_channel, this,
            actions.permissions(channel),
        ) { actions.requestPermissions(channel) }.show()
    }
}
