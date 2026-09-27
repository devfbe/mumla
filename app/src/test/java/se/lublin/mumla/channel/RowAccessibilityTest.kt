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
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.mumla.R
import se.lublin.mumla.service.OverlayUserAdapter
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.util.UserStatus

/** What the channel list and the overlay tell accessibility services about their rows. */
@RunWith(RobolectricTestRunner::class)
class RowAccessibilityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    }

    private val noTaps = object : ChannelListAdapter.Listener {
        override fun onChannelClick(row: ChannelRow.Channel) = Unit
        override fun onUserClick(row: ChannelRow.User) = Unit
        override fun onExpandClick(row: ChannelRow.Channel) = Unit
        override fun onJoinClick(row: ChannelRow.Channel) = Unit
        override fun onChannelMore(anchor: View, row: ChannelRow.Channel) = Unit
        override fun onUserMore(anchor: View, row: ChannelRow.User) = Unit
        override fun onStopListening(row: ChannelRow.Listener) = Unit
    }

    private fun adapter(vararg rows: ChannelRow): ChannelListAdapter {
        val config = AsyncDifferConfig.Builder(ChannelListAdapter.DIFF).setBackgroundThreadExecutor { it.run() }.build()
        return ChannelListAdapter(context, noTaps, config).apply {
            submitList(rows.toList())
            idleMainLooper()
        }
    }

    private fun ChannelListAdapter.bound(position: Int): View {
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = onCreateViewHolder(parent, getItemViewType(position))
        onBindViewHolder(holder, position)
        return holder.itemView
    }

    private fun View.description(id: Int) = findViewById<View>(id).contentDescription?.toString()

    private fun channel(expanded: Boolean) =
        ChannelRow.Channel(0, "Root", 0, 1, expanded, true, false, false, ChannelRow.Lock.NONE)

    private fun user(status: UserStatus = UserStatus.NONE) = ChannelRow.User(7, "user-7", 1, false, status, null)

    @Test
    fun aChannelRowNamesItsButtonsAndWhatTheToggleWillDo() {
        val row = adapter(channel(expanded = true)).bound(0)

        assertThat(row.description(R.id.channel_row_expand)).isEqualTo(context.getString(R.string.a11y_collapse))
        assertThat(row.description(R.id.channel_row_join)).isEqualTo(context.getString(R.string.a11y_join_channel))
        assertThat(row.description(R.id.channel_row_more)).isEqualTo(context.getString(R.string.a11y_channel_options))
    }

    @Test
    fun aCollapsedChannelOffersToExpand() {
        val row = adapter(channel(expanded = false)).bound(0)

        assertThat(row.description(R.id.channel_row_expand)).isEqualTo(context.getString(R.string.expand))
    }

    @Test
    fun aUserRowStatesTheTalkStateAndKeepsItsIconOutOfTheWay() {
        val adapter = adapter(user())
        adapter.setTalkStates(mapOf(7 to TalkState.TALKING))

        val row = adapter.bound(0)

        assertThat(ViewCompat.getStateDescription(row)).isEqualTo(context.getString(R.string.a11y_state_talking))
        assertThat(row.findViewById<View>(R.id.user_row_talk_highlight).importantForAccessibility)
            .isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        assertThat(row.description(R.id.user_row_more)).isEqualTo(context.getString(R.string.a11y_user_options))
    }

    @Test
    fun aRepaintedUserRowUpdatesItsState() {
        val adapter = adapter(user())
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        fun layOut() {
            list.measure(0, 0)
            list.layout(0, 0, 1000, 2000)
        }
        layOut()

        adapter.submitList(listOf(user(UserStatus.SELF_MUTED)))
        idleMainLooper()
        layOut()

        val row = list.findViewHolderForItemId(7L or ChannelRow.USER_ID_MASK)!!.itemView
        assertThat(ViewCompat.getStateDescription(row)).isEqualTo(context.getString(R.string.a11y_state_muted))
    }

    @Test
    fun anOverlayRowStatesTheTalkState() {
        val adapter = OverlayUserAdapter(context)
        adapter.submit(listOf(UserState(7, "user-7", 0, isDeafened = true)), mapOf(7 to TalkState.TALKING))

        val row = adapter.getView(0, null, FrameLayout(context))

        assertThat(ViewCompat.getStateDescription(row))
            .isEqualTo(context.getString(R.string.a11y_state_server_deafened))
    }

    @Test
    fun aSilentUserHasNoState() {
        val adapter = OverlayUserAdapter(context)
        adapter.submit(listOf(UserState(7, "user-7", 0)), emptyMap())

        val row = adapter.getView(0, null, FrameLayout(context))

        assertThat(ViewCompat.getStateDescription(row)).isNull()
    }
}
