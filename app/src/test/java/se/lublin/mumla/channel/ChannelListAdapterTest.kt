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
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
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
import org.robolectric.annotation.GraphicsMode
import se.lublin.humla.model.Bytes
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.drawable.CircleDrawable
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.util.UserStatus
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/** How the channel list binds its rows, repaints talk states and reports taps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelListAdapterTest {

    private lateinit var context: Context
    private val taps = mutableListOf<Pair<String, Long>>()

    private val listener = object : ChannelListAdapter.Listener {
        override fun onChannelClick(row: ChannelRow.Channel) { taps += "channel" to row.id }
        override fun onUserClick(row: ChannelRow.User) { taps += "user" to row.id }
        override fun onExpandClick(row: ChannelRow.Channel) { taps += "expand" to row.id }
        override fun onJoinClick(row: ChannelRow.Channel) { taps += "join" to row.id }
        override fun onChannelMore(anchor: View, row: ChannelRow.Channel) { taps += "channelMore" to row.id }
        override fun onUserMore(anchor: View, row: ChannelRow.User) { taps += "userMore" to row.id }
        override fun onStopListening(row: ChannelRow.Listener) { taps += "stop" to row.id }
    }

    private val direct = Executor { it.run() }
    private lateinit var adapter: ChannelListAdapter
    private lateinit var list: RecyclerView

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val config = AsyncDifferConfig.Builder(ChannelListAdapter.DIFF).setBackgroundThreadExecutor(direct).build()
        adapter = ChannelListAdapter(context, listener, config)
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ChannelListAdapterTest.adapter
        }
    }

    private fun show(vararg rows: ChannelRow) {
        adapter.submitList(rows.toList())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        layOut()
    }

    private fun layOut() {
        list.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun row(id: Long): View = list.findViewHolderForItemId(id)!!.itemView

    @Suppress("LongParameterList") // One per field, all defaulted.
    private fun channel(
        id: Int,
        name: String? = "channel-$id",
        depth: Int = 0,
        userCount: Int? = 0,
        expanded: Boolean = true,
        expandable: Boolean = true,
        isOwn: Boolean = false,
        isLinked: Boolean = false,
        lock: ChannelRow.Lock = ChannelRow.Lock.NONE,
    ) = ChannelRow.Channel(id, name, depth, userCount, expanded, expandable, isOwn, isLinked, lock)

    private fun user(
        session: Int,
        depth: Int = 1,
        isSelf: Boolean = false,
        status: UserStatus = UserStatus.NONE,
        avatar: Bytes? = null,
    ) = ChannelRow.User(session, "user-$session", depth, isSelf, status, avatar)

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    @Test
    fun aChannelRowShowsItsNameCountAndIndentation() {
        show(channel(1, "Lounge", depth = 2, userCount = 5))

        val row = row(ChannelRow.CHANNEL_ID_MASK or 1L)
        assertThat(row.text(R.id.channel_row_name)).isEqualTo("Lounge")
        assertThat(row.text(R.id.channel_row_count)).isEqualTo("5")
        assertThat(row.findViewById<View>(R.id.channel_row_title).paddingLeft).isEqualTo(dp(50f))
    }

    @Test
    fun theCountIsHiddenWhenTheRowCarriesNone() {
        show(channel(1, userCount = null))

        assertThat(row(ChannelRow.CHANNEL_ID_MASK or 1L).findViewById<View>(R.id.channel_row_count).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun theChannelNameIsBoldForOursAndItalicForALinkedOne() {
        show(
            channel(1, isOwn = true),
            channel(2, isLinked = true),
            channel(3, isOwn = true, isLinked = true),
            channel(4),
        )

        fun style(id: Int) = row(ChannelRow.CHANNEL_ID_MASK or id.toLong())
            .findViewById<TextView>(R.id.channel_row_name).typeface?.style ?: Typeface.NORMAL
        assertThat(listOf(1, 2, 3, 4).map(::style))
            .containsExactly(Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC, Typeface.NORMAL).inOrder()
    }

    @Test
    fun theExpandToggleShowsWhetherTheRowIsOpenAndHidesWithoutAnythingToOpen() {
        show(channel(1, expanded = true), channel(2, expanded = false), channel(3, expandable = false))

        fun toggle(id: Int) =
            row(ChannelRow.CHANNEL_ID_MASK or id.toLong()).findViewById<ImageView>(R.id.channel_row_expand)
        assertThat(toggle(1).contentDescription).isEqualTo(context.getString(R.string.a11y_collapse))
        assertThat(toggle(2).contentDescription).isEqualTo(context.getString(R.string.expand))
        assertThat(toggle(3).visibility).isEqualTo(View.INVISIBLE)
        assertThat(toggle(3).isEnabled).isFalse()
    }

    @Test
    fun onlyARestrictedChannelShowsALockThatSaysWhetherItCanBeEntered() {
        show(channel(1), channel(2, lock = ChannelRow.Lock.OPEN), channel(3, lock = ChannelRow.Lock.CLOSED))

        fun lock(id: Int) =
            row(ChannelRow.CHANNEL_ID_MASK or id.toLong()).findViewById<ImageView>(R.id.channel_row_lock)
        assertThat(lock(1).visibility).isEqualTo(View.GONE)
        assertThat(lock(2).contentDescription).isEqualTo(context.getString(R.string.a11y_channel_restricted))
        assertThat(lock(3).contentDescription).isEqualTo(context.getString(R.string.a11y_channel_locked))
    }

    @Test
    fun aUserRowShowsItsNameBoldOnlyForUsAndIsIndented() {
        show(user(1, depth = 1), user(2, depth = 3, isSelf = true))

        val other = row(ChannelRow.USER_ID_MASK or 1L)
        val self = row(ChannelRow.USER_ID_MASK or 2L)
        assertThat(other.text(R.id.user_row_name)).isEqualTo("user-1")
        assertThat(other.findViewById<TextView>(R.id.user_row_name).typeface?.style ?: Typeface.NORMAL)
            .isEqualTo(Typeface.NORMAL)
        assertThat(self.findViewById<TextView>(R.id.user_row_name).typeface.style).isEqualTo(Typeface.BOLD)
        assertThat(self.findViewById<View>(R.id.user_row_title).paddingLeft).isEqualTo(dp(75f))
    }

    @Test
    fun aListenerRowNamesTheUserAndOnlyTheOwnOneCanBeStopped() {
        show(ChannelRow.Listener(5, 1, "Ann", 1, isOwn = false), ChannelRow.Listener(5, 2, "Me", 1, isOwn = true))

        val ann = row(ChannelRow.listenerId(5, 1))
        val me = row(ChannelRow.listenerId(5, 2))
        assertThat(ann.contentDescription).isEqualTo(context.getString(R.string.a11y_listener, "Ann"))
        assertThat(ann.findViewById<View>(R.id.listener_row_stop).visibility).isEqualTo(View.GONE)
        assertThat(me.findViewById<View>(R.id.listener_row_stop).visibility).isEqualTo(View.VISIBLE)

        me.findViewById<View>(R.id.listener_row_stop).performClick()
        assertThat(taps).containsExactly("stop" to ChannelRow.listenerId(5, 2))
    }

    @Test
    fun tappingARowOrItsButtonsReportsTheRowAndALongPressIsTheOverflowButton() {
        show(channel(1), user(2))
        val channel = row(ChannelRow.CHANNEL_ID_MASK or 1L)
        val user = row(ChannelRow.USER_ID_MASK or 2L)

        channel.performClick()
        channel.findViewById<View>(R.id.channel_row_expand).performClick()
        channel.findViewById<View>(R.id.channel_row_join).performClick()
        channel.performLongClick()
        user.performClick()
        user.performLongClick()

        val channelId = ChannelRow.CHANNEL_ID_MASK or 1L
        val userId = ChannelRow.USER_ID_MASK or 2L
        assertThat(taps).containsExactly(
            "channel" to channelId, "expand" to channelId, "join" to channelId, "channelMore" to channelId,
            "user" to userId, "userMore" to userId,
        ).inOrder()
    }

    private fun iconOf(session: Int): Drawable =
        row(ChannelRow.USER_ID_MASK or session.toLong()).findViewById<ImageView>(R.id.user_row_talk_highlight).drawable

    private fun resourceOf(drawable: Drawable): Int =
        org.robolectric.Shadows.shadowOf(drawable).createdFromResId

    @Test
    fun theIconShowsTheStateOfHighestPriorityThenTalkingThenTheAvatar() {
        show(
            user(1, status = UserStatus.SELF_DEAFENED),
            user(2, status = UserStatus.DEAFENED),
            user(3, status = UserStatus.SELF_MUTED),
            user(4, status = UserStatus.MUTED),
            user(5, status = UserStatus.SUPPRESSED),
            user(6),
            user(7),
        )
        adapter.setTalkStates(mapOf(5 to TalkState.TALKING, 6 to TalkState.WHISPERING))
        layOut()

        assertThat((1..7).map { resourceOf(iconOf(it)) }).containsExactly(
            R.drawable.outline_circle_deafened,
            R.drawable.outline_circle_server_deafened,
            R.drawable.outline_circle_muted,
            R.drawable.outline_circle_server_muted,
            R.drawable.outline_circle_suppressed,
            R.drawable.outline_circle_talking_on,
            R.drawable.outline_circle_talking_off,
        ).inOrder()
    }

    @Test
    fun aDecodableAvatarIsShownAndAnythingElseIsTheRestingDot() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        show(user(1, avatar = Bytes.of(png)), user(2, avatar = Bytes.of(byteArrayOf(1, 2, 3))))

        assertThat(iconOf(1)).isInstanceOf(CircleDrawable::class.java)
        assertThat(resourceOf(iconOf(2))).isEqualTo(R.drawable.outline_circle_talking_off)
    }

    @Test
    fun aTalkStateChangeRepaintsTheIconInPlaceWithoutTouchingTheList() {
        show(channel(1), user(2), user(3))
        var notifications = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { notifications++ }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { notifications++ }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { notifications++ }
        })
        val nameBefore = row(ChannelRow.USER_ID_MASK or 2L).findViewById<TextView>(R.id.user_row_name)
        val otherIcon = iconOf(3)

        adapter.setTalkStates(mapOf(2 to TalkState.TALKING, 99 to TalkState.TALKING))

        assertThat(notifications).isEqualTo(0)
        assertThat(list.isLayoutRequested).isFalse()
        assertThat(resourceOf(iconOf(2))).isEqualTo(R.drawable.outline_circle_talking_on)
        assertThat(ViewCompat.getStateDescription(row(ChannelRow.USER_ID_MASK or 2L)))
            .isEqualTo(context.getString(R.string.a11y_state_talking))
        assertThat(row(ChannelRow.USER_ID_MASK or 2L).findViewById<TextView>(R.id.user_row_name))
            .isSameInstanceAs(nameBefore)
        assertThat(iconOf(3)).isSameInstanceAs(otherIcon)

        adapter.setTalkStates(emptyMap())
        assertThat(resourceOf(iconOf(2))).isEqualTo(R.drawable.outline_circle_talking_off)
    }

    /** A row kept off screen without being bound again catches up when it comes back. */
    @Test
    fun aRowThatMissedATalkStateChangeCatchesUpWhenItComesBack() {
        show(user(2))
        val holder = adapter.onCreateViewHolder(list, R.layout.channel_user_row)
        adapter.onBindViewHolder(holder, 0)

        adapter.setTalkStates(mapOf(2 to TalkState.TALKING))
        adapter.onViewAttachedToWindow(holder)

        val icon = holder.itemView.findViewById<ImageView>(R.id.user_row_talk_highlight).drawable
        assertThat(resourceOf(icon)).isEqualTo(R.drawable.outline_circle_talking_on)
    }

    /** The icons are layer lists whose `ConstantState` is per instance: two lookups must not share one. */
    @Test
    fun twoLookupsOfOneTalkStateIconNeverShareAConstantState() {
        val first = ResourcesCompat.getDrawable(context.resources, R.drawable.outline_circle_talking_off, null)!!
        val second = ResourcesCompat.getDrawable(context.resources, R.drawable.outline_circle_talking_off, null)!!

        assertThat(first.constantState).isNotSameInstanceAs(second.constantState)
    }

    @Test
    fun aChangedRowIsRebuiltAndAnUnchangedOneIsLeftAlone() {
        show(channel(1), user(2))
        val changed = mutableListOf<Pair<Int, Boolean>>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                changed += positionStart to false
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changed += positionStart to (payload != null)
            }
        })

        show(channel(1), user(2, status = UserStatus.MUTED))
        assertThat(changed).containsExactly(1 to true)
        assertThat(resourceOf(iconOf(2))).isEqualTo(R.drawable.outline_circle_server_muted)

        changed.clear()
        show(channel(1, name = "renamed"), user(2, status = UserStatus.MUTED))
        assertThat(changed).containsExactly(0 to false)
        assertThat(adapter.positionOf(ChannelRow.USER_ID_MASK or 2L)).isEqualTo(1)
        assertThat(adapter.positionOf(ChannelRow.USER_ID_MASK or 99L)).isEqualTo(-1)
    }

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 4000
    }
}
