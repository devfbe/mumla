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
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.net.Permissions
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.R
import se.lublin.mumla.app.showConfirmDialog
import se.lublin.mumla.channel.comment.ChannelDescriptionFragment
import se.lublin.mumla.db.PinnedChannels

/** The popup menu of a channel's row: join, edit, pin, link, shout and so on. */
class ChannelMenu(
    private val context: Context,
    private val channel: IChannel,
    private val service: IHumlaService,
    private val pinnedChannels: PinnedChannels,
    private val fragmentManager: FragmentManager,
) : PermissionsPopupMenu.IOnMenuPrepareListener {

    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        // TODO This breaks uMurmur ACL. Put in a fix based on server version perhaps?
        // menu_channel_add visible with (permissions & (Permissions.MAKE_CHANNEL | Permissions.MAKE_TEMP_CHANNEL)) > 0
        val canWrite = permissions and Permissions.WRITE > 0
        menu.findItem(R.id.context_channel_edit).isVisible = canWrite
        menu.findItem(R.id.context_channel_remove).isVisible = canWrite
        menu.findItem(R.id.context_channel_view_description).isVisible =
            channel.description != null || channel.descriptionHash != null
        service.targetServer?.let { server ->
            menu.findItem(R.id.context_channel_pin).isChecked = pinnedChannels.isPinned(server.id, channel.id)
        }
        if (service.isConnected) {
            val ourChannel = try {
                service.session.sessionChannel
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onMenuPrepare: $e")
                null
            }
            if (ourChannel != null) {
                menu.findItem(R.id.context_channel_link).isChecked = channel.links.contains(ourChannel)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per item.
    override fun onMenuItemClick(item: MenuItem): Boolean {
        val session = service.takeIf { it.isConnected }?.session ?: return false
        when (item.itemId) {
            R.id.context_channel_join -> session.joinChannel(channel.id)
            R.id.context_channel_add -> showEditor(adding = true)
            R.id.context_channel_edit -> showEditor(adding = false)
            R.id.context_channel_remove -> confirmRemoval()
            R.id.context_channel_view_description -> showDescription()
            R.id.context_channel_pin -> togglePin()
            R.id.context_channel_link -> session.sessionChannel?.let { ours ->
                if (item.isChecked) session.unlinkChannels(ours, channel) else session.linkChannels(ours, channel)
            }
            R.id.context_channel_unlink_all -> session.unlinkAllChannels(channel)
            R.id.context_channel_shout -> showShoutDialog()
            else -> return false
        }
        return true
    }

    private fun confirmRemoval() {
        context.showConfirmDialog(
            context.getString(R.string.confirm_delete_channel),
            title = context.getString(R.string.confirm),
        ) {
            if (service.isConnected) service.session.removeChannel(channel.id)
        }
    }

    private fun togglePin() {
        val server = service.targetServer ?: return
        pinnedChannels.setPinned(server.id, channel.id, !pinnedChannels.isPinned(server.id, channel.id))
    }

    private fun showEditor(adding: Boolean) {
        val editor = ChannelEditFragment()
        editor.arguments = Bundle().apply {
            if (adding) putInt("parent", channel.id) else putInt("channel", channel.id)
            putBoolean("adding", adding)
        }
        editor.show(fragmentManager, "ChannelAdd")
    }

    private fun showDescription() {
        val fragment = ChannelDescriptionFragment()
        fragment.arguments = Bundle().apply {
            putInt("channel", channel.id)
            putString("comment", channel.description)
            putBoolean("editing", false)
        }
        fragment.show(fragmentManager, ChannelDescriptionFragment::class.java.name)
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
            .setPositiveButton(R.string.confirm) { _, _ -> shout(linkedBox.isChecked, subchannelBox.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shout(includeLinked: Boolean, includeSubchannels: Boolean) {
        if (!service.isConnected) return
        val session = service.session
        // Replaces any whisper target we registered before.
        if (session.voiceTargetMode == VoiceTargetMode.WHISPER) {
            session.unregisterWhisperTarget(session.voiceTargetId)
        }
        val id = session.registerWhisperTarget(WhisperTargetChannel(channel, includeLinked, includeSubchannels, null))
        if (id > 0) {
            session.voiceTargetId = id
        } else {
            Toast.makeText(context, R.string.shout_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun showPopup(anchor: View) {
        PermissionsPopupMenu(context, anchor, R.menu.context_channel, this, channel, service).show()
    }

    private companion object {
        val TAG: String = ChannelMenu::class.java.name
    }
}
