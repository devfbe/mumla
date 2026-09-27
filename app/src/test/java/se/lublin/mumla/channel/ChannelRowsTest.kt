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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.model.Bytes
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.UserState
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.util.UserStatus

/** How [channelRows] flattens a snapshot into the channel list's rows. */
class ChannelRowsTest {

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
    private val small = serverState(self = 100) {
        channel(0, "root")
        channel(ChannelState(1, "empty", 0, position = 1))
        channel(ChannelState(2, "populated", 0, position = 2))
        channel(3, "emptyChild", parent = 1)
        channel(4, "deep", parent = 2)
        user(100, "u100", channel = 4)
        user(200, "u200", channel = 0)
    }

    private fun rows(
        model: ServerState = small,
        roots: List<Int> = listOf(0),
        expanded: Map<Int, Boolean> = emptyMap(),
        showUserCount: Boolean = true,
    ) = channelRows(model, roots, expanded, showUserCount)

    private fun List<ChannelRow>.channel(id: Int) = filterIsInstance<ChannelRow.Channel>().single { it.channel == id }

    @Test
    fun eachChannelIsFollowedByItsUsersThenItsSubchannels() {
        assertThat(rows().map { it.id }).containsExactly(
            ChannelRow.CHANNEL_ID_MASK or 0L,
            ChannelRow.USER_ID_MASK or 200L,
            ChannelRow.CHANNEL_ID_MASK or 1L,
            ChannelRow.CHANNEL_ID_MASK or 2L,
            ChannelRow.CHANNEL_ID_MASK or 4L,
            ChannelRow.USER_ID_MASK or 100L,
        ).inOrder()
    }

    @Test
    fun aRowIsIndentedByItsDepthAndAUserOneDeeperThanItsChannel() {
        val rows = rows()

        assertThat(rows.map { it.depth }).containsExactly(0, 1, 1, 1, 2, 3).inOrder()
    }

    @Test
    fun anEmptySubtreeStartsCollapsedAndAPopulatedOneExpanded() {
        val rows = rows()

        assertThat(rows.channel(1).expanded).isFalse()
        assertThat(rows.channel(2).expanded).isTrue()
        assertThat(rows.map { it.id }).doesNotContain(ChannelRow.CHANNEL_ID_MASK or 3L)
    }

    @Test
    fun anExplicitExpansionOrContractionOverridesTheDefault() {
        val rows = rows(expanded = mapOf(1 to true, 2 to false))

        assertThat(rows.channel(1).expanded).isTrue()
        assertThat(rows.map { it.id }).contains(ChannelRow.CHANNEL_ID_MASK or 3L)
        assertThat(rows.channel(2).expanded).isFalse()
        assertThat(rows.map { it.id }).doesNotContain(ChannelRow.USER_ID_MASK or 100L)
    }

    @Test
    fun theUserCountCountsTheWholeSubtreeEvenWhenItIsCollapsed() {
        val rows = rows(expanded = mapOf(2 to false))

        assertThat(rows.channel(0).userCount).isEqualTo(2)
        assertThat(rows.channel(2).userCount).isEqualTo(1)
        assertThat(rows.channel(1).userCount).isEqualTo(0)
    }

    @Test
    fun theUserCountIsLeftOutWhenTheSettingIsOff() {
        assertThat(rows(showUserCount = false).filterIsInstance<ChannelRow.Channel>().map { it.userCount })
            .containsExactly(null, null, null, null)
    }

    @Test
    fun theExpandToggleIsOfferedForSubchannelsUsersOrListenersOnly() {
        val model = serverState {
            channel(0, "root")
            channel(1, "withSub")
            channel(2, "sub", parent = 1)
            channel(3, "withUser")
            channel(4, "withListener")
            channel(5, "nothing")
            user(1, "u", channel = 3)
            user(UserState(2, "l", 0, listening = setOf(4)))
        }

        val rows = rows(model, expanded = mapOf(1 to true, 3 to true, 4 to true, 5 to true))

        assertThat(listOf(1, 2, 3, 4, 5).map { rows.channel(it).expandable })
            .containsExactly(true, false, true, true, false).inOrder()
    }

    @Test
    fun listenersFollowTheUsersKeepTheChannelOpenAndAreNotCountedAsUsers() {
        val model = serverState(self = 1) {
            channel(0, "root")
            channel(5, "Games")
            user(UserState(1, "Me", 0, listening = setOf(5)))
            user(UserState(2, "Ann", 0, listening = setOf(5)))
            user(3, "Bob", channel = 5)
        }

        val rows = rows(model)

        val games = rows.dropWhile { it.id != (ChannelRow.CHANNEL_ID_MASK or 5L) }
        assertThat(games.map { it.id }).containsExactly(
            ChannelRow.CHANNEL_ID_MASK or 5L,
            ChannelRow.USER_ID_MASK or 3L,
            ChannelRow.listenerId(5, 2),
            ChannelRow.listenerId(5, 1),
        ).inOrder()
        assertThat(rows.channel(5).userCount).isEqualTo(1)
        assertThat(rows.channel(5).expanded).isTrue()
        val listeners = rows.filterIsInstance<ChannelRow.Listener>()
        assertThat(listeners.map { it.name to it.isOwn }).containsExactly("Ann" to false, "Me" to true).inOrder()
        assertThat(listeners.map { it.depth }).containsExactly(2, 2)
    }

