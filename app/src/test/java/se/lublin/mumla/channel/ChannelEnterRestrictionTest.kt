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
import android.view.View
import android.widget.ImageView
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
import org.robolectric.shadows.ShadowToast
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected

/** Channels whose ACL restricts entering: the lock in the list and the refused join. */
@RunWith(RobolectricTestRunner::class)
class ChannelEnterRestrictionTest {

    private lateinit var context: Context
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val service = mockk<IHumlaService>(relaxed = true).stubConnected(session)
    private val root = FakeChannel(0, "Root")
    private val open = root.addSubchannel(FakeChannel(1, "Open"))
    private val restricted = root.addSubchannel(FakeChannel(2, "Restricted").apply { isEnterRestricted = true })
    private val locked = root.addSubchannel(
        FakeChannel(3, "Locked").apply {
            isEnterRestricted = true
            canEnter = false
        }
    )
    private val byId = mapOf(0 to root, 1 to open, 2 to restricted, 3 to locked)

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        every { session.getChannel(any()) } answers { byId[firstArg<Int>()] }
        // Expand all, so every channel has a row.
        open.addUser(FakeUser(10))
        restricted.addUser(FakeUser(11))
        locked.addUser(FakeUser(12))
        idleMainLooper()
    }

    private fun adapter() = ChannelListAdapter(
        context, service, MumlaRepository(mockk(relaxed = true), Dispatchers.Unconfined),
        mockk<FragmentManager>(relaxed = true), showPinnedOnly = false, showUserCount = true,
    )

    private fun rowOf(adapter: ChannelListAdapter, channelId: Int): View {
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val position = adapter.getChannelPosition(channelId)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView
    }

    @Test
    fun onlyRestrictedChannelsShowALockThatSaysWhetherTheyCanBeEntered() {
        val adapter = adapter()

        val openLock = rowOf(adapter, 1).findViewById<ImageView>(R.id.channel_row_lock)
        val restrictedLock = rowOf(adapter, 2).findViewById<ImageView>(R.id.channel_row_lock)
        val lockedLock = rowOf(adapter, 3).findViewById<ImageView>(R.id.channel_row_lock)

        assertThat(openLock.visibility).isEqualTo(View.GONE)
        assertThat(restrictedLock.visibility).isEqualTo(View.VISIBLE)
        assertThat(restrictedLock.contentDescription).isEqualTo(context.getString(R.string.a11y_channel_restricted))
        assertThat(lockedLock.visibility).isEqualTo(View.VISIBLE)
        assertThat(lockedLock.contentDescription).isEqualTo(context.getString(R.string.a11y_channel_locked))
    }

    @Test
    fun theJoinButtonJoinsAnEnterableChannel() {
        rowOf(adapter(), 2).findViewById<View>(R.id.channel_row_join).performClick()

        verify { session.joinChannel(2) }
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    fun theJoinButtonExplainsInsteadOfJoiningAChannelThatCannotBeEntered() {
        rowOf(adapter(), 3).findViewById<View>(R.id.channel_row_join).performClick()

        verify(exactly = 0) { session.joinChannel(any()) }
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("You are not allowed to enter Locked.")
    }
}
