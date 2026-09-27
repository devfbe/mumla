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
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.ChannelState
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.ui.showConfirmDialog

/** The popup menu of a user's row: moderation, comments and the local mute, volume and ignore. */
class UserMenu(
    private val context: Context,
    private val session: Int,
    private val state: () -> UserMenuState?,
    private val actions: Actions,
) : PermissionsPopupMenu.IOnMenuPrepareListener {

    /** What the items do, for the user they were picked on. */
    @Suppress("TooManyFunctions") // One per item.
    interface Actions : MenuPermissions {
        fun kickBan(session: Int, reason: String, ban: Boolean)
        fun setMuteDeaf(session: Int, mute: Boolean, deaf: Boolean)
        fun setPrioritySpeaker(session: Int, priority: Boolean)

        /** The channels a user can be moved to, in tree order. */
        fun channels(): List<ChannelState>
        fun moveUser(session: Int, channel: Int)
        fun showComment(session: Int, comment: String?, edit: Boolean)
        fun resetComment(session: Int)
        fun register(session: Int)
        fun setLocalMuted(session: Int, muted: Boolean)
        fun setLocalIgnored(session: Int, ignored: Boolean)
        fun showLocalVolume(session: Int, name: String?)
        fun showInfo(session: Int, name: String?)
    }

    @Suppress("CyclomaticComplexMethod") // One rule per item.
    override fun onMenuPrepare(menu: Menu, permissions: Int) {
        val state = state() ?: return
        val user = state.user
        val self = state.isSelf
        val perms = state.serverPermissions
        val canMuteDeafen = state.channelPermissions and (Permissions.WRITE or Permissions.MUTE_DEAFEN) > 0
        val hasComment = !user.comment.isNullOrEmpty() || user.hasCommentHash

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

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One branch per item.
    override fun onMenuItemClick(item: MenuItem): Boolean {
        val user = state()?.user ?: return false
        when (item.itemId) {
            R.id.context_ban, R.id.context_kick -> showKickDialog(ban = item.itemId == R.id.context_ban)
            R.id.context_mute -> actions.setMuteDeaf(session, !(user.isMuted || user.isSuppressed), user.isDeafened)
            R.id.context_deafen -> actions.setMuteDeaf(session, user.isMuted, !user.isDeafened)
            R.id.context_move -> showChannelMoveDialog()
            R.id.context_priority -> actions.setPrioritySpeaker(session, !user.isPrioritySpeaker)
            R.id.context_local_mute -> actions.setLocalMuted(session, !user.isLocalMuted)
            R.id.context_local_volume -> actions.showLocalVolume(session, user.name)
            R.id.context_ignore_messages -> actions.setLocalIgnored(session, !user.isLocalIgnored)
            R.id.context_change_comment -> actions.showComment(session, user.comment, edit = true)
            R.id.context_view_comment -> actions.showComment(session, user.comment, edit = false)
            R.id.context_reset_comment -> context.showConfirmDialog(
                context.getString(R.string.confirm_reset_comment, user.name),
                R.string.confirm,
            ) { actions.resetComment(session) }
            R.id.context_register -> actions.register(session)
            R.id.context_info -> actions.showInfo(session, user.name)
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
                actions.kickBan(session, reasonField.text.toString(), ban)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChannelMoveDialog() {
        val channels = actions.channels()
        if (channels.isEmpty()) return
        showChannelMoveDialog(context, channels) { actions.moveUser(session, it) }
    }

    fun showPopup(anchor: View) {
        val channel = state()?.user?.channel ?: return
        PermissionsPopupMenu(
            context, anchor, R.menu.context_user, this,
            actions.permissions(channel),
        ) { actions.requestPermissions(channel) }.show()
    }
}
