package se.lublin.mumla.channel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * Covers the chat-target action mode, the list across disconnect and rebind, and the refusal of a
 * host or parent that cannot serve the fragment.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentTest {

    class HostActivity : AppCompatActivity(), HumlaServiceProvider, DatabaseProvider {
        // Not `var service`/`var database`: both would generate the interface's own accessor.
        private var bound: IMumlaService? = null
        private val db: MumlaDatabase = mockk(relaxed = true)

        fun bind(service: IMumlaService?) {
            bound = service
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }

        override fun getService(): IMumlaService? = bound
        override fun addServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun removeServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun getDatabase(): MumlaDatabase = db

        fun databaseMock(): MumlaDatabase = db
    }

    /** A host that can bind the service but cannot hand out a database. */
    class ServiceOnlyActivity : AppCompatActivity(), HumlaServiceProvider {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }

        override fun getService(): IMumlaService? = null
        override fun addServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun removeServiceFragment(fragment: HumlaServiceFragment) = Unit
    }

    /** The parent the fragment demands: chat targets live above it, not in it. */
    class HostParent : Fragment(), ChatTargetProvider {
        private var target: ChatTargetProvider.ChatTarget? = null
        override fun getChatTarget(): ChatTargetProvider.ChatTarget? = target
        override fun setChatTarget(target: ChatTargetProvider.ChatTarget?) {
            this.target = target
        }

        override fun registerChatTargetListener(
            listener: ChatTargetProvider.OnChatTargetSelectedListener,
        ) = Unit

        override fun unregisterChatTargetListener(
            listener: ChatTargetProvider.OnChatTargetSelectedListener,
        ) = Unit
    }

    /** Records what the fragment asks the list to scroll to, without needing a laid-out list. */
    private class RecordingLayoutManager(context: android.content.Context) :
        LinearLayoutManager(context) {
        val scrolls = mutableListOf<Int>()
        override fun scrollToPosition(position: Int) {
            scrolls.add(position)
            super.scrollToPosition(position)
        }
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var parent: HostParent
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
        every { service.isConnected } returns true
        every { service.HumlaSession() } returns session
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        controller.get().bind(service)
        parent = HostParent()
        controller.get().supportFragmentManager.beginTransaction()
            .add(parent, "parent").commitNow()
        fragment = ChannelListFragment()
        fragment.arguments = Bundle().apply { putBoolean("pinned", false) }
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
        fragment.setServiceBound(false)
        fragment.setServiceBound(true)
    }

    private fun newListFragment() = ChannelListFragment().apply {
        arguments = Bundle().apply { putBoolean("pinned", false) }
    }

    /** A host or parent that cannot serve the fragment is refused at once, naming the gap. */
    @Test
    fun aParentThatCannotProvideChatTargetsIsRefusedWhenTheFragmentAttaches() {
        val plainParent = Fragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(plainParent, "plain").commitNow()

        val thrown = assertThrows(ClassCastException::class.java) {
            plainParent.childFragmentManager.beginTransaction()
                .add(newListFragment(), "list").commitNow()
        }

        assertThat(thrown).hasMessageThat().contains("ChatTargetProvider")
    }

    @Test
    fun aHostThatCannotProvideADatabaseIsRefusedWhenTheFragmentAttaches() {
        val host = Robolectric.buildActivity(ServiceOnlyActivity::class.java).setup().get()
        val chatParent = HostParent()
        host.supportFragmentManager.beginTransaction().add(chatParent, "parent").commitNow()

        val thrown = assertThrows(ClassCastException::class.java) {
            chatParent.childFragmentManager.beginTransaction()
                .add(newListFragment(), "list").commitNow()
        }

        assertThat(thrown).hasMessageThat().contains("DatabaseProvider")
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

        fragment.serviceObserver.onDisconnected(null)

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

        fragment.serviceObserver.onUserJoinedChannel(
            FakeUser(200), tree.getValue(2), tree.getValue(0)
        )
        idleMainLooper()

        assertThat(layout.scrolls).isEmpty()

        fragment.serviceObserver.onUserJoinedChannel(
            FakeUser(100), tree.getValue(2), tree.getValue(0)
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

        fragment.serviceObserver.onUserJoinedChannel(
            FakeUser(100), tree.getValue(2), tree.getValue(0)
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

        fragment.onSharedPreferenceChanged(preferences, "some.other.preference")

        assertThat(changes()).isEqualTo(0)

        fragment.onSharedPreferenceChanged(preferences, Settings.PREF_SHOW_USER_COUNT)

        assertThat(changes()).isEqualTo(1)
    }

    /** Its argument decides whether it shows the whole tree or the pinned channels. */
    @Test
    fun thePinnedArgumentDecidesWhereTheTreeIsRooted() {
        verify(exactly = 0) { controller.get().databaseMock().getPinnedChannels(any()) }

        val pinnedParent = HostParent()
        controller.get().supportFragmentManager.beginTransaction()
            .add(pinnedParent, "pinned-parent").commitNow()
        val pinned = ChannelListFragment().apply {
            arguments = Bundle().apply { putBoolean("pinned", true) }
        }
        pinnedParent.childFragmentManager.beginTransaction().add(pinned, "pinned-list").commitNow()

        verify(exactly = 1) { controller.get().databaseMock().getPinnedChannels(any()) }
    }

    /**
     * A removal reported after we were disconnected is our own removal; the model is not read.
     */
    @Test
    fun aRemovalReportedWhileDisconnectedRebuildsNothing() {
        val changes = countChanges()
        every { service.isConnected } returns false

        fragment.serviceObserver.onUserRemoved(FakeUser(200), "gone")
        idleMainLooper()

        assertThat(changes()).isEqualTo(0)

        every { service.isConnected } returns true
        fragment.serviceObserver.onUserRemoved(FakeUser(200), "gone")
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
        assertThat(parent.chatTarget?.channel).isEqualTo(first)

        // A target, but not this channel: switches it.
        fragment.onChannelClick(second)
        assertThat(parent.chatTarget?.channel).isEqualTo(second)

        // The open target, tapped again.
        fragment.onChannelClick(second)
        assertThat(parent.chatTarget).isNull()
    }

    /**
     * A chat target can be set from elsewhere with no action mode open here, so the tap has to open
     * one rather than swallow itself.
     */
    @Test
    fun tappingTheChannelOfATargetThisFragmentDidNotOpenOpensAModeForIt() {
        val channel = FakeChannel(1)
        parent.setChatTarget(ChatTargetProvider.ChatTarget(channel))

        fragment.onChannelClick(channel)

        assertThat(parent.chatTarget?.channel).isEqualTo(channel)

        // Now there is one to dismiss, proving a mode was opened above.
        fragment.onChannelClick(channel)

        assertThat(parent.chatTarget).isNull()
    }

    @Test
    fun tappingTheOpenUserTargetAgainClosesItAndTappingAnotherSwitchesIt() {
        val first = FakeUser(1)
        val second = FakeUser(2)

        fragment.onUserClick(first)
        assertThat(parent.chatTarget?.user).isEqualTo(first)

        fragment.onUserClick(second)
        assertThat(parent.chatTarget?.user).isEqualTo(second)

        fragment.onUserClick(second)
        assertThat(parent.chatTarget).isNull()
    }

    @Test
    fun tappingTheUserOfATargetThisFragmentDidNotOpenOpensAModeForIt() {
        val user = FakeUser(1)
        parent.setChatTarget(ChatTargetProvider.ChatTarget(user))

        fragment.onUserClick(user)

        assertThat(parent.chatTarget?.user).isEqualTo(user)

        fragment.onUserClick(user)

        assertThat(parent.chatTarget).isNull()
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

        assertThat(parent.chatTarget?.channel).isSameInstanceAs(channel)
    }

    @Test
    fun aChannelTargetIsNotTheUserTargetOfTheSameTap() {
        val user = FakeUser(1)

        fragment.onChannelClick(FakeChannel(1))
        fragment.onUserClick(user)

        assertThat(parent.chatTarget?.user).isSameInstanceAs(user)
    }
}
