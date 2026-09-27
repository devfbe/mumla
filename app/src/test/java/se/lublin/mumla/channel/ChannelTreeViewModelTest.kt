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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.Server
import se.lublin.humla.model.UserState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.net.Permissions
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class ChannelTreeViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = mockk<MumlaDatabase>(relaxed = true)
    private val repository = MumlaRepository(database, Dispatchers.Unconfined)
    private var server = Server(SERVER, "Home", "example.org", 64738, "me", null)
    private val session = mockk<IHumlaSession>(relaxed = true) { every { targetServer } answers { server } }
    private val state = session.stubState(SessionState.Connected)
    private val model = session.stubModel(tree())
    private val actions = session.stubActions()
    private val showUserCount = MutableStateFlow(true)

    init {
        installSession(session)
    }

    /** Root(0) > Lobby(1) with us (1) and Ann (2), Games(2) with Bob (3) who is registered. */
    private fun tree(
        ann: UserState = UserState(2, "Ann", 1, hash = "annhash"),
        games: ChannelState = ChannelState(2, "Games", 0, position = 1),
        permissions: Int = 0,
    ) = serverState(self = 1, permissions = permissions) {
        channel(0, "Root")
        channel(ChannelState(1, "Lobby", 0, links = setOf(2), permissions = Permissions.MUTE_DEAFEN))
        channel(games)
        user(1, "Me", channel = 1)
        user(ann)
        user(UserState(3, "Bob", 2, userId = 30))
    }

    /** A view model with a screen collecting its rows and talk states. */
    private fun viewModel(pinnedOnly: Boolean = false) =
        ChannelTreeViewModel(SessionManager.get(app), repository, pinnedOnly, showUserCount, Dispatchers.Unconfined)
            .also { tree ->
                val screen = CoroutineScope(UnconfinedTestDispatcher())
                screen.launch { tree.tree.collect {} }
                screen.launch { tree.talkStates.collect {} }
                idleMainLooper()
            }

    private fun ChannelTreeViewModel.ids() = tree.value?.rows?.map { it.id }

    @Test
    fun theTreeFollowsTheModelOnlyWhileSynchronized() {
        val tree = viewModel()
        assertThat(tree.tree.value!!.ownChannel).isEqualTo(1)
        assertThat(tree.ids()).contains(ChannelRow.USER_ID_MASK or 3L)

        model.value = tree(ann = UserState(2, "Ann", 2))
        idleMainLooper()
        assertThat(tree.tree.value!!.rows.indexOfFirst { it.id == (ChannelRow.USER_ID_MASK or 2L) })
            .isGreaterThan(tree.tree.value!!.rows.indexOfFirst { it.id == (ChannelRow.CHANNEL_ID_MASK or 2L) })

        state.value = SessionState.Reconnecting(null)
        idleMainLooper()
        assertThat(tree.tree.value).isNull()
    }

    @Test
    fun theUserCountSettingAndTheExpansionsReachTheRows() {
        val tree = viewModel()

        showUserCount.value = false
        tree.setExpanded(2, false)
        idleMainLooper()

        val games = tree.tree.value!!.rows.filterIsInstance<ChannelRow.Channel>().single { it.channel == 2 }
        assertThat(games.userCount).isNull()
        assertThat(games.expanded).isFalse()
        assertThat(tree.ids()).doesNotContain(ChannelRow.USER_ID_MASK or 3L)
    }

    @Test
    fun aPinnedTreeIsRootedInThePinnedChannelsAsTheyWereRead() {
        every { database.getPinnedChannels(SERVER) } returns listOf(2)

        val tree = viewModel(pinnedOnly = true)

        assertThat(tree.ids()!!.first()).isEqualTo(ChannelRow.CHANNEL_ID_MASK or 2L)
        assertThat(tree.ids()).doesNotContain(ChannelRow.CHANNEL_ID_MASK or 0L)
    }

    @Test
    fun theTalkStatesAreTheSessions() {
        val tree = viewModel()

        assertThat(tree.talkStates.value).isSameInstanceAs(session.talkStates.value)
    }

    @Test
    fun joiningIsRefusedWhereTheServerSaidWeMayNotEnter() {
        val tree = viewModel()
        assertThat(tree.join(2)).isTrue()
        verify { actions.joinChannel(2) }

        model.value = tree(games = ChannelState(2, "Games", 0, canEnter = false))

        assertThat(tree.join(2)).isFalse()
        verify(exactly = 1) { actions.joinChannel(2) }
        assertThat(tree.channelName(2)).isEqualTo("Games")
    }

    @Test
    fun thePermissionsFollowTheModelAndTheRootsAreTheServerWideOnes() {
        val tree = viewModel()
        val seen = mutableListOf<Int>()
        CoroutineScope(UnconfinedTestDispatcher()).launch { tree.permissions(0).collect { seen += it } }

        model.value = tree(permissions = 0x20)
        idleMainLooper()

        assertThat(seen.last()).isEqualTo(0x20)
        tree.requestPermissions(0)
        verify { actions.requestPermissions(0) }
    }

    @Test
    fun theChannelMenuStateTellsPinsLinksAndListening() {
        every { database.getPinnedChannels(SERVER) } returns listOf(2)
        model.value = tree().let { serverState(self = 1) {
            it.channels.values.forEach(::channel)
            user(UserState(1, "Me", 1, listening = setOf(2)))
        } }
        val tree = viewModel()

        assertThat(tree.channelMenuState(2)).isEqualTo(
            ChannelMenuState(
                hasDescription = false, isPinned = true, isOwn = false, isLinkedToOwn = false, isListening = true,
            ),
        )
        assertThat(tree.channelMenuState(1)!!.isOwn).isTrue()
        assertThat(tree.channelMenuState(99)).isNull()
    }

    @Test
    fun theUserMenuStateCarriesTheUserAndOurPermissionsOverThem() {
        val tree = viewModel()

        val ann = tree.userMenuState(2)!!

        assertThat(ann.user.name).isEqualTo("Ann")
        assertThat(ann.isSelf).isFalse()
        assertThat(ann.channelPermissions).isEqualTo(Permissions.MUTE_DEAFEN)
        assertThat(tree.userMenuState(1)!!.isSelf).isTrue()
    }

    @Test
    fun linkingIsWithOurChannel() {
        val tree = viewModel()

        tree.setLinked(2, true)
        tree.setLinked(2, false)

        verify { actions.linkChannels(1, 2) }
        verify { actions.unlinkChannels(1, 2) }
    }

    @Test
    fun aShoutWhispersToTheChannelAndSaysWhenNoSlotIsLeft() {
        val tree = viewModel()
        every { actions.whisperTo(any()) } returns false

        assertThat(tree.shout(2, includeLinked = true, includeSubchannels = false)).isFalse()

        verify { actions.whisperTo(match<WhisperTarget> { it.name == "Games" }) }
    }

    @Test
    fun theLocalMuteAndIgnoreOfARegisteredUserOnASavedServerAreStored() {
        val tree = viewModel()

        tree.setLocalMuted(3, true)
        tree.setLocalIgnored(3, true)
        tree.setLocalMuted(3, false)
        tree.setLocalIgnored(3, false)

        verify { actions.setLocalMuted(3, true) }
        verify { database.addLocalMutedUser(SERVER, 30) }
        verify { database.addLocalIgnoredUser(SERVER, 30) }
        verify { database.removeLocalMutedUser(SERVER, 30) }
        verify { database.removeLocalIgnoredUser(SERVER, 30) }
    }

    @Test
    fun anUnregisteredUserOrAnUnsavedServerStoresNoLocalMute() {
        val tree = viewModel()
        tree.setLocalMuted(2, true)

        server = Server(-1, "Quick", "example.org", 64738, "me", null)
        tree.setLocalMuted(3, true)

        verify { actions.setLocalMuted(2, true) }
        verify { actions.setLocalMuted(3, true) }
        verify(exactly = 0) { database.addLocalMutedUser(any(), any()) }
    }

    @Test
    fun aKeptVolumeIsStoredByCertificateElseByName() {
        model.value = tree(ann = UserState(2, "Ann", 1))
        val tree = viewModel()

        tree.setLocalVolume(3, 1.5f)

        verify { actions.setLocalVolume(3, 1.5f) }
        verify(exactly = 1) { database.setLocalVolume(any(), any()) }
        verify { database.setLocalVolume("name:example.org:64738:Bob", 1.5f) }

        model.value = tree()
        tree.setLocalVolume(2, 0.25f)
        verify { database.setLocalVolume("cert:annhash", 0.25f) }
    }

    @Test
    fun withoutASessionNothingIsAskedOrStored() {
        state.value = SessionState.Disconnected()
        val tree = viewModel()

        tree.setLocalMuted(3, true)
        tree.setLocalVolume(3, 2f)
        tree.join(1)

        verify(exactly = 0) { actions.setLocalMuted(any(), any()) }
        verify(exactly = 0) { actions.joinChannel(any()) }
        verify(exactly = 0) { database.setLocalVolume(any(), any()) }
        assertThat(tree.channels()).isEmpty()
    }

    @Test
    fun theChannelsToMoveToAreTheTreeInOrder() {
        val tree = viewModel()

        assertThat(tree.channels().map { it.id }).containsExactly(0, 1, 2).inOrder()
    }

    private companion object {
        const val SERVER = 42L
    }
}
