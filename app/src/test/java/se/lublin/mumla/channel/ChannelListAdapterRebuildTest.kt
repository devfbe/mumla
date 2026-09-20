package se.lublin.mumla.channel

import android.content.Context
import android.os.Bundle
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    private companion object {
        /** Tall enough for every row of [smallTree] to be laid out at once. */
        const val WIDTH_PX = 1000
        const val HEIGHT_PX = 4000
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

    /**
     * The same two-turn property across the *other* way a scheduled rebuild ends. [updateChannels]
     * clears the flag from inside the posted runnable; a position query runs that runnable by hand
     * and has to leave the flag cleared as well.
     *
     * [aBurstInALaterTurnRebuildsAgain] cannot see that: it never settles anything by hand, so a
     * mutation that re-arms the flag after a hand-run rebuild survives it. In production the first
     * hand-run settle is the first channel switch of the session
     * (`ChannelListFragment.onUserJoinedChannel` -> `scrollToChannel`) or the first search
     * suggestion clicked -- after which the list would never update again.
     */
    @Test
    fun aBurstAfterAPositionQuerySettledTheLastOneRebuildsAgain() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val changes = countChanges(adapter)

        adapter.updateChannels()
        adapter.getChannelPosition(0)
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

        // All four corners of `hasSubchannels || subtreeUserCount > 0`. The last one is what
        // separates that condition from an exclusive or, and only a test that writes it can.
        assertThat(expandToggleVisibilityOf(adapter, 1)).isEqualTo(View.VISIBLE)  // subchannel
        assertThat(expandToggleVisibilityOf(adapter, 4)).isEqualTo(View.VISIBLE)  // user
        assertThat(expandToggleVisibilityOf(adapter, 2)).isEqualTo(View.VISIBLE)  // both
        assertThat(expandToggleVisibilityOf(adapter, 3)).isEqualTo(View.INVISIBLE) // neither
    }

    /** Everything the row reads out of the session is skipped while disconnected. */
    @Test
    fun aDisconnectedServiceLeavesTheChannelRowUnmarked() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        every { session.sessionChannel } returns ids.getValue(2)
        idleMainLooper()

        every { service.isConnected } returns false

        assertThat(nameStyleOf(adapter, 2)).isEqualTo(Typeface.NORMAL)
    }

    @Test
    fun onlyOurOwnUserRowIsBold() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        every { session.sessionId } returns 100

        assertThat(userNameStyleOf(adapter, 100)).isEqualTo(Typeface.BOLD)
        assertThat(userNameStyleOf(adapter, 200)).isEqualTo(Typeface.NORMAL)

        every { service.isConnected } returns false

        assertThat(userNameStyleOf(adapter, 100)).isEqualTo(Typeface.NORMAL)
    }

    @Test
    fun theJoinButtonJoinsTheRowsChannelWhileConnected() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        joinButtonOf(adapter, 2).performClick()

        verify { session.joinChannel(2) }
    }

    @Test
    fun theJoinButtonDoesNothingWhileDisconnected() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val join = joinButtonOf(adapter, 2)

        every { service.isConnected } returns false
        join.performClick()

        verify(exactly = 0) { session.joinChannel(any()) }
    }

    private fun joinButtonOf(adapter: ChannelListAdapter, channelId: Int): android.view.View {
        val position = adapter.getChannelPosition(channelId)
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView.findViewById(R.id.channel_row_join)
    }

    private fun userNameStyleOf(adapter: ChannelListAdapter, session: Int): Int {
        val position = adapter.getUserPosition(session)
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView.findViewById<android.widget.TextView>(R.id.user_row_name)
            .typeface?.style ?: Typeface.NORMAL
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
     * `updateUserStates` is the most frequently executed code in this adapter -- every talk-state
     * and every mute/deafen change of every user goes through it, without a rebuild -- and it is
     * reachable only through a list that has an adapter attached and has been laid out, because
     * its first statement is `findViewHolderForItemId`. Every test that builds a holder by hand
     * gets `null` there and measures nothing.
     */
    @Test
    fun aTalkStateUpdateRepaintsTheRowOfTheUserItNames() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val view = attachedRecyclerView(adapter)
        val user = ids.getValue(4).getUsers().first() as FakeUser

        assertThat(talkHighlightResIdIn(view, 100))
            .isEqualTo(R.drawable.outline_circle_talking_off)

        user.state = TalkState.TALKING
        adapter.updateUserStates(user, view)

        assertThat(talkHighlightResIdIn(view, 100))
            .isEqualTo(R.drawable.outline_circle_talking_on)
    }

    /**
     * Why `updateUserStates` does not compare constant states before it repaints.
     *
     * It used to read `state != null && state != newState.constantState`, and both clauses
     * survived mutation. Measured here: the talk-state icons are layer lists, and
     * `LayerDrawable.getConstantState()` hands back its own per-instance `LayerState`, a fresh one
     * per `newDrawable()`. Two lookups of one resource therefore never share a constant state, so
     * the comparison was true on every call and the guard never once stopped a repaint -- an
     * equivalent mutant wearing a guard's clothes (spec 4.05). This test is the premise: if a
     * future resource or framework version does start sharing the state, it goes red and a real
     * guard becomes writable.
     */
    @Test
    fun twoLookupsOfOneTalkStateIconNeverShareAConstantState() {
        val res = context.resources
        val first = ResourcesCompat.getDrawable(res, R.drawable.outline_circle_talking_off, null)!!
        val second = ResourcesCompat.getDrawable(res, R.drawable.outline_circle_talking_off, null)!!

        assertThat(first.constantState).isNotNull()
        assertThat(first.constantState).isNotSameInstanceAs(second.constantState)
    }

    /** A user with no row in this list is not somebody else's row. */
    @Test
    fun aTalkStateUpdateForAUserThatIsNotShownRepaintsNothing() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val view = attachedRecyclerView(adapter)
        val stranger = FakeUser(999, state = TalkState.TALKING)
        val before = view.childCount.let { n -> (0 until n).map { view.getChildAt(it) } }
            .mapNotNull { it.findViewById<android.widget.ImageView>(R.id.user_row_talk_highlight) }
            .map { it.drawable }

        adapter.updateUserStates(stranger, view)

        val after = view.childCount.let { n -> (0 until n).map { view.getChildAt(it) } }
            .mapNotNull { it.findViewById<android.widget.ImageView>(R.id.user_row_talk_highlight) }
            .map { it.drawable }
        assertThat(after).isEqualTo(before)
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

    /**
     * The channel name carries two independent marks: bold for the channel we are in, italic for
     * a channel linked with it -- and our own channel is italic too once it has any link.
     */
    @Test
    fun theChannelNameIsBoldForOursAndItalicForALinkedOne() {
        val (root, ids) = smallTree()
        val ours = ids.getValue(2)
        val linked = ids.getValue(1)
        val adapter = adapterOver(root, ids)
        every { session.sessionChannel } returns ours

        assertThat(nameStyleOf(adapter, 2)).isEqualTo(Typeface.BOLD)
        assertThat(nameStyleOf(adapter, 1)).isEqualTo(Typeface.NORMAL)

        ours.addLink(linked)
        linked.addLink(ours)

        assertThat(nameStyleOf(adapter, 2)).isEqualTo(Typeface.BOLD_ITALIC)
        assertThat(nameStyleOf(adapter, 1)).isEqualTo(Typeface.ITALIC)
    }

    private fun nameStyleOf(adapter: ChannelListAdapter, channelId: Int): Int {
        val position = adapter.getChannelPosition(channelId)
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView.findViewById<android.widget.TextView>(R.id.channel_row_name)
            .typeface?.style ?: Typeface.NORMAL
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

    /**
     * A list the adapter is actually attached to, measured and laid out, so that the rows exist as
     * view holders the adapter can find again. [recyclerView] is a bare parent for
     * `onCreateViewHolder` and deliberately has no adapter: anything that reaches into the list
     * itself needs this one instead.
     */
    private fun attachedRecyclerView(adapter: ChannelListAdapter): RecyclerView {
        val view = recyclerView()
        view.adapter = adapter
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        idleMainLooper()
        return view
    }

    private fun talkHighlightIn(view: RecyclerView, session: Int): android.widget.ImageView {
        val itemId = session.toLong() or ChannelListAdapter.USER_ID_MASK
        val holder = requireNotNull(view.findViewHolderForItemId(itemId)) {
            "no laid-out row for user $session"
        }
        return holder.itemView.findViewById(R.id.user_row_talk_highlight)
    }

    private fun talkHighlightResIdIn(view: RecyclerView, session: Int): Int =
        shadowOf(talkHighlightIn(view, session).drawable).createdFromResId

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