    @Test
    fun onlyAListenerStillKeepsAnEmptyChannelOpen() {
        val model = serverState {
            channel(0, "root")
            channel(5, "Games")
            user(UserState(2, "Ann", 0, listening = setOf(5)))
        }

        assertThat(rows(model).channel(5).expanded).isTrue()
    }

    @Test
    fun ourChannelIsMarkedAndSoAreTheChannelsLinkedWithIt() {
        val model = serverState(self = 1) {
            channel(0, "root")
            channel(ChannelState(1, "ours", 0, links = setOf(2)))
            channel(ChannelState(2, "linked", 0, links = setOf(1)))
            channel(3, "other")
            user(1, "Me", channel = 1)
        }

        val rows = rows(model, expanded = mapOf(2 to true, 3 to true))

        assertThat(rows.channel(1).let { it.isOwn to it.isLinked }).isEqualTo(true to true)
        assertThat(rows.channel(2).let { it.isOwn to it.isLinked }).isEqualTo(false to true)
        assertThat(rows.channel(3).let { it.isOwn to it.isLinked }).isEqualTo(false to false)
    }

    @Test
    fun withoutOurUserNoChannelIsMarked() {
        val rows = rows(serverState { channel(0, "root"); user(1, "Ann") })

        assertThat(rows.channel(0).isOwn).isFalse()
        assertThat(rows.filterIsInstance<ChannelRow.User>().single().isSelf).isFalse()
    }

    @Test
    fun onlyOurOwnUserRowIsMarkedAsOurs() {
        val users = rows().filterIsInstance<ChannelRow.User>()

        assertThat(users.map { it.session to it.isSelf }).containsExactly(200 to false, 100 to true).inOrder()
    }

    @Test
    fun aRestrictedChannelIsLockedOpenOrClosedByWhetherWeMayEnter() {
        val model = serverState {
            channel(0, "root")
            channel(ChannelState(1, "open", 0, isEnterRestricted = true, canEnter = true))
            channel(ChannelState(2, "closed", 0, isEnterRestricted = true, canEnter = false))
            channel(ChannelState(3, "denied", 0, canEnter = false))
        }

        val rows = rows(model, expanded = mapOf(0 to true))

        assertThat(rows.channel(0).lock).isEqualTo(ChannelRow.Lock.NONE)
        assertThat(rows.channel(1).lock).isEqualTo(ChannelRow.Lock.OPEN)
        assertThat(rows.channel(2).lock).isEqualTo(ChannelRow.Lock.CLOSED)
        assertThat(rows.channel(3).lock).isEqualTo(ChannelRow.Lock.CLOSED)
    }

    @Test
    fun aUserRowCarriesTheStateItsIconShowsAndItsAvatar() {
        val avatar = Bytes.of(byteArrayOf(1, 2, 3))
        val model = serverState {
            channel(0, "root")
            user(UserState(1, "a", 0, isSelfDeafened = true, isDeafened = true, isSelfMuted = true))
            user(UserState(2, "b", 0, isDeafened = true, isSelfMuted = true))
            user(UserState(3, "c", 0, isSelfMuted = true, isMuted = true))
            user(UserState(4, "d", 0, isMuted = true, isSuppressed = true))
            user(UserState(5, "e", 0, isSuppressed = true))
            user(UserState(6, "f", 0, texture = avatar))
        }

        val users = rows(model).filterIsInstance<ChannelRow.User>()

        assertThat(users.map { it.status }).containsExactly(
            UserStatus.SELF_DEAFENED, UserStatus.DEAFENED, UserStatus.SELF_MUTED, UserStatus.MUTED,
            UserStatus.SUPPRESSED, UserStatus.NONE,
        ).inOrder()
        assertThat(users.last().avatar).isSameInstanceAs(avatar)
    }

    @Test
    fun aPinnedListIsRootedInThePinnedChannelsInPinningOrder() {
        val rows = rows(roots = listOf(4, 1, 99))

        assertThat(rows.filterIsInstance<ChannelRow.Channel>().map { it.channel to it.depth })
            .containsExactly(4 to 0, 1 to 0).inOrder()
    }

    @Test
    fun aModelWithoutTheRootHasNoRows() {
        assertThat(rows(serverState { channel(ChannelState(5, "orphan", parent = 7)) })).isEmpty()
    }

    @Test
    fun aFiveThousandChannelTreeIsFlattenedQuickly() {
        val model = ServerState.of(
            (0 until 5_000).map { ChannelState(it, "channel-$it", if (it == 0) null else (it - 1) / 4) },
            (1..1_000).map { UserState(it, "user-$it", (it * 5) % 5_000) },
            selfSession = 1,
        )
        repeat(WARMUP) { rows(model) }

        val start = System.nanoTime()
        val rows = rows(model)
        val micros = (System.nanoTime() - start) / 1_000

        println("5000 channels, 1000 users flattened into ${rows.size} rows in $micros us")
        assertThat(rows.size).isGreaterThan(1_000)
        assertThat(micros).isLessThan(BOUND_MICROS)
    }

    private companion object {
        const val WARMUP = 20
        const val BOUND_MICROS = 500_000L
    }
}
