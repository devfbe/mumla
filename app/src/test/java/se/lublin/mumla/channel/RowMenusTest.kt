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
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity

/** What the channel menu offers for the state it is given, and what its items do. */
@RunWith(RobolectricTestRunner::class)
class RowMenusTest {
    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val channelActions = mockk<ChannelMenu.Actions>(relaxed = true)

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

    /** Everyday actions first; below a divider what changes the channel. */
    @Test
    fun theChannelMenuPutsTheEverydayActionsBeforeTheManagement() {
        val items = inflated(R.menu.context_channel)
        val order = (0 until items.size()).map { items.getItem(it) }

        assertThat(order.map { it.itemId }).containsExactly(
            R.id.context_channel_join, R.id.context_channel_listen, R.id.context_channel_pin,
            R.id.context_channel_shout, R.id.context_channel_view_description,
            R.id.context_channel_add, R.id.context_channel_edit, R.id.context_channel_link,
            R.id.context_channel_unlink_all, R.id.context_channel_remove,
        ).inOrder()
        val everyday = order.take(5).map { it.groupId }.distinct()
        val management = order.drop(5).map { it.groupId }.distinct()
        assertThat(everyday).hasSize(1)
        assertThat(management).hasSize(1)
        assertThat(everyday).isNotEqualTo(management)
    }

    @Test
    fun addingASubchannelNeedsThePermissionToMakeOne() {
        fun addVisible(permissions: Int) =
            channelMenu(channelState(), permissions).second.findItem(R.id.context_channel_add).isVisible

        assertThat(inflated(R.menu.context_channel).findItem(R.id.context_channel_add).isVisible).isFalse()
        assertThat(addVisible(Permissions.ENTER)).isFalse()
        assertThat(addVisible(Permissions.MAKE_CHANNEL)).isTrue()
        assertThat(addVisible(Permissions.MAKE_TEMP_CHANNEL)).isTrue()
    }

    @Test
    fun withoutAStateTheChannelMenuDoesNothing() {
        val (menu, items) = channelMenu(null, Permissions.WRITE)

        assertThat(menu.onMenuItemClick(items.findItem(R.id.context_channel_join))).isFalse()
        verify(exactly = 0) { channelActions.join(any()) }
    }
}
