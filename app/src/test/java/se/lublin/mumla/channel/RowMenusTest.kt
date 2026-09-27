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

import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.UserState
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity

/** What the channel and user menus offer for the state they are given, and what their items do. */
@RunWith(RobolectricTestRunner::class)
class RowMenusTest {
    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val channelActions = mockk<ChannelMenu.Actions>(relaxed = true)
    private val userActions = mockk<UserMenu.Actions>(relaxed = true)

    private fun inflated(menuRes: Int): Menu =
        PopupMenu(activity, View(activity)).menu.also { activity.menuInflater.inflate(menuRes, it) }

    private fun channelMenu(state: ChannelMenuState?, permissions: Int): Pair<ChannelMenu, Menu> {
        val menu = ChannelMenu(activity, 2, { state }, channelActions)
        return menu to inflated(R.menu.context_channel).also { menu.onMenuPrepare(it, permissions) }
    }

    private fun channelState(
        isOwn: Boolean = false,
        isListening: Boolean = false,
        isPinned: Boolean = false,
        isLinkedToOwn: Boolean = false,
    ) = ChannelMenuState(hasDescription = false, isPinned, isOwn, isLinkedToOwn, isListening)

    @Test
    fun theChannelMenuOffersListeningOnlyWithThePermissionAndNotForTheOwnChannel() {
        fun listenVisible(state: ChannelMenuState, permissions: Int) =
            channelMenu(state, permissions).second.findItem(R.id.context_channel_listen).isVisible

        assertThat(listenVisible(channelState(), Permissions.LISTEN)).isTrue()
        assertThat(listenVisible(channelState(), Permissions.ENTER)).isFalse()
        assertThat(listenVisible(channelState(isOwn = true), Permissions.LISTEN)).isFalse()
        // Already listening: shown (to stop it) even without the permission.
        assertThat(listenVisible(channelState(isListening = true), Permissions.ENTER)).isTrue()
    }

    @Test
    fun theChannelMenuTogglesListeningPinningAndLinking() {
        val (menu, items) = channelMenu(channelState(isListening = true, isPinned = true), Permissions.LISTEN)
        assertThat(items.findItem(R.id.context_channel_listen).isChecked).isTrue()
        assertThat(items.findItem(R.id.context_channel_pin).isChecked).isTrue()
        assertThat(items.findItem(R.id.context_channel_link).isChecked).isFalse()

        menu.onMenuItemClick(items.findItem(R.id.context_channel_listen))
        menu.onMenuItemClick(items.findItem(R.id.context_channel_pin))
        menu.onMenuItemClick(items.findItem(R.id.context_channel_link))
        menu.onMenuItemClick(items.findItem(R.id.context_channel_join))
        menu.onMenuItemClick(items.findItem(R.id.context_channel_unlink_all))

        verify { channelActions.setListening(2, false) }
        verify { channelActions.setPinned(2, false) }
        verify { channelActions.setLinked(2, true) }
        verify { channelActions.join(2) }
        verify { channelActions.unlinkAll(2) }
    }

    @Test
    fun editingAndRemovingNeedTheWritePermission() {
        val (_, readOnly) = channelMenu(channelState(), Permissions.ENTER)
        val (_, writable) = channelMenu(channelState(), Permissions.WRITE)

        assertThat(readOnly.findItem(R.id.context_channel_edit).isVisible).isFalse()
        assertThat(writable.findItem(R.id.context_channel_edit).isVisible).isTrue()
        assertThat(writable.findItem(R.id.context_channel_remove).isVisible).isTrue()
    }

    @Test
    fun withoutAStateTheChannelMenuDoesNothing() {
        val (menu, items) = channelMenu(null, Permissions.WRITE)

        assertThat(menu.onMenuItemClick(items.findItem(R.id.context_channel_join))).isFalse()
        verify(exactly = 0) { channelActions.join(any()) }
    }

    private fun userMenu(
        user: UserState,
        isSelf: Boolean = false,
        server: Int = 0,
        channel: Int = 0,
    ): Pair<UserMenu, Menu> {
        val state = UserMenuState(user, isSelf, server, channel)
        val menu = UserMenu(activity, user.session, { state }, userActions)
        return menu to inflated(R.menu.context_user).also { menu.onMenuPrepare(it, channel) }
    }

    @Test
    fun moderationNeedsItsPermissionsAndIsNeverOfferedOnOurselves() {
        val ann = UserState(2, "Ann", 1)
        val (_, plain) = userMenu(ann)
        val moderation = Permissions.KICK or Permissions.MOVE
        val (_, moderator) = userMenu(ann, server = moderation, channel = Permissions.MUTE_DEAFEN)
        val (_, self) = userMenu(ann, isSelf = true, server = Permissions.KICK, channel = Permissions.MUTE_DEAFEN)

        assertThat(plain.findItem(R.id.context_kick).isVisible).isFalse()
        assertThat(plain.findItem(R.id.context_mute).isVisible).isFalse()
        assertThat(moderator.findItem(R.id.context_kick).isVisible).isTrue()
        assertThat(moderator.findItem(R.id.context_move).isVisible).isTrue()
        assertThat(moderator.findItem(R.id.context_mute).isVisible).isTrue()
        assertThat(self.findItem(R.id.context_kick).isVisible).isFalse()
        assertThat(self.findItem(R.id.context_local_mute).isVisible).isFalse()
        assertThat(self.findItem(R.id.context_change_comment).isVisible).isTrue()
    }

    @Test
    fun theLocalChoicesAndTheServerMuteAreToggled() {
        val ann = UserState(2, "Ann", 1, isLocalMuted = true, isMuted = false, isDeafened = true)
        val (menu, items) = userMenu(ann, channel = Permissions.MUTE_DEAFEN)
        assertThat(items.findItem(R.id.context_local_mute).isChecked).isTrue()

        menu.onMenuItemClick(items.findItem(R.id.context_local_mute))
        menu.onMenuItemClick(items.findItem(R.id.context_ignore_messages))
        menu.onMenuItemClick(items.findItem(R.id.context_mute))
        menu.onMenuItemClick(items.findItem(R.id.context_deafen))
        menu.onMenuItemClick(items.findItem(R.id.context_local_volume))
        menu.onMenuItemClick(items.findItem(R.id.context_info))

        verify { userActions.setLocalMuted(2, false) }
        verify { userActions.setLocalIgnored(2, true) }
        verify { userActions.setMuteDeaf(2, true, true) }
        verify { userActions.setMuteDeaf(2, false, false) }
        verify { userActions.showLocalVolume(2, "Ann") }
        verify { userActions.showInfo(2, "Ann") }
    }

    @Test
    fun aCommentIsOfferedWhenThereIsOneOrOnlyItsHash() {
        val (_, none) = userMenu(UserState(2, "Ann", 1))
        val (_, hashed) = userMenu(UserState(2, "Ann", 1, hasCommentHash = true))

        assertThat(none.findItem(R.id.context_view_comment).isVisible).isFalse()
        assertThat(hashed.findItem(R.id.context_view_comment).isVisible).isTrue()
    }

    @Test
    fun theMoveDialogListsTheChannelsItIsGiven() {
        every { userActions.channels() } returns emptyList()
        val (menu, items) = userMenu(UserState(2, "Ann", 1), server = Permissions.MOVE)

        assertThat(menu.onMenuItemClick(items.findItem(R.id.context_move))).isTrue()
        verify { userActions.channels() }
    }
}
