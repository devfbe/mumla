/*
 * Copyright (C) 2026 The Mumla authors
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

import se.lublin.humla.net.Permissions

/** An action row of the user actions sheet; [checkable] ones show their state as a switch. */
enum class UserAction(val checkable: Boolean = false) {
    KICK, BAN, MUTE(checkable = true), DEAFEN(checkable = true), MOVE, PRIORITY(checkable = true),
    LOCAL_MUTE(checkable = true), IGNORE_MESSAGES(checkable = true), VIEW_COMMENT, CHANGE_COMMENT,
    RESET_COMMENT, INFO, REGISTER,
}

/** One row of the user actions sheet: the action it triggers, and for a checkable one its state. */
data class UserMenuRow(val action: UserAction, val checked: Boolean = false)

/**
 * The rows the user actions sheet shows for [state], in the order they are listed, with the same
 * visibility and checked state the popup menu they replace used to give.
 */
@Suppress("CyclomaticComplexMethod") // One rule per row.
fun userMenuRows(state: UserMenuState): List<UserMenuRow> {
    val user = state.user
    val self = state.isSelf
    val perms = state.serverPermissions
    val canMuteDeafen = state.channelPermissions and (Permissions.WRITE or Permissions.MUTE_DEAFEN) > 0
    val hasComment = !user.comment.isNullOrEmpty() || user.hasCommentHash
    val register = if (self) Permissions.SELF_REGISTER else Permissions.REGISTER
    val mutedOrSuppressed = user.isMuted || user.isSuppressed

    return buildList {
        if (!self && perms and (Permissions.KICK or Permissions.BAN or Permissions.WRITE) > 0) {
            add(UserMenuRow(UserAction.KICK))
        }
        if (!self && perms and (Permissions.BAN or Permissions.WRITE) > 0) add(UserMenuRow(UserAction.BAN))
        if (canMuteDeafen && (!self || mutedOrSuppressed)) {
            add(UserMenuRow(UserAction.MUTE, checked = mutedOrSuppressed))
        }
        if (canMuteDeafen && (!self || user.isDeafened)) {
            add(UserMenuRow(UserAction.DEAFEN, checked = user.isDeafened))
        }
        if (!self && perms and Permissions.MOVE > 0) add(UserMenuRow(UserAction.MOVE))
        if (canMuteDeafen) add(UserMenuRow(UserAction.PRIORITY, checked = user.isPrioritySpeaker))
        if (!self) add(UserMenuRow(UserAction.LOCAL_MUTE, checked = user.isLocalMuted))
        if (!self) add(UserMenuRow(UserAction.IGNORE_MESSAGES, checked = user.isLocalIgnored))
        if (hasComment) add(UserMenuRow(UserAction.VIEW_COMMENT))
        if (self) add(UserMenuRow(UserAction.CHANGE_COMMENT))
        if (!self && hasComment && perms and (Permissions.MOVE or Permissions.WRITE) > 0) {
            add(UserMenuRow(UserAction.RESET_COMMENT))
        }
        add(UserMenuRow(UserAction.INFO))
        if (user.userId < 0 && !user.hash.isNullOrEmpty() && perms and (register or Permissions.WRITE) > 0) {
            add(UserMenuRow(UserAction.REGISTER))
        }
    }
}
