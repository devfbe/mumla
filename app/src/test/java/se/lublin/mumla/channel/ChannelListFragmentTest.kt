package se.lublin.mumla.channel

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.SessionActions
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.snackbarText
import se.lublin.mumla.testing.ChatTargetParentFragment
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.addUnderChatParent
import se.lublin.mumla.testing.channelRow
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.layOut
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.testing.stubTalkStates
import se.lublin.mumla.testing.userRow

/**
 * The channel list screen over a mocked session: its rows across disconnects, following us, the
 * settings it reads, joining, and the chat-target action mode.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentTest {

    /** Records what the fragment asks the list to scroll to, without needing a laid-out list. */
    private class RecordingLayoutManager(context: android.content.Context) : LinearLayoutManager(context) {
        val scrolls = mutableListOf<Int>()
        override fun scrollToPosition(position: Int) {
            scrolls.add(position)
            super.scrollToPosition(position)
        }
    }

    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var parent: ChatTargetParentFragment
    private lateinit var fragment: ChannelListFragment
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(SERVER, "Home", "example.org", 64738, "me", null)
    }
    private lateinit var model: MutableStateFlow<ServerState?>
    private lateinit var talkStates: MutableStateFlow<Map<Int, TalkState>>
    private lateinit var actions: SessionActions

    private val channelView: RecyclerView get() = fragment.requireView().findViewById(R.id.channelUsers)
    private val listAdapter: ChannelListAdapter get() = channelView.adapter as ChannelListAdapter

    /**
     * ```
     * root(0)
     *   user 200
     *   populated(2)
     *     user 100 (us)
     *   other(3)
     *     user 300
     * ```
     */
    private fun tree(self: Int = 2, ann: Int = 0, locked: Boolean = false) = serverState(self = 100) {
        channel(0, "Root")
        channel(ChannelState(2, "populated", 0, position = 1))
        channel(ChannelState(3, "other", 0, position = 2, isEnterRestricted = locked, canEnter = !locked))
        user(100, "Me", channel = self)
        user(200, "Ann", channel = ann)
        user(300, "Bob", channel = 3)
    }

    @Before
    fun setUp() {
        model = session.stubModel(tree())
        talkStates = session.stubTalkStates()
        actions = session.stubActions()
        session.stubConnected()
        controller = hostWith(session)
        fragment = ChannelListFragment.newInstance(pinned = false)
        parent = controller.get().addUnderChatParent(fragment, "list")
        drainMainUntil { listAdapter.itemCount > 0 }
    }

    private fun rows(): List<ChannelRow> = listAdapter.currentList

    private fun rowsSettle(condition: (List<ChannelRow>) -> Boolean) = drainMainUntil { condition(rows()) }

    @Test
    fun aSynchronizedSessionFillsTheList() {
        assertThat(rows().map { it.id }).contains(ChannelRow.USER_ID_MASK or 100L)
    }

    @Test
    fun aLostConnectionEmptiesTheListAndTheReconnectFillsItAgain() {
        session.stubState(SessionState.ConnectionLost(10L, 1, null))
        rowsSettle { it.isEmpty() }

        session.stubState(SessionState.Reconnecting(null))
        session.stubState(SessionState.Connected)
        rowsSettle { it.isNotEmpty() }
    }

    @Test
    fun aChangeInTheModelReachesTheRows() {
        model.value = tree(ann = 3)

        rowsSettle { rows -> rows.indexOfFirst { it.id == ChannelRow.USER_ID_MASK or 200L } > 3 }
    }

    @Test
    fun talkStatesReachTheList() {
        channelView.layOut(1000, 4000)

        talkStates.value = mapOf(200 to TalkState.TALKING)
        idleMainLooper()
        channelView.layOut(1000, 4000)

        val row = channelView.findViewHolderForItemId(ChannelRow.USER_ID_MASK or 200L)!!.itemView
        assertThat(androidx.core.view.ViewCompat.getStateDescription(row))
            .isEqualTo(controller.get().getString(R.string.a11y_state_talking))
    }

    /** Only our own move scrolls the list, and only once the moved rows are in. */
    @Test
    fun ourOwnChannelChangeScrollsTheListAndSomebodyElsesDoesNot() {
        val layout = RecordingLayoutManager(controller.get())
        channelView.layoutManager = layout

        model.value = tree(ann = 3)
        rowsSettle { rows -> rows.indexOfFirst { it.id == ChannelRow.USER_ID_MASK or 200L } > 3 }
        assertThat(layout.scrolls).isEmpty()

        model.value = tree(self = 3, ann = 3)
        drainMainUntil { layout.scrolls.isNotEmpty() }

        assertThat(layout.scrolls).containsExactly(listAdapter.positionOf(ChannelRow.CHANNEL_ID_MASK or 3L))
    }

    @Test
    fun aMoveWhileDisconnectedScrollsNothing() {
        val layout = RecordingLayoutManager(controller.get())
        channelView.layoutManager = layout
        session.stubState(SessionState.Disconnected())
        rowsSettle { it.isEmpty() }

        model.value = tree(self = 3)
        idleMainLooper()

        assertThat(layout.scrolls).isEmpty()
    }

    @Test
    fun theUserCountPreferenceReachesTheRows() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(controller.get())
        val shown = Settings.getInstance(controller.get()).shouldShowUserCount

        preferences.edit { putBoolean(Settings.SHOW_USER_COUNT.key, !shown) }

        rowsSettle { rows -> (rows.first() as ChannelRow.Channel).userCount != null == !shown }
    }

    /** Its argument decides whether it shows the whole tree or the pinned channels. */
    @Test
    fun thePinnedArgumentDecidesWhereTheTreeIsRooted() {
        // A fresh repository, so the pins are read again with the stub below.
        installDatabase(controller.get().database)
        every { controller.get().database.getPinnedChannels(any()) } returns listOf(2)

        val pinnedList = ChannelListFragment.newInstance(true)
        controller.get().addUnderChatParent(pinnedList, "pinned-list", parentTag = "pinned-parent")

        val view = pinnedList.requireView().findViewById<RecyclerView>(R.id.channelUsers)
        drainMainUntil { (view.adapter as ChannelListAdapter).itemCount > 0 }
        assertThat((view.adapter as ChannelListAdapter).getItemId(0)).isEqualTo(ChannelRow.CHANNEL_ID_MASK or 2L)
        assertThat(listAdapter.getItemId(0)).isEqualTo(ChannelRow.CHANNEL_ID_MASK or 0L)
    }

    @Test
    fun joiningAnEnterableChannelMovesUs() {
        fragment.join(3)

        verify { actions.joinChannel(3) }
        assertThat(controller.get().snackbarText()).isNull()
    }

    @Test
    fun joiningAChannelThatCannotBeEnteredExplainsInstead() {
        model.value = tree(locked = true)

        fragment.join(3)

        verify(exactly = 0) { actions.joinChannel(any()) }
        assertThat(controller.get().snackbarText()).isEqualTo("You are not allowed to enter other.")
    }

    @Test
    fun ourOwnListenerCanBeStoppedFromItsRow() {
        fragment.onStopListening(ChannelRow.Listener(3, 100, "Me", 1, isOwn = true))

        verify { actions.setListening(3, false) }
    }

    /**
     * Only a set target that is this row's channel with an open action mode means "tapped the open
     * target again". Opening a mode sets the target, finishing it clears it.
     */
    @Test
    fun tappingTheOpenChannelTargetAgainClosesItAndTappingAnotherSwitchesIt() {
        fragment.onChannelClick(channelRow(1))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.Channel(1, "channel-1"))

        fragment.onChannelClick(channelRow(2))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.Channel(2, "channel-2"))

        fragment.onChannelClick(channelRow(2))
        assertThat(parent.chat.target.value).isNull()
    }

    /**
     * A chat target can be set from elsewhere with no action mode open here, so the tap has to open
     * one rather than swallow itself.
     */
    @Test
    fun tappingTheChannelOfATargetThisFragmentDidNotOpenOpensAModeForIt() {
        parent.chat.select(ChatTarget.Channel(1, "channel-1"))

        fragment.onChannelClick(channelRow(1))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.Channel(1, "channel-1"))

        // Now there is one to dismiss, proving a mode was opened above.
        fragment.onChannelClick(channelRow(1))
        assertThat(parent.chat.target.value).isNull()
    }

    @Test
    fun tappingTheOpenUserTargetAgainClosesItAndTappingAnotherSwitchesIt() {
        fragment.onUserClick(userRow(1))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.User(1, "user-1"))

        fragment.onUserClick(userRow(2))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.User(2, "user-2"))

        fragment.onUserClick(userRow(2))
        assertThat(parent.chat.target.value).isNull()
    }

    /** A channel target and a user target with the same id differ. */
    @Test
    fun aUserTargetIsNotTheChannelTargetOfTheSameId() {
        fragment.onUserClick(userRow(1))
        fragment.onChannelClick(channelRow(1))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.Channel(1, "channel-1"))

        fragment.onUserClick(userRow(1))
        assertThat(parent.chat.target.value).isEqualTo(ChatTarget.User(1, "user-1"))
    }

    private companion object {
        const val SERVER = 42L
    }
}
