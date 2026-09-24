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
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.stubConnected

/** What the channel list and the overlay tell accessibility services about their rows. */
@RunWith(RobolectricTestRunner::class)
class RowAccessibilityTest {
    private lateinit var context: Context
    private val root = FakeChannel(0)
    private val sub = FakeChannel(1)
    private val user = FakeUser(7)

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        root.addSubchannel(sub)
        root.addUser(user)
    }

    private fun adapter(): ChannelListAdapter {
        val session = mockk<IHumlaSession>(relaxed = true)
        every { session.getChannel(0) } returns root
        val service = mockk<IHumlaService>(relaxed = true).stubConnected(session)
        return ChannelListAdapter(
            context, service, MumlaRepository(mockk(relaxed = true), Dispatchers.Unconfined),
            mockk<FragmentManager>(relaxed = true), false, true,
        )
    }

    private fun ChannelListAdapter.bound(position: Int): View {
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = onCreateViewHolder(parent, getItemViewType(position))
        onBindViewHolder(holder, position)
        return holder.itemView
    }

    private fun View.description(id: Int) = findViewById<View>(id).contentDescription?.toString()

    @Test
    fun aChannelRowNamesItsButtonsAndWhatTheToggleWillDo() {
        val adapter = adapter()

        val row = adapter.bound(adapter.getChannelPosition(0))

        assertThat(row.description(R.id.channel_row_expand)).isEqualTo(context.getString(R.string.a11y_collapse))
        assertThat(row.description(R.id.channel_row_join)).isEqualTo(context.getString(R.string.a11y_join_channel))
        assertThat(row.description(R.id.channel_row_more)).isEqualTo(context.getString(R.string.a11y_channel_options))
    }

    @Test
    fun aCollapsedChannelOffersToExpand() {
        sub.addUser(FakeUser(8))
        val adapter = adapter()
        adapter.bound(adapter.getChannelPosition(1)).findViewById<View>(R.id.channel_row_expand).performClick()
        adapter.getChannelPosition(1) // runs the scheduled rebuild

        val row = adapter.bound(adapter.getChannelPosition(1))

        assertThat(row.description(R.id.channel_row_expand)).isEqualTo(context.getString(R.string.expand))
    }

    @Test
    fun aUserRowStatesTheTalkStateAndKeepsItsIconOutOfTheWay() {
        user.state = TalkState.TALKING
        val adapter = adapter()

        val row = adapter.bound(adapter.getUserPosition(7))

        assertThat(ViewCompat.getStateDescription(row)).isEqualTo(context.getString(R.string.a11y_state_talking))
        assertThat(row.findViewById<View>(R.id.user_row_talk_highlight).importantForAccessibility)
            .isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        assertThat(row.description(R.id.user_row_more)).isEqualTo(context.getString(R.string.a11y_user_options))
    }

    @Test
    fun aRepaintedUserRowUpdatesItsState() {
        val adapter = adapter()
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            measure(0, 0)
            layout(0, 0, 1000, 2000)
        }
        user.selfMuted = true

        adapter.updateUserStates(user, list)

        val row = list.findViewHolderForItemId(7L or ChannelListAdapter.USER_ID_MASK)!!.itemView
        assertThat(ViewCompat.getStateDescription(row)).isEqualTo(context.getString(R.string.a11y_state_muted))
    }

    @Test
    fun anOverlayRowStatesTheTalkState() {
        user.deafened = true

        val row = ChannelAdapter(context, root).getView(0, null, FrameLayout(context))

        assertThat(ViewCompat.getStateDescription(row))
            .isEqualTo(context.getString(R.string.a11y_state_server_deafened))
    }

    @Test
    fun aSilentUserHasNoState() {
        val row = ChannelAdapter(context, root).getView(0, null, FrameLayout(context))

        assertThat(ViewCompat.getStateDescription(row)).isNull()
    }
}
