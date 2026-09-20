package se.lublin.mumla.channel

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
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
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaDatabase

/**
 * What one model event costs the main thread.
 *
 * Every observer of the model answers a channel or user event with a full rebuild of the channel
 * tree, and after stream A bounded the observer queue a large server synchronisation still
 * delivers about a thousand of those events. Measured on a 5 000-channel tree, one rebuild walked
 * 33 179 nodes -- because `getSubchannelUserCount()` re-walks the whole subtree at every node it
 * is asked about -- and the thousand-event burst cost about half a second of main thread on a
 * desktop JVM, several seconds on a phone.
 *
 * Two properties fix that, and both are pinned here: at most one rebuild per main-thread turn,
 * and one pass over the model per rebuild.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListAdapterRebuildTest {

    /** The row layouts resolve theme attributes, so they need a themed context, not the app one. */
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var context: Context
    private lateinit var session: IHumlaSession
    private lateinit var service: IHumlaService
    private lateinit var database: MumlaDatabase
    private lateinit var byId: MutableMap<Int, FakeChannel>

    private fun adapterOver(
        root: FakeChannel,
        ids: Map<Int, FakeChannel>,
        pinnedChannels: List<Int>? = null,
        showUserCount: Boolean = true,
        connected: Boolean = true,
    ): ChannelListAdapter {
        byId = ids.toMutableMap()
        session = mockk(relaxed = true)
        every { session.getChannel(any()) } answers { byId[firstArg<Int>()] }
        service = mockk(relaxed = true)
        every { service.isConnected } returns connected
        every { service.HumlaSession() } returns session
        database = mockk(relaxed = true)
        if (pinnedChannels != null) {
            every { database.getPinnedChannels(any()) } returns pinnedChannels
        }
        return ChannelListAdapter(
            context,
            service,
            database,
            mockk<FragmentManager>(relaxed = true),
            pinnedChannels != null,
            showUserCount,
        ).also { root.counters.reset() }
    }

    /**
     * ```
     * root(0)            2 users below it
     *   user 200
     *   empty(1)         0 users below it -- collapsed by default
     *     emptyChild(3)
     *   populated(2)     1 user below it
     *     deep(4)
     *       user 100
     * ```
     */
    private fun smallTree(): Pair<FakeChannel, Map<Int, FakeChannel>> {
        val counters = FakeChannel.Counters()
        val root = FakeChannel(0, counters = counters)
        val empty = FakeChannel(1, counters = counters)
        val populated = FakeChannel(2, counters = counters)
        val emptyChild = FakeChannel(3, counters = counters)
        val deep = FakeChannel(4, counters = counters)
        root.addSubchannel(empty)
        root.addSubchannel(populated)
        empty.addSubchannel(emptyChild)
        populated.addSubchannel(deep)
        deep.addUser(FakeUser(100))
        root.addUser(FakeUser(200))
        return root to mapOf(0 to root, 1 to empty, 2 to populated, 3 to emptyChild, 4 to deep)
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        idleMainLooper()
    }

    @Test
    fun aBurstOfModelEventsCostsOneRebuild() {
        val (root, ids) = buildChannelTree(channelCount = 1000, branching = 4, userEvery = 5)
        val adapter = adapterOver(root, ids)

        repeat(1024) { adapter.updateChannels() }
        idleMainLooper()

        assertThat(root.counters.getSubchannelsCalls).isEqualTo(1000)
    }

    @Test
    fun aBurstOfModelEventsNotifiesTheViewOnce() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val changes = countChanges(adapter)

        repeat(1024) { adapter.updateChannels() }
        idleMainLooper()

        assertThat(changes()).isEqualTo(1)
    }

    /** A rebuild a position query has already run must not run a second time when main idles. */
    @Test
    fun settlingAScheduledRebuildConsumesIt() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val changes = countChanges(adapter)

        adapter.updateChannels()
        adapter.getChannelPosition(0)
        idleMainLooper()

        assertThat(changes()).isEqualTo(1)
    }

    private fun countChanges(adapter: ChannelListAdapter): () -> Int {
        var changes = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                changes++
            }
        })
        return { changes }
    }

    /**
     * A burst is not a one-off: the next event, in the next turn, has to be scheduled again.
     * Found by mutation -- leaving the scheduled flag set survived every other test in this
     * class, because none of them delivered events in two separate main-thread turns, which is
     * the only thing that ever happens in production.
     */
    @Test
    fun aBurstInALaterTurnRebuildsAgain() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val changes = countChanges(adapter)

        adapter.updateChannels()
        idleMainLooper()
        val after = adapter.itemCount

        ids.getValue(0).addUser(FakeUser(300))
        adapter.updateChannels()
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(after + 1)
        assertThat(changes()).isEqualTo(2)
    }

    @Test
    fun aNewlyBoundConnectedServiceRebuildsTheList() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        val before = adapter.itemCount

        ids.getValue(0).addUser(FakeUser(300))
        every { service.connectionState } returns HumlaService.ConnectionState.CONNECTED
        adapter.setService(service)
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(before + 1)
    }

    @Test
    fun aNewlyBoundServiceThatIsNotConnectedYetRebuildsNothing() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        val before = adapter.itemCount

        ids.getValue(0).addUser(FakeUser(300))
        every { service.connectionState } returns HumlaService.ConnectionState.CONNECTING
        adapter.setService(service)
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(before)
    }

    @Test
    fun aRebuildReadsEveryChannelExactlyOnceAndNeverAsksForARecursiveCount() {
        val (root, ids) = buildChannelTree(channelCount = 1000, branching = 4, userEvery = 5)
        val adapter = adapterOver(root, ids)

        adapter.updateChannels()
        idleMainLooper()

        assertThat(root.counters.getUsersCalls).isEqualTo(1000)
        assertThat(root.counters.getSubchannelsCalls).isEqualTo(1000)
        assertThat(root.counters.subchannelUserCountCalls).isEqualTo(0)
    }

    @Test
    fun nothingIsRebuiltBeforeTheMainThreadTurnEnds() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val before = adapter.itemCount

        ids.getValue(0).addUser(FakeUser(300))
        adapter.updateChannels()

        assertThat(adapter.itemCount).isEqualTo(before)

        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(before + 1)
    }

    /**
     * Stream A's observer queue folds and drops events, so an observer may only treat an event as
     * "read the model again". The coalesced rebuild has to answer the state the model is in when
     * it finally runs, not the state that triggered the first of the folded events.
     */
    @Test
    fun theCoalescedRebuildReflectsTheLastStateAndNotTheFirstEvent() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        val transient = FakeUser(300)
        ids.getValue(0).addUser(transient)
        adapter.updateChannels()
        ids.getValue(0).removeUser(transient)
        ids.getValue(0).addUser(FakeUser(301))
        ids.getValue(0).addUser(FakeUser(302))
        adapter.updateChannels()
        idleMainLooper()

        assertThat(adapter.getUserPosition(300)).isEqualTo(-1)
        assertThat(adapter.getUserPosition(301)).isNotEqualTo(-1)
        assertThat(adapter.getUserPosition(302)).isNotEqualTo(-1)
    }

    @Test
    fun bindingAChannelRowDoesNotWalkTheTree() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val parent = recyclerView()
        val position = adapter.getChannelPosition(2)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))

        root.counters.reset()
        adapter.onBindViewHolder(holder, position)

        assertThat(root.counters.subchannelUserCountCalls).isEqualTo(0)
        assertThat(root.counters.getSubchannelsCalls).isEqualTo(0)
        assertThat(root.counters.getUsersCalls).isEqualTo(0)
    }

    @Test
    fun theChannelRowStillShowsTheRecursiveUserCount() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val parent = recyclerView()

        assertThat(userCountTextAt(adapter, parent, adapter.getChannelPosition(0))).isEqualTo("2")
        assertThat(userCountTextAt(adapter, parent, adapter.getChannelPosition(1))).isEqualTo("0")
        assertThat(userCountTextAt(adapter, parent, adapter.getChannelPosition(2))).isEqualTo("1")
    }

    @Test
    fun anEmptySubtreeStaysCollapsedAndAPopulatedOneIsExpanded() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        // root, its user, the empty channel (no children shown), the populated one and its subtree
        assertThat(adapter.itemCount).isEqualTo(6)
        assertThat(adapter.getChannelPosition(3)).isEqualTo(-1)
        assertThat(adapter.getChannelPosition(4)).isNotEqualTo(-1)
        assertThat(adapter.getUserPosition(100)).isNotEqualTo(-1)
    }

    @Test
    fun theNodeOrderIsChannelThenItsUsersThenItsSubchannels() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        assertThat(
            listOf(
                adapter.getChannelPosition(0),
                adapter.getUserPosition(200),
                adapter.getChannelPosition(1),
                adapter.getChannelPosition(2),
                adapter.getChannelPosition(4),
                adapter.getUserPosition(100),
            )
        ).isEqualTo(listOf(0, 1, 2, 3, 4, 5))
    }

    @Test
    fun anExplicitExpansionOverridesTheEmptySubtreeDefault() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        clickExpandToggle(adapter, adapter.getChannelPosition(1))

        assertThat(adapter.getChannelPosition(3)).isNotEqualTo(-1)
    }

    @Test
    fun anExplicitContractionOverridesThePopulatedSubtreeDefault() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        clickExpandToggle(adapter, adapter.getChannelPosition(2))

        assertThat(adapter.getChannelPosition(4)).isEqualTo(-1)
        assertThat(adapter.getUserPosition(100)).isEqualTo(-1)
        // Its own row survives, still carrying the count of the subtree it hides.
        assertThat(adapter.getChannelPosition(2)).isNotEqualTo(-1)
        assertThat(userCountTextAt(adapter, recyclerView(), adapter.getChannelPosition(2)))
            .isEqualTo("1")
    }

    /**
     * `onUserJoinedChannel` asks for a position in the same turn in which it reports the join. A
     * scheduled rebuild has to be settled before the answer, or the fragment scrolls to where the
     * channel used to be.
     */
    @Test
    fun aChannelPositionQuerySettlesAScheduledRebuildFirst() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        ids.getValue(0).addSubchannel(newcomerBelowRoot(root, ids))
        adapter.updateChannels()

        assertThat(adapter.getChannelPosition(5)).isNotEqualTo(-1)
    }

    @Test
    fun aUserPositionQuerySettlesAScheduledRebuildFirst() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        ids.getValue(0).addSubchannel(newcomerBelowRoot(root, ids))
        adapter.updateChannels()

        assertThat(adapter.getUserPosition(400)).isNotEqualTo(-1)
    }

    @Test
    fun aPositionQueryOnASettledAdapterRebuildsNothing() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        root.counters.reset()

        adapter.getChannelPosition(2)
        adapter.getUserPosition(100)

        assertThat(root.counters.getSubchannelsCalls).isEqualTo(0)
        assertThat(root.counters.getUsersCalls).isEqualTo(0)
    }

    /**
     * The expand toggle is hidden for a channel that can show nothing. Both halves of that
     * decision matter: channel 1 holds no users at all but has a subchannel, channel 4 has no
     * subchannel but holds a user, and channel 3 has neither.
     */
    @Test
    fun theExpandToggleIsShownForASubchannelAndForAUserAndHiddenForNeither() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        clickExpandToggle(adapter, adapter.getChannelPosition(1))

        assertThat(expandToggleVisibilityOf(adapter, 1)).isEqualTo(View.VISIBLE)
        assertThat(expandToggleVisibilityOf(adapter, 4)).isEqualTo(View.VISIBLE)
        assertThat(expandToggleVisibilityOf(adapter, 3)).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun theUserCountIsHiddenWhenTheSettingIsOff() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids, showUserCount = false)

        assertThat(userCountViewAt(adapter, adapter.getChannelPosition(0)).visibility)
            .isEqualTo(View.GONE)

        adapter.setShowChannelUserCount(true)

        assertThat(userCountViewAt(adapter, adapter.getChannelPosition(0)).visibility)
            .isEqualTo(View.VISIBLE)
    }

    @Test
    fun aPinnedListIsRootedInThePinnedChannelsRatherThanInTheRoot() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids, pinnedChannels = listOf(2))

        assertThat(adapter.getChannelPosition(0)).isEqualTo(-1)
        assertThat(adapter.getChannelPosition(2)).isEqualTo(0)
        assertThat(adapter.getUserPosition(100)).isNotEqualTo(-1)
    }

    @Test
    fun aRebuildWhileDisconnectedLeavesTheListAsItWas() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        val before = adapter.itemCount

        every { service.isConnected } returns false
        ids.getValue(0).addUser(FakeUser(300))
        adapter.updateChannels()
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(before)
    }

    /**
     * The talk-state icon is a priority list, not a set of independent flags: a user who is both
     * self-muted and server-deafened shows the deafened icon. The order is pinned here because the
     * Kotlin conversion rewrote the if-chain as a `when`.
     */
    @Test
    fun theTalkStateIconFollowsTheStatePriority() {
        val (root, ids) = smallTree()
        val user = ids.getValue(4).getUsers().first() as FakeUser
        val adapter = adapterOver(root, ids)

        assertThat(talkStateDrawableOf(adapter)).isEqualTo(R.drawable.outline_circle_talking_off)

        user.state = TalkState.TALKING
        assertThat(talkStateDrawableOf(adapter)).isEqualTo(R.drawable.outline_circle_talking_on)

        user.suppressed = true
        assertThat(talkStateDrawableOf(adapter)).isEqualTo(R.drawable.outline_circle_suppressed)

        user.muted = true
        assertThat(talkStateDrawableOf(adapter))
            .isEqualTo(R.drawable.outline_circle_server_muted)

        user.selfMuted = true
        assertThat(talkStateDrawableOf(adapter)).isEqualTo(R.drawable.outline_circle_muted)

        user.deafened = true
        assertThat(talkStateDrawableOf(adapter))
            .isEqualTo(R.drawable.outline_circle_server_deafened)

        user.selfDeafened = true
        assertThat(talkStateDrawableOf(adapter)).isEqualTo(R.drawable.outline_circle_deafened)
    }

    private fun talkStateDrawableOf(adapter: ChannelListAdapter): Int {
        val position = adapter.getUserPosition(100)
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        val drawable = holder.itemView
            .findViewById<android.widget.ImageView>(R.id.user_row_talk_highlight).drawable
        return shadowOf(drawable).createdFromResId
    }

    private fun newcomerBelowRoot(root: FakeChannel, ids: Map<Int, FakeChannel>): FakeChannel {
        val newcomer = FakeChannel(5, counters = root.counters)
        newcomer.addUser(FakeUser(400))
        byId[5] = newcomer
        return newcomer
    }

    private fun expandToggleVisibilityOf(adapter: ChannelListAdapter, channelId: Int): Int {
        val position = adapter.getChannelPosition(channelId)
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView.findViewById<android.widget.ImageView>(R.id.channel_row_expand)
            .visibility
    }

    private fun userCountViewAt(adapter: ChannelListAdapter, position: Int): android.widget.TextView {
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView.findViewById(R.id.channel_row_count)
    }

    private fun clickExpandToggle(adapter: ChannelListAdapter, position: Int) {
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        holder.itemView.findViewById<android.widget.ImageView>(R.id.channel_row_expand)
            .performClick()
        idleMainLooper()
    }

    private fun recyclerView() =
        RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }

    private fun userCountTextAt(
        adapter: ChannelListAdapter,
        parent: RecyclerView,
        position: Int,
    ): String {
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView
            .findViewById<android.widget.TextView>(R.id.channel_row_count)
            .text.toString()
    }
}
