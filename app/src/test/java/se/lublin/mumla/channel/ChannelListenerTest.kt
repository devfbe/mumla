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

import android.content.Context
import android.view.Menu
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.net.Permissions
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.PinnedChannels
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected

/** Channel listeners: their rows in the channel list and listening from the channel menu. */
@RunWith(RobolectricTestRunner::class)
class ChannelListenerTest {

    private lateinit var context: Context
    private val session = mockk<IHumlaSession>(relaxed = true).stubConnected()
    private val me = FakeUser(1, "Me")
    private val ann = FakeUser(2, "Ann")

    /** root(0) with Ann in it; music(1), empty but listened to by Me and Ann; sub(2) below it. */
    private val root = FakeChannel(0, "Root").apply { addUser(ann) }
    private val music = root.addSubchannel(
        FakeChannel(1, "Music").apply {
            addListener(ann)
            addListener(me)
        }
    )
    private val sub = music.addSubchannel(FakeChannel(2, "Sub"))
    private val byId = mapOf(0 to root, 1 to music, 2 to sub)

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        every { session.getChannel(any()) } answers { byId[firstArg<Int>()] }
        every { session.sessionId } returns 1
        every { session.sessionUser } returns me
        every { session.sessionChannel } returns root
        idleMainLooper()
    }

    private fun adapter() = ChannelListAdapter(
        context, session, MumlaRepository(mockk(relaxed = true), Dispatchers.Unconfined),
        mockk<FragmentManager>(relaxed = true), showPinnedOnly = false, showUserCount = true,
    )

    private fun bind(adapter: ChannelListAdapter, position: Int): View {
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView
    }

    @Test
    fun listenersFollowTheChannelsUsersAndDoNotCountAsUsers() {
        val adapter = adapter()

        val musicRow = adapter.getChannelPosition(1)
        // A channel with listeners only is shown expanded, its listeners before its subchannels.
        assertThat(adapter.getListenerPosition(1, 2)).isEqualTo(musicRow + 1)
        assertThat(adapter.getListenerPosition(1, 1)).isEqualTo(musicRow + 2)
        assertThat(adapter.getChannelPosition(2)).isEqualTo(musicRow + 3)
        assertThat(adapter.getItemViewType(musicRow + 1)).isEqualTo(R.layout.channel_listener_row)
        assertThat(bind(adapter, musicRow).findViewById<TextView>(R.id.channel_row_count).text.toString())
            .isEqualTo("0")
    }

    @Test
    fun aListenerRowNamesTheUserAndOnlyTheOwnOneCanBeStopped() {
        val adapter = adapter()

        val annRow = bind(adapter, adapter.getListenerPosition(1, 2))
        val myRow = bind(adapter, adapter.getListenerPosition(1, 1))

        assertThat(annRow.findViewById<TextView>(R.id.listener_row_name).text.toString()).isEqualTo("Ann")
        assertThat(annRow.contentDescription).isEqualTo("Ann is listening")
        assertThat(annRow.findViewById<View>(R.id.listener_row_stop).visibility).isEqualTo(View.GONE)
        assertThat(myRow.findViewById<View>(R.id.listener_row_stop).visibility).isEqualTo(View.VISIBLE)

        myRow.findViewById<View>(R.id.listener_row_stop).performClick()
        verify { session.setListening(1, false) }
    }

    private fun preparedMenu(channel: FakeChannel, permissions: Int): Pair<ChannelMenu, Menu> {
        val menu = ChannelMenu(context, channel, session, mockk<PinnedChannels>(relaxed = true), mockk(relaxed = true))
        val popup = PopupMenu(context, View(context)).apply { inflate(R.menu.context_channel) }
        menu.onMenuPrepare(popup.menu, permissions)
        return menu to popup.menu
    }

    @Test
    fun theChannelMenuOffersListeningOnlyWithThePermissionAndNotForTheOwnChannel() {
        assertThat(preparedMenu(sub, Permissions.LISTEN).second.findItem(R.id.context_channel_listen).isVisible)
            .isTrue()
        assertThat(preparedMenu(sub, Permissions.ENTER).second.findItem(R.id.context_channel_listen).isVisible)
            .isFalse()
        assertThat(preparedMenu(root, Permissions.LISTEN).second.findItem(R.id.context_channel_listen).isVisible)
            .isFalse()
    }

    @Test
    fun theChannelMenuTogglesListening() {
        val (subMenu, subItems) = preparedMenu(sub, Permissions.LISTEN)
        val subItem = subItems.findItem(R.id.context_channel_listen)
        assertThat(subItem.isChecked).isFalse()
        subMenu.onMenuItemClick(subItem)
        verify { session.setListening(2, true) }

        // Already listening: shown (to stop it) even without the permission.
        val (musicMenu, musicItems) = preparedMenu(music, Permissions.ENTER)
        val musicItem = musicItems.findItem(R.id.context_channel_listen)
        assertThat(musicItem.isVisible).isTrue()
        assertThat(musicItem.isChecked).isTrue()
        musicMenu.onMenuItemClick(musicItem)
        verify { session.setListening(1, false) }
    }
}
