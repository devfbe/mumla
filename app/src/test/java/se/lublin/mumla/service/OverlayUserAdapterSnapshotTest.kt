package se.lublin.mumla.service

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.channel.FakeChannel
import se.lublin.mumla.channel.FakeUser
import se.lublin.mumla.testing.ThemedActivity

/**
 * The overlay's user list answers about one snapshot of the channel: `getCount()` and
 * `getItem(position)` must not see different moments, or a user leaving between them throws.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayUserAdapterSnapshotTest {
    private lateinit var context: Context
    private lateinit var channel: FakeChannel
    private lateinit var users: List<FakeUser>

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        channel = FakeChannel(0)
        users = (1..3).map { FakeUser(it) }
        users.forEach { channel.addUser(it) }
    }

    @Test
    fun aUserLeavingBetweenTheCountAndTheItemDoesNotBreakTheRow() {
        val adapter = OverlayUserAdapter(context, channel)

        val count = adapter.count
        channel.removeUser(users[0])

        assertThat(adapter.getItem(count - 1)).isEqualTo(users[2])
    }

    @Test
    fun aBindReadsTheModelNotAtAll() {
        val adapter = OverlayUserAdapter(context, channel)
        channel.counters.reset()

        adapter.count
        adapter.getItem(0)
        adapter.getItemId(0)
        adapter.getView(0, null, FrameLayout(context))

        assertThat(channel.counters.getUsersCalls).isEqualTo(0)
    }

    @Test
    fun notifyDataSetChangedTakesAFreshSnapshot() {
        val adapter = OverlayUserAdapter(context, channel)
        val before = adapter.count

        channel.addUser(FakeUser(4))

        assertThat(adapter.count).isEqualTo(before)

        adapter.notifyDataSetChanged()

        assertThat(adapter.count).isEqualTo(before + 1)
    }

    @Test
    fun aFreshSnapshotIsAnnouncedToTheList() {
        val adapter = OverlayUserAdapter(context, channel)
        var changes = 0
        adapter.registerDataSetObserver(object : android.database.DataSetObserver() {
            override fun onChanged() {
                changes++
            }
        })

        adapter.notifyDataSetChanged()

        assertThat(changes).isEqualTo(1)
    }

    @Test
    fun settingAChannelSwitchesToItsUsers() {
        val adapter = OverlayUserAdapter(context, channel)
        val other = FakeChannel(1)
        other.addUser(FakeUser(9))

        adapter.setChannel(other)

        assertThat(adapter.getChannel()).isEqualTo(other)
        assertThat(adapter.count).isEqualTo(1)
        assertThat(adapter.getItem(0)).isEqualTo(other.users[0])
    }

    @Test
    fun theStateIconFollowsTheStatePriority() {
        val adapter = OverlayUserAdapter(context, channel)
        val user = users[0]

        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_talking_off)

        user.state = TalkState.TALKING
        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_talking_on)

        user.suppressed = true
        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_suppressed)

        user.muted = true
        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_server_muted)

        user.deafened = true
        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_server_deafened)

        // Unlike the channel list, the overlay puts the self flags above the server ones.
        user.selfMuted = true
        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_muted)

        user.selfDeafened = true
        assertThat(stateIcon(adapter)).isEqualTo(R.drawable.outline_circle_deafened)
    }

    @Test
    fun theRowShowsTheUsersName() {
        val adapter = OverlayUserAdapter(context, channel)
        val view = adapter.getView(1, null, FrameLayout(context))

        assertThat(view.findViewById<TextView>(R.id.user_row_name).text.toString())
            .isEqualTo(users[1].name)
    }

    private fun stateIcon(adapter: OverlayUserAdapter): Int {
        val view = adapter.getView(0, null, FrameLayout(context))
        return shadowOf(view.findViewById<ImageView>(R.id.user_row_state).drawable).createdFromResId
    }
}
