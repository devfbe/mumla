package se.lublin.mumla.channel

import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * The fragment had no tests at all -- 391 lines of it -- and two semantic changes went through the
 * Kotlin conversion unpinned. What is testable here without a fabricated scaffold is pinned:
 *
 * - the chat-target action mode, whose three-clause condition decides whether a tap opens a target
 *   or closes the one that is open,
 * - what happens to the list across a disconnect and a rebind, which is where a pre-existing bug
 *   left the list permanently empty,
 * - that the fragment refuses a host or a parent that cannot serve it, which the conversion turned
 *   from Java's silent null cast into an immediate ClassCastException.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentTest {

    class HostActivity : AppCompatActivity(), HumlaServiceProvider, DatabaseProvider {
        // Not `var service`/`var database`: both would generate the interface's own accessor
        // (spec 4.05).
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

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun rebind() {
        fragment.setServiceBound(false)
        fragment.setServiceBound(true)
    }

    private fun newListFragment() = ChannelListFragment().apply {
        arguments = Bundle().apply { putBoolean("pinned", false) }
    }

    /**
     * The Java read `(ChatTargetProvider) getParentFragment()` and, for a parent that is not one,
     * got a `ClassCastException` -- but it read `(DatabaseProvider) activity` into a field and
     * only failed later, somewhere else. Both refusals are immediate now, and both name what is
     * missing. Nothing said so, so this is where it is said.
     */
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
     * Pre-existing, and identical in the Java, so not a regression of this conversion -- but a
     * disconnect took the adapter off the list and left the fragment's own reference in place, and
     * the rebind that follows a reconnection takes the `setService` branch, which never puts an
     * adapter back on the `RecyclerView`. The channel list then stays empty for the rest of the
     * process, with a connected server behind it.
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
     * The list follows us and nobody else. `onUserJoinedChannel` arrives for every user in the
     * server, and the scroll belongs only to the one whose session is ours -- otherwise every join
     * anywhere on a busy server would yank the list out from under the reader.
     *
     * It is also the production path that the adapter's rebuild coalescing is built around: the
     * position is asked for in the same main-thread turn in which the event scheduled a rebuild,
     * so the answer has to settle that rebuild first or the list scrolls to where the channel used
     * to be.
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

    /** A disconnected service reports nothing, and a scroll then points at a list that is gone. */
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

    /**
     * Three clauses -- a target is set, it is this row's channel, and an action mode is open --
     * and only all three together mean "the user tapped the open target again". The observable is
     * the target itself: opening a mode sets it, finishing the mode clears it.
     */
    @Test
    fun tappingTheOpenChannelTargetAgainClosesItAndTappingAnotherSwitchesIt() {
        val first = FakeChannel(1)
        val second = FakeChannel(2)

        // No target yet: the first clause is false, so this opens one.
        fragment.onChannelClick(first)
        assertThat(parent.chatTarget?.channel).isEqualTo(first)

        // A target, but not this channel: the second clause is false, so this switches it.
        fragment.onChannelClick(second)
        assertThat(parent.chatTarget?.channel).isEqualTo(second)

        // All three: the open target, tapped again.
        fragment.onChannelClick(second)
        assertThat(parent.chatTarget).isNull()
    }

    /**
     * The third clause on its own. A chat target can be set from elsewhere -- the chat fragment
     * sets one whenever the user changes channel -- and then this fragment holds no action mode
     * to dismiss, so the tap has to open one rather than swallow itself. Without the clause the
     * tap would do nothing at all and the row would look dead.
     */
    @Test
    fun tappingTheChannelOfATargetThisFragmentDidNotOpenOpensAModeForIt() {
        val channel = FakeChannel(1)
        parent.setChatTarget(ChatTargetProvider.ChatTarget(channel))

        fragment.onChannelClick(channel)

        assertThat(parent.chatTarget?.channel).isEqualTo(channel)

        // And now there is one to dismiss, which is what proves a mode was opened above.
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
     * A channel target and a user target are different targets even when one tap follows the
     * other: `channel == current.channel` is null on a user target, and `null == null` would make
     * the two collapse into one if either side stopped being compared.
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
