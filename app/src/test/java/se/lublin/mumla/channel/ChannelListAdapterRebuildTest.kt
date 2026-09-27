package se.lublin.mumla.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.drawable.CircleDrawable
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Model events trigger at most one rebuild per main-thread turn, and each rebuild makes one pass
 * over the model.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListAdapterRebuildTest {
    private companion object {
        /** Tall enough for every row of [smallTree] to be laid out at once. */
        const val WIDTH_PX = 1000
        const val HEIGHT_PX = 4000
        const val SERVER_ID = 42L
    }

    /** The row layouts resolve theme attributes, so they need a themed context, not the app one. */
    private lateinit var context: Context
    private lateinit var session: IHumlaSession
    private lateinit var database: MumlaDatabase
    private lateinit var server: Server
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
        server = mockk(relaxed = true)
        every { server.id } returns SERVER_ID
        every { server.isSaved } returns true
        session.stubState(if (connected) SessionState.Connected else SessionState.Disconnected())
        every { session.targetServer } returns server
        database = mockk(relaxed = true)
        if (pinnedChannels != null) {
            every { database.getPinnedChannels(any()) } returns pinnedChannels
        }
        return ChannelListAdapter(
            context,
            session,
            // Inline, so the database work is deterministic in tests.
            MumlaRepository(database, Dispatchers.Unconfined),
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

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
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

    /** The scheduled flag is cleared, so an event in a later turn is scheduled again. */
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
     * A position query that runs the scheduled rebuild by hand must also clear the flag, or the
     * list would never update again after the first channel switch or search suggestion.
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
    fun aNewlyBoundConnectedSessionRebuildsTheList() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        val before = adapter.itemCount

        ids.getValue(0).addUser(FakeUser(300))
        adapter.setSession(session)
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(before + 1)
    }

    @Test
    fun aNewlyBoundSessionThatIsNotConnectedYetRebuildsNothing() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        val before = adapter.itemCount

        ids.getValue(0).addUser(FakeUser(300))
        session.stubState(SessionState.Connecting)
        adapter.setSession(session)
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

    /**
     * A collapsed channel is still walked to count its users; only its rows are dropped, so one
     * visible row can still read the whole model.
     */
    @Test
    fun aCollapsedSubtreeIsStillWalkedBecauseItsUsersStillHaveToBeCounted() {
        val (root, ids) = buildChannelTree(channelCount = 1000, branching = 4, userEvery = 5)
        val adapter = adapterOver(root, ids)
        clickExpandToggle(adapter, adapter.getChannelPosition(0))
        root.counters.reset()

        adapter.updateChannels()
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(1)
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
     * Folded events only mean "read the model again": the coalesced rebuild reflects the model
     * when it runs, not the event that scheduled it.
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
     * `onUserJoinedChannel` asks for a position in the same turn it reports the join, so a
     * scheduled rebuild must be settled before answering.
     */
    @Test
    fun aChannelPositionQuerySettlesAScheduledRebuildFirst() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        ids.getValue(0).addSubchannel(newcomerBelowRoot(root))
        adapter.updateChannels()

        assertThat(adapter.getChannelPosition(5)).isNotEqualTo(-1)
    }

    @Test
    fun aUserPositionQuerySettlesAScheduledRebuildFirst() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        ids.getValue(0).addSubchannel(newcomerBelowRoot(root))
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
     * The expand toggle is hidden only for a channel with neither subchannels nor users below it.
     */
    @Test
    fun theExpandToggleIsShownForASubchannelAndForAUserAndHiddenForNeither() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        clickExpandToggle(adapter, adapter.getChannelPosition(1))

        // All four corners of `hasSubchannels || subtreeUserCount > 0`, for visibility and enabled.
        assertThat(expandToggleOf(adapter, 1).visibility).isEqualTo(View.VISIBLE)   // subchannel
        assertThat(expandToggleOf(adapter, 4).visibility).isEqualTo(View.VISIBLE)   // user
        assertThat(expandToggleOf(adapter, 2).visibility).isEqualTo(View.VISIBLE)   // both
        assertThat(expandToggleOf(adapter, 3).visibility).isEqualTo(View.INVISIBLE) // neither

        assertThat(expandToggleOf(adapter, 1).isEnabled).isTrue()
        assertThat(expandToggleOf(adapter, 4).isEnabled).isTrue()
        assertThat(expandToggleOf(adapter, 2).isEnabled).isTrue()
        assertThat(expandToggleOf(adapter, 3).isEnabled).isFalse()
    }

    @Test
    fun theExpandToggleChevronShowsWhetherTheRowIsOpen() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        assertThat(expandToggleImageOf(adapter, 2)).isEqualTo(R.drawable.ic_action_expanded)
        assertThat(expandToggleImageOf(adapter, 1)).isEqualTo(R.drawable.ic_action_collapsed)

        clickExpandToggle(adapter, adapter.getChannelPosition(2))
        clickExpandToggle(adapter, adapter.getChannelPosition(1))

        assertThat(expandToggleImageOf(adapter, 2)).isEqualTo(R.drawable.ic_action_collapsed)
        assertThat(expandToggleImageOf(adapter, 1)).isEqualTo(R.drawable.ic_action_expanded)
    }

    @Test
    fun aRowCarriesTheNameOfTheChannelOrUserItShows() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        assertThat(channelRowTextOf(adapter, 2, R.id.channel_row_name)).isEqualTo("channel-2")
        assertThat(userRowTextOf(adapter, 100, R.id.user_row_name)).isEqualTo("user-100")
    }

    /** The indent makes the flat list read as a tree; a user row sits one step further in. */
    @Test
    fun aRowIsIndentedByItsDepthInTheTree() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        assertThat(channelRowPaddingOf(adapter, 0)).isEqualTo(indentPx(0))
        assertThat(channelRowPaddingOf(adapter, 2)).isEqualTo(indentPx(1))
        assertThat(channelRowPaddingOf(adapter, 4)).isEqualTo(indentPx(2))
        assertThat(userRowPaddingOf(adapter, 200)).isEqualTo(indentPx(1))
        assertThat(userRowPaddingOf(adapter, 100)).isEqualTo(indentPx(3))
    }

    /**
     * A long press on a row delegates to the row's overflow button. The listener is replaced, as
     * the popup it would open belongs to `ChannelMenu` / `UserMenu`.
     */
    @Test
    fun aLongPressOnARowIsATapOnItsOverflowButton() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        val rows = listOf(
            rowOf(adapter, adapter.getChannelPosition(2)) to R.id.channel_row_more,
            rowOf(adapter, adapter.getUserPosition(100)) to R.id.user_row_more,
        )

        for ((row, overflowId) in rows) {
            var taps = 0
            row.findViewById<View>(overflowId).setOnClickListener { taps++ }

            assertThat(row.performLongClick()).isTrue()

            assertThat(taps).isEqualTo(1)
        }
    }

    /**
     * A null in `getUsers()` gets no row but is still counted, matching
     * `Channel.subchannelUserCount` (`_users.size()`).
     */
    @Test
    fun aUserTheModelHasNotFilledInYetIsCountedButGetsNoRow() {
        val root = FakeChannel(0)
        root.addUser(FakeUser(200))
        root.addAbsentUser()
        val adapter = adapterOver(root, mapOf(0 to root))

        assertThat(adapter.itemCount).isEqualTo(2)
        assertThat(adapter.getUserPosition(200)).isEqualTo(1)
        assertThat(userCountTextAt(adapter, recyclerView(), adapter.getChannelPosition(0)))
            .isEqualTo("2")
    }

    @Test
    fun tappingARowReportsTheChannelOrTheUserItShows() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val channels = mutableListOf<Int>()
        val users = mutableListOf<Int>()
        adapter.onChannelClick = { channels.add(it.id) }
        adapter.onUserClick = { users.add(it.session) }

        rowOf(adapter, adapter.getChannelPosition(2)).performClick()
        rowOf(adapter, adapter.getChannelPosition(1)).performClick()
        rowOf(adapter, adapter.getUserPosition(100)).performClick()

        assertThat(channels).containsExactly(2, 1).inOrder()
        assertThat(users).containsExactly(100)
    }

    @Test
    fun aDisconnectedSessionLeavesTheChannelRowUnmarked() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        every { session.sessionChannel } returns ids.getValue(2)
        idleMainLooper()

        session.stubState(SessionState.Disconnected())

        assertThat(nameStyleOf(adapter, 2)).isEqualTo(Typeface.NORMAL)
    }

    @Test
    fun onlyOurOwnUserRowIsBold() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        every { session.sessionId } returns 100

        assertThat(userNameStyleOf(adapter, 100)).isEqualTo(Typeface.BOLD)
        assertThat(userNameStyleOf(adapter, 200)).isEqualTo(Typeface.NORMAL)

        session.stubState(SessionState.Disconnected())

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

        session.stubState(SessionState.Disconnected())
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

    /**
     * The setting is read at bind time, so the rows already on screen are redrawn by the change
     * notification, not by the flag.
     */
    @Test
    fun theUserCountIsHiddenWhenTheSettingIsOff() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids, showUserCount = false)
        val changes = countChanges(adapter)

        assertThat(userCountViewAt(adapter, adapter.getChannelPosition(0)).visibility)
            .isEqualTo(View.GONE)

        adapter.setShowChannelUserCount(true)

        assertThat(changes()).isEqualTo(1)
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

        session.stubState(SessionState.Disconnected())
        ids.getValue(0).addUser(FakeUser(300))
        adapter.updateChannels()
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(before)
    }

    /**
     * `updateUserStates` starts with `findViewHolderForItemId`, so it is only reachable through an
     * attached, laid-out list; hand-built holders get `null`.
     */
    @Test
    fun aTalkStateUpdateRepaintsTheRowOfTheUserItNames() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val view = attachedRecyclerView(adapter)
        val user = ids.getValue(4).users.first() as FakeUser

        assertThat(talkHighlightResIdIn(view, 100))
            .isEqualTo(R.drawable.outline_circle_talking_off)

        user.state = TalkState.TALKING
        adapter.updateUserStates(user, view)

        assertThat(talkHighlightResIdIn(view, 100))
            .isEqualTo(R.drawable.outline_circle_talking_on)
    }

    /**
     * Why `updateUserStates` does not compare constant states before repainting: the talk-state
     * icons are layer lists, and `LayerDrawable.getConstantState()` returns a fresh `LayerState`
     * per instance, so two lookups of one resource never share one. If this starts failing, a
     * constant-state guard becomes meaningful.
     */
    @Test
    fun twoLookupsOfOneTalkStateIconNeverShareAConstantState() {
        val res = context.resources
        val first = ResourcesCompat.getDrawable(res, R.drawable.outline_circle_talking_off, null)!!
        val second = ResourcesCompat.getDrawable(res, R.drawable.outline_circle_talking_off, null)!!

        assertThat(first.constantState).isNotNull()
        assertThat(first.constantState).isNotSameInstanceAs(second.constantState)
    }

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
     * The local mute/ignore history is kept per registered account on a saved server, so both
     * conditions must hold before anything is written. Uses the inline executor so each corner
     * is deterministic.
     */
    @Test
    fun theLocalMuteHistoryIsWrittenOnlyForARegisteredUserOnASavedServer() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)

        // Neither clause.
        every { server.isSaved } returns false
        adapter.onLocalUserStateUpdated(FakeUser(500, userId = -1))
        // The right clause alone: an anonymous user on a saved server has no row to keep.
        every { server.isSaved } returns true
        adapter.onLocalUserStateUpdated(FakeUser(501, userId = -1))
        // The left clause alone: a registered user on a server we do not store.
        every { server.isSaved } returns false
        adapter.onLocalUserStateUpdated(FakeUser(502, userId = 7))

        verify(exactly = 0) { database.removeLocalMutedUser(any(), any()) }
        verify(exactly = 0) { database.removeLocalIgnoredUser(any(), any()) }

        // Both.
        every { server.isSaved } returns true
        adapter.onLocalUserStateUpdated(FakeUser(503, userId = 7))

        verify(exactly = 1) { database.removeLocalMutedUser(SERVER_ID, 7) }
        verify(exactly = 1) { database.removeLocalIgnoredUser(SERVER_ID, 7) }
    }

    /** Muting and ignoring are stored separately, and each one is added or removed, never both. */
    @Test
    fun theLocalMuteAndIgnoreRowsFollowTheUsersOwnFlags() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val user = FakeUser(504, userId = 11)

        user.isLocalMuted = true
        adapter.onLocalUserStateUpdated(user)

        verify(exactly = 1) { database.addLocalMutedUser(SERVER_ID, 11) }
        verify(exactly = 1) { database.removeLocalIgnoredUser(SERVER_ID, 11) }
        verify(exactly = 0) { database.removeLocalMutedUser(any(), any()) }
        verify(exactly = 0) { database.addLocalIgnoredUser(any(), any()) }

        user.isLocalMuted = false
        user.isLocalIgnored = true
        adapter.onLocalUserStateUpdated(user)

        verify(exactly = 1) { database.removeLocalMutedUser(SERVER_ID, 11) }
        verify(exactly = 1) { database.addLocalIgnoredUser(SERVER_ID, 11) }
    }

    /** The local volume is stored by identity, also for unregistered users and unsaved servers. */
    @Test
    fun theLocalVolumeIsStoredForAnyIdentifiableUser() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        every { server.isSaved } returns false
        every { server.host } returns "example.org"
        every { server.port } returns 64738
        val user = FakeUser(506, name = "Bob").apply { localVolume = 1.5f }

        adapter.onLocalUserStateUpdated(user)

        verify(exactly = 1) { database.setLocalVolume("name:example.org:64738:Bob", 1.5f) }
    }

    /**
     * The list is redrawn for every local state change, whether or not it is persisted: local mute
     * is what the row shows, and a server we do not store still shows it.
     */
    @Test
    fun aLocalStateChangeRedrawsTheListEvenWhenNothingIsStored() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        val changes = countChanges(adapter)
        every { server.isSaved } returns false

        adapter.onLocalUserStateUpdated(FakeUser(505, userId = -1))

        assertThat(changes()).isEqualTo(1)
        verify(exactly = 0) { database.removeLocalMutedUser(any(), any()) }
    }

    /**
     * The repository's default dispatcher writes off the calling (main) thread. Deterministic because the
     * write itself releases the latch.
     */
    @Test
    fun theLocalStateIsWrittenOffTheCallingThread() {
        val (root, ids) = smallTree()
        val database = mockk<MumlaDatabase>(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        every { server.id } returns SERVER_ID
        every { server.isSaved } returns true
        val pinnedSession = mockk<IHumlaSession>(relaxed = true).stubConnected()
        every { pinnedSession.targetServer } returns server
        val adapter = ChannelListAdapter(
            context, pinnedSession, MumlaRepository(database), mockk<FragmentManager>(relaxed = true), false, true,
        )
        val done = CountDownLatch(1)
        val writer = arrayOfNulls<String>(1)
        every { database.removeLocalMutedUser(any(), any()) } answers {
            writer[0] = Thread.currentThread().name.substringBefore(" @coroutine#")
            done.countDown()
        }

        adapter.onLocalUserStateUpdated(FakeUser(506, userId = 13))

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(writer[0]).isNotEqualTo(
            Thread.currentThread().name.substringBefore(" @coroutine#")
        )
    }

    /**
     * A user with a decodable texture gets a [CircleDrawable] of it; a user without one gets the
     * resting dot.
     */
    @Test
    fun aDecodableTextureBecomesTheUsersAvatarAndAnythingElseIsTheRestingDot() {
        val (root, ids) = smallTree()
        val channel = ids.getValue(4)

        channel.removeUser(channel.users.first())
        channel.addUser(FakeUser(100, texture = pngBytes()))
        assertThat(talkHighlightDrawableOf(adapterOver(root, ids)))
            .isInstanceOf(CircleDrawable::class.java)

        channel.removeUser(channel.users.first())
        channel.addUser(FakeUser(100))
        assertThat(talkStateDrawableOf(adapterOver(root, ids)))
            .isEqualTo(R.drawable.outline_circle_talking_off)
    }

    /**
     * A texture that does not decode falls through to the resting dot. Needs real graphics:
     * Robolectric's legacy `BitmapFactory` decodes any bytes.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTextureThatDoesNotDecodeFallsBackToTheRestingDot() {
        val (root, ids) = smallTree()
        val channel = ids.getValue(4)
        channel.removeUser(channel.users.first())
        channel.addUser(FakeUser(100, texture = byteArrayOf(1, 2, 3)))

        assertThat(talkStateDrawableOf(adapterOver(root, ids)))
            .isEqualTo(R.drawable.outline_circle_talking_off)
    }

    /** Every session read in bind is wrapped, so a row still binds when the model goes away. */
    @Test
    fun aSessionThatThrowsMidBindStillProducesARow() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        every { session.sessionChannel } throws IllegalStateException("not synchronized")
        every { session.sessionId } throws IllegalStateException("gone")

        assertThat(nameStyleOf(adapter, 2)).isEqualTo(Typeface.NORMAL)
        assertThat(userNameStyleOf(adapter, 100)).isEqualTo(Typeface.NORMAL)
    }

    /** A rebuild that throws mid-walk keeps the rows built so far instead of crashing. */
    @Test
    fun aModelThatThrowsMidRebuildLeavesTheListStanding() {
        val (root, ids) = smallTree()
        val adapter = adapterOver(root, ids)
        idleMainLooper()
        every { session.getChannel(any()) } throws IllegalStateException("not synchronized")

        adapter.updateChannels()
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(0)
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

    private fun talkStateDrawableOf(adapter: ChannelListAdapter): Int =
        shadowOf(talkHighlightDrawableOf(adapter)).createdFromResId

    private fun talkHighlightDrawableOf(adapter: ChannelListAdapter): Drawable =
        rowOf(adapter, adapter.getUserPosition(100))
            .findViewById<android.widget.ImageView>(R.id.user_row_talk_highlight).drawable

    /** A four-pixel image, so that the decode has something real to succeed on. */
    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return bytes.toByteArray()
    }

    private fun newcomerBelowRoot(root: FakeChannel): FakeChannel {
        val newcomer = FakeChannel(5, counters = root.counters)
        newcomer.addUser(FakeUser(400))
        byId[5] = newcomer
        return newcomer
    }

    private fun rowOf(adapter: ChannelListAdapter, position: Int): View {
        val parent = recyclerView()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder.itemView
    }

    private fun expandToggleOf(adapter: ChannelListAdapter, channelId: Int) =
        rowOf(adapter, adapter.getChannelPosition(channelId))
            .findViewById<android.widget.ImageView>(R.id.channel_row_expand)

    private fun expandToggleImageOf(adapter: ChannelListAdapter, channelId: Int): Int =
        shadowOf(expandToggleOf(adapter, channelId).drawable).createdFromResId

    private fun channelRowTextOf(adapter: ChannelListAdapter, channelId: Int, id: Int): String =
        rowOf(adapter, adapter.getChannelPosition(channelId))
            .findViewById<android.widget.TextView>(id).text.toString()

    private fun userRowTextOf(adapter: ChannelListAdapter, session: Int, id: Int): String =
        rowOf(adapter, adapter.getUserPosition(session))
            .findViewById<android.widget.TextView>(id).text.toString()

    private fun channelRowPaddingOf(adapter: ChannelListAdapter, channelId: Int): Int =
        rowOf(adapter, adapter.getChannelPosition(channelId))
            .findViewById<View>(R.id.channel_row_title).paddingLeft

    private fun userRowPaddingOf(adapter: ChannelListAdapter, session: Int): Int =
        rowOf(adapter, adapter.getUserPosition(session))
            .findViewById<View>(R.id.user_row_title).paddingLeft

    private fun indentPx(level: Int): Int = (level * TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 25f, context.resources.displayMetrics
    )).toInt()

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
     * A list the adapter is attached to, measured and laid out, so view holders can be found
     * again. [recyclerView] is only a bare parent for `onCreateViewHolder`.
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
