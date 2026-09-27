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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.UserState
import se.lublin.humla.net.Permissions

/** [userMenuRows]: the rows the user actions sheet shows, in the order the old popup menu used. */
@RunWith(RobolectricTestRunner::class)
class UserMenuRowsTest {

    private fun state(
        user: UserState,
        isSelf: Boolean = false,
        server: Int = 0,
        channel: Int = 0,
    ) = UserMenuState(user, isSelf, server, channel)

    @Test
    fun moderationNeedsItsPermissionsAndIsNeverOfferedOnOurselves() {
        val ann = UserState(2, "Ann", 1)
        val plain = userMenuRows(state(ann)).map { it.action }
        val moderation = Permissions.KICK or Permissions.MOVE
        val moderator = userMenuRows(state(ann, server = moderation, channel = Permissions.MUTE_DEAFEN))
            .map { it.action }
        val self = userMenuRows(
            state(ann, isSelf = true, server = Permissions.KICK, channel = Permissions.MUTE_DEAFEN),
        ).map { it.action }

        assertThat(plain).doesNotContain(UserAction.KICK)
        assertThat(plain).doesNotContain(UserAction.MUTE)
        assertThat(moderator).containsAtLeast(UserAction.KICK, UserAction.MOVE, UserAction.MUTE)
        assertThat(self).doesNotContain(UserAction.KICK)
        assertThat(self).doesNotContain(UserAction.LOCAL_MUTE)
        assertThat(self).contains(UserAction.CHANGE_COMMENT)
    }

    @Test
    fun theLocalAndServerMuteRowsCarryTheirCurrentState() {
        val ann = UserState(2, "Ann", 1, isLocalMuted = true, isMuted = false, isDeafened = true)
        val rows = userMenuRows(state(ann, channel = Permissions.MUTE_DEAFEN))

        assertThat(rows.single { it.action == UserAction.LOCAL_MUTE }.checked).isTrue()
        assertThat(rows.single { it.action == UserAction.DEAFEN }.checked).isTrue()
        assertThat(rows.single { it.action == UserAction.MUTE }.checked).isFalse()
    }

    @Test
    fun aCommentIsOfferedWhenThereIsOneOrOnlyItsHash() {
        val none = userMenuRows(state(UserState(2, "Ann", 1))).map { it.action }
        val hashed = userMenuRows(state(UserState(2, "Ann", 1, hasCommentHash = true))).map { it.action }

        assertThat(none).doesNotContain(UserAction.VIEW_COMMENT)
        assertThat(hashed).contains(UserAction.VIEW_COMMENT)
    }

    @Test
    fun registeringNeedsAnUnregisteredUserWithAHashAndThePermission() {
        val unregistered = UserState(2, "Ann", 1, hash = "hash")
        val noHash = UserState(2, "Ann", 1)
        val registered = UserState(2, "Ann", 1, hash = "hash", userId = 30)

        assertThat(userMenuRows(state(unregistered, server = Permissions.REGISTER)).map { it.action })
            .contains(UserAction.REGISTER)
        assertThat(userMenuRows(state(noHash, server = Permissions.REGISTER)).map { it.action })
            .doesNotContain(UserAction.REGISTER)
        assertThat(userMenuRows(state(registered, server = Permissions.REGISTER)).map { it.action })
            .doesNotContain(UserAction.REGISTER)
    }

    @Test
    fun theLocalMuteVolumeAndIgnoreRowsAreNeverOfferedOnOurselves() {
        val self = userMenuRows(state(UserState(1, "Me", 1), isSelf = true)).map { it.action }

        assertThat(self).containsNoneOf(UserAction.LOCAL_MUTE, UserAction.IGNORE_MESSAGES, UserAction.MOVE)
    }

    /** The same order the popup menu it replaces listed its items in. */
    @Test
    fun theRowsKeepThePopupMenusOrder() {
        val ann = UserState(2, "Ann", 1, hash = "annhash", hasCommentHash = true)
        val perms = Permissions.KICK or Permissions.BAN or Permissions.MOVE or Permissions.REGISTER
        val rows = userMenuRows(state(ann, server = perms, channel = Permissions.MUTE_DEAFEN)).map { it.action }

        assertThat(rows).containsExactly(
            UserAction.KICK, UserAction.BAN, UserAction.MUTE, UserAction.DEAFEN, UserAction.MOVE,
            UserAction.PRIORITY, UserAction.LOCAL_MUTE, UserAction.IGNORE_MESSAGES, UserAction.VIEW_COMMENT,
            UserAction.RESET_COMMENT, UserAction.INFO, UserAction.REGISTER,
        ).inOrder()
    }

    @Test
    fun theInfoRowIsAlwaysOffered() {
        assertThat(userMenuRows(state(UserState(2, "Ann", 1))).map { it.action }).contains(UserAction.INFO)
        assertThat(userMenuRows(state(UserState(1, "Me", 1), isSelf = true)).map { it.action })
            .contains(UserAction.INFO)
    }
}
