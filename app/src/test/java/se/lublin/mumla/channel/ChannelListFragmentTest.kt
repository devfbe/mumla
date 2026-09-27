package se.lublin.mumla.channel

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ChatTargetParentFragment
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents

/**
 * Covers the chat-target action mode, the list across disconnect and rebind, and the refusal of a
 * host or parent that cannot serve the fragment.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentTest {

    /** Records what the fragment asks the list to scroll to, without needing a laid-out list. */
    private class RecordingLayoutManager(context: android.content.Context) :
        LinearLayoutManager(context) {
        val scrolls = mutableListOf<Int>()
        override fun scrollToPosition(position: Int) {
            scrolls.add(position)
            super.scrollToPosition(position)
        }
    }

    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var parent: ChatTargetParentFragment
    private lateinit var fragment: ChannelListFragment
    private lateinit var service: IMumlaService
    private lateinit var session: IHumlaSession
    private lateinit var tree: Map<Int, FakeChannel>

    private val channelView: RecyclerView
        get() = fragment.requireView().findViewById(R.id.channelUsers)

    @Before
    fun setUp() {
        service = mockk(relaxed = true)
        session = mockk(relaxed = true)
        tree = smallTree()
        every { session.getChannel(any()) } answers { tree[firstArg<Int>()] }
        service.stubConnected(session)
        service.stubEvents()
        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        controller.get().bind(service)
        parent = ChatTargetParentFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(parent, "parent").commitNow()
        fragment = ChannelListFragment.newInstance(pinned = false)
        parent.childFragmentManager.beginTransaction().add(fragment, "list").commitNow()
    }

    /**
     * ```
     * root(0)
     *   user 200
     *   populated(2)
     *     user 100
     * ```
     */
    private fun smallTree(): Map<Int, FakeChannel> {
        val root = FakeChannel(0)
        val populated = FakeChannel(2, counters = root.counters)
        root.addSubchannel(populated)
        root.addUser(FakeUser(200))
        populated.addUser(FakeUser(100))
        return mapOf(0 to root, 2 to populated)
    }

    private val listAdapter: ChannelListAdapter
        get() = channelView.adapter as ChannelListAdapter


    private fun countChanges(): () -> Int {
        var changes = 0
        listAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                changes++
            }
        })
        return { changes }
    }

    private fun rebind() {
        controller.get().bind(null)
        controller.get().bind(service)
    }

    @Test
    fun aBoundServiceGivesTheListAnAdapter() {
        assertThat(channelView.adapter).isNotNull()
    }

    /**
     * A disconnect removes the adapter from the list; the rebind after reconnecting takes the
     * `setService` branch, which must put it back or the list stays empty.
     */
    @Test
    fun reconnectingAfterADisconnectPutsAnAdapterBackOnTheList() {
        assertThat(channelView.adapter).isNotNull()

        fragment.onServiceEvent(HumlaEvent.Disconnected(null))

        assertThat(channelView.adapter).isNull()

        rebind()

        assertThat(channelView.adapter).isNotNull()
    }

    /**
     * Only our own join scrolls the list. The position is asked for in the same turn the event
     * scheduled a rebuild, so the adapter must settle it first.
     */
    @Test
    fun ourOwnChannelChangeScrollsTheListAndSomebodyElsesDoesNot() {
        val layout = RecordingLayoutManager(controller.get())
        channelView.layoutManager = layout
        every { session.sessionId } returns 100
        idleMainLooper()

        fragment.onServiceEvent(
            HumlaEvent.UserJoinedChannel(FakeUser(200), tree.getValue(2), tree.getValue(0))
        )
        idleMainLooper()

        assertThat(layout.scrolls).isEmpty()

        fragment.onServiceEvent(
            HumlaEvent.UserJoinedChannel(FakeUser(100), tree.getValue(2), tree.getValue(0))
        )

        assertThat(layout.scrolls).containsExactly(listAdapter.getChannelPosition(2))
        assertThat(listAdapter.getChannelPosition(2)).isNotEqualTo(-1)
    }

    /** A disconnected service reports nothing, so no scroll happens. */
    @Test
    fun aJoinReportedWhileDisconnectedScrollsNothing() {
        val layout = RecordingLayoutManager(controller.get())
        channelView.layoutManager = layout
        every { session.sessionId } returns 100
        every { service.isConnected } returns false

        fragment.onServiceEvent(
            HumlaEvent.UserJoinedChannel(FakeUser(100), tree.getValue(2), tree.getValue(0))
        )
        idleMainLooper()

        assertThat(layout.scrolls).isEmpty()
    }

    /** Only a change to the preference this fragment reads reaches the adapter. */
    @Test
    fun onlyTheUserCountPreferenceReachesTheAdapter() {
        val preferences =
            PreferenceManager.getDefaultSharedPreferences(controller.get())
        val changes = countChanges()

        preferences.edit { putBoolean("some.other.preference", true) }
        idleMainLooper()

        assertThat(changes()).isEqualTo(0)

        val shown = Settings.getInstance(controller.get()).shouldShowUserCount
        preferences.edit { putBoolean(Settings.SHOW_USER_COUNT.key, !shown) }
        idleMainLooper()

        assertThat(changes()).isEqualTo(1)
    }

    /** Its argument decides whether it shows the whole tree or the pinned channels. */
    @Test
    fun thePinnedArgumentDecidesWhereTheTreeIsRooted() {
        // A fresh repository, so the pins are read again with the stub below.
        installDatabase(controller.get().database)
        every { controller.get().database.getPinnedChannels(any()) } returns listOf(2)

        val whole = ChatTargetParentFragment()
        val pinned = ChatTargetParentFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(whole, "whole-parent").add(pinned, "pinned-parent").commitNow()
        val wholeList = ChannelListFragment.newInstance(false)
        val pinnedList = ChannelListFragment.newInstance(true)
        whole.childFragmentManager.beginTransaction().add(wholeList, "whole-list").commitNow()
        pinned.childFragmentManager.beginTransaction().add(pinnedList, "pinned-list").commitNow()
        idleMainLooper()

        fun firstRow(list: ChannelListFragment): Long {
            val view = list.requireView().findViewById<RecyclerView>(R.id.channelUsers)
            return (view.adapter as ChannelListAdapter).getItemId(0)
        }
        assertThat(firstRow(wholeList)).isEqualTo(ChannelListAdapter.CHANNEL_ID_MASK or 0L)
        assertThat(firstRow(pinnedList)).isEqualTo(ChannelListAdapter.CHANNEL_ID_MASK or 2L)
    }

    /**
     * A removal reported after we were disconnected is our own removal; the model is not read.
     */
    @Test
    fun aRemovalReportedWhileDisconnectedRebuildsNothing() {
        val changes = countChanges()
        every { service.isConnected } returns false

        fragment.onServiceEvent(HumlaEvent.UserRemoved(FakeUser(200), "gone"))
        idleMainLooper()

        assertThat(changes()).isEqualTo(0)

        every { service.isConnected } returns true
        fragment.onServiceEvent(HumlaEvent.UserRemoved(FakeUser(200), "gone"))
        idleMainLooper()

        assertThat(changes()).isEqualTo(1)
    }

    /**
     * Only a set target that is this row's channel with an open action mode means "tapped the open
     * target again". Opening a mode sets the target, finishing it clears it.
     */
    @Test
    fun tappingTheOpenChannelTargetAgainClosesItAndTappingAnotherSwitchesIt() {
        val first = FakeChannel(1)
        val second = FakeChannel(2)

        // No target yet: opens one.
        fragment.onChannelClick(first)
        assertThat((parent.chatTargets.target.value as? ChatTarget.Channel)?.channel).isEqualTo(first)

        // A target, but not this channel: switches it.
        fragment.onChannelClick(second)
        assertThat((parent.chatTargets.target.value as? ChatTarget.Channel)?.channel).isEqualTo(second)

        // The open target, tapped again.
        fragment.onChannelClick(second)
        assertThat(parent.chatTargets.target.value).isNull()
    }

    /**
     * A chat target can be set from elsewhere with no action mode open here, so the tap has to open
     * one rather than swallow itself.
     */
    @Test
    fun tappingTheChannelOfATargetThisFragmentDidNotOpenOpensAModeForIt() {
        val channel = FakeChannel(1)
        parent.chatTargets.select(ChatTarget.Channel(channel))

        fragment.onChannelClick(channel)

        assertThat((parent.chatTargets.target.value as? ChatTarget.Channel)?.channel).isEqualTo(channel)

        // Now there is one to dismiss, proving a mode was opened above.
        fragment.onChannelClick(channel)

        assertThat(parent.chatTargets.target.value).isNull()
    }

    @Test
    fun tappingTheOpenUserTargetAgainClosesItAndTappingAnotherSwitchesIt() {
        val first = FakeUser(1)
        val second = FakeUser(2)

        fragment.onUserClick(first)
        assertThat((parent.chatTargets.target.value as? ChatTarget.User)?.user).isEqualTo(first)

        fragment.onUserClick(second)
        assertThat((parent.chatTargets.target.value as? ChatTarget.User)?.user).isEqualTo(second)

        fragment.onUserClick(second)
        assertThat(parent.chatTargets.target.value).isNull()
    }

    @Test
    fun tappingTheUserOfATargetThisFragmentDidNotOpenOpensAModeForIt() {
        val user = FakeUser(1)
        parent.chatTargets.select(ChatTarget.User(user))

        fragment.onUserClick(user)

        assertThat((parent.chatTargets.target.value as? ChatTarget.User)?.user).isEqualTo(user)

        fragment.onUserClick(user)

        assertThat(parent.chatTargets.target.value).isNull()
    }

    /**
     * A channel target and a user target differ even when one tap follows the other
     * (`current.channel` is null on a user target).
     */
    @Test
    fun aUserTargetIsNotTheChannelTargetOfTheSameTap() {
        val channel = FakeChannel(1)

        fragment.onUserClick(FakeUser(1))
        fragment.onChannelClick(channel)

        assertThat((parent.chatTargets.target.value as? ChatTarget.Channel)?.channel).isSameInstanceAs(channel)
    }

    @Test
    fun aChannelTargetIsNotTheUserTargetOfTheSameTap() {
        val user = FakeUser(1)

        fragment.onChannelClick(FakeChannel(1))
        fragment.onUserClick(user)

        assertThat((parent.chatTargets.target.value as? ChatTarget.User)?.user).isSameInstanceAs(user)
    }
}
