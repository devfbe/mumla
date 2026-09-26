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
import android.widget.EditText
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.channel.comment.UserCommentFragment
import se.lublin.mumla.ui.showConfirmDialog
import se.lublin.mumla.util.flattenChannels

/**
 * The popup menu of a user's row: moderation, comments and the local mute and ignore, after
 * which [onLocalStateChanged] runs.
 */
class UserMenu(
    private val context: Context,
    private val user: IUser,
    private val service: IHumlaService,
    private val fragmentManager: FragmentManager,
    private val onLocalStateChanged: (IUser) -> Unit,
) : PermissionsPopupMenu.IOnMenuPrepareListener {

    /** The session while connected; the menu acts on nothing else. */
    private val session: IHumlaSession? get() = service.takeIf { it.isConnected }?.session

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Guard clauses, then one rule per item.
    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        val session = session ?: return
        val self = try {
            user.session == session.sessionId
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in onMenuPrepare: $e")
            return
        }
        val perms = session.permissions
        val channel = user.channel
        if (channel == null) {
            Log.d(TAG, "user.channel == null in onMenuPrepare")
            return
        }
        val channelPerms = if (channel.id != 0) channel.permissions else perms
        val canMuteDeafen = channelPerms and (Permissions.WRITE or Permissions.MUTE_DEAFEN) > 0
        val hasComment = !user.comment.isNullOrEmpty() || user.commentHash != null

        menu.findItem(R.id.context_kick).isVisible =
            !self && perms and (Permissions.KICK or Permissions.BAN or Permissions.WRITE) > 0
        menu.findItem(R.id.context_ban).isVisible = !self && perms and (Permissions.BAN or Permissions.WRITE) > 0
        menu.findItem(R.id.context_mute).isVisible = canMuteDeafen && (!self || user.isMuted || user.isSuppressed)
        menu.findItem(R.id.context_deafen).isVisible = canMuteDeafen && (!self || user.isDeafened)
        menu.findItem(R.id.context_priority).isVisible = canMuteDeafen
        menu.findItem(R.id.context_move).isVisible = !self && perms and Permissions.MOVE > 0
        menu.findItem(R.id.context_change_comment).isVisible = self
        menu.findItem(R.id.context_reset_comment).isVisible =
            !self && hasComment && perms and (Permissions.MOVE or Permissions.WRITE) > 0
        menu.findItem(R.id.context_view_comment).isVisible = hasComment
        val register = if (self) Permissions.SELF_REGISTER else Permissions.REGISTER
        menu.findItem(R.id.context_register).isVisible = user.userId < 0 && !user.hash.isNullOrEmpty() &&
            perms and (register or Permissions.WRITE) > 0
        menu.findItem(R.id.context_local_mute).isVisible = !self
        menu.findItem(R.id.context_local_volume).isVisible = !self
        menu.findItem(R.id.context_ignore_messages).isVisible = !self

        menu.findItem(R.id.context_mute).isChecked = user.isMuted || user.isSuppressed
        menu.findItem(R.id.context_deafen).isChecked = user.isDeafened
        menu.findItem(R.id.context_priority).isChecked = user.isPrioritySpeaker
        menu.findItem(R.id.context_local_mute).isChecked = user.isLocalMuted
        menu.findItem(R.id.context_ignore_messages).isChecked = user.isLocalIgnored
    }

    @Suppress("CyclomaticComplexMethod") // One branch per item.
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.context_ban, R.id.context_kick -> showKickDialog(ban = item.itemId == R.id.context_ban)
            R.id.context_mute ->
                session?.setMuteDeafState(user.session, !(user.isMuted || user.isSuppressed), user.isDeafened)
            R.id.context_deafen -> session?.setMuteDeafState(user.session, user.isMuted, !user.isDeafened)
            R.id.context_move -> showChannelMoveDialog()
            R.id.context_priority -> session?.setPrioritySpeaker(user.session, !user.isPrioritySpeaker)
            R.id.context_local_mute -> {
                user.isLocalMuted = !user.isLocalMuted
                onLocalStateChanged(user)
            }
            R.id.context_local_volume -> session?.let { showLocalVolumeDialog(context, it, user, onLocalStateChanged) }
            R.id.context_ignore_messages -> {
                user.isLocalIgnored = !user.isLocalIgnored
                onLocalStateChanged(user)
            }
            R.id.context_change_comment -> showUserComment(edit = true)
            R.id.context_view_comment -> showUserComment(edit = false)
            R.id.context_reset_comment ->
                context.showConfirmDialog(
                    context.getString(R.string.confirm_reset_comment, user.name),
                    R.string.confirm,
                ) { session?.setUserComment(user.session, "") }
            R.id.context_register -> session?.registerUser(user.session)
            R.id.context_info -> showUserInfoDialog(context, service, user)
            else -> return false
        }
        return true
    }

    private fun showKickDialog(ban: Boolean) {
        val reasonField = EditText(context).apply { setHint(R.string.hint_reason) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.user_menu_kick)
            .setView(reasonField)
            .setPositiveButton(R.string.user_menu_kick) { _, _ ->
                session?.kickBanUser(user.session, reasonField.text.toString(), ban)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUserComment(edit: Boolean) {
        val fragment = UserCommentFragment()
        fragment.arguments = Bundle().apply {
            putInt("session", user.session)
            putString("comment", user.comment)
            putBoolean("editing", edit)
        }
        fragment.show(fragmentManager, UserCommentFragment::class.java.name)
    }

    private fun showChannelMoveDialog() {
        val root = session?.rootChannel ?: return
        val channels = flattenChannels(root)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.user_menu_move)
            .setItems(channels.map { it.name }.toTypedArray()) { _, which ->
                session?.moveUserToChannel(user.session, channels[which].id)
            }
            .show()
    }

    fun showPopup(anchor: View) {
        val channel = user.channel ?: return
        PermissionsPopupMenu(context, anchor, R.menu.context_user, this, channel, service).show()
    }

    private companion object {
        val TAG: String = UserMenu::class.java.name
    }
}
