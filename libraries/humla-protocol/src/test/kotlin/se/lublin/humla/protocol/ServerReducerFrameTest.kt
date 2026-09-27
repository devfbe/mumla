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
package se.lublin.humla.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.model.ServerState

/**
 * What the reducer does with a frame that names a channel it has never heard of, or a parent that
 * would break the tree. The server decides those ids, and the tree must stay walkable from the root.
 *
 * An unknown parent gets a stub channel, which the real `ChannelState` later fills in. Unknown links
 * are skipped: a link is an attribute of a channel we already have, not a place in the tree.
 */
class ServerReducerFrameTest {

    private val model = ReducerHarness().apply { feed(channelFrame(0, name = "Root")) }
    private val state: ServerState get() = model.state

    /** Every channel a reader walking down from the root reaches, in order, without the root. */
    private fun channelsBelowRoot(): List<Int> = state.flatten().drop(1).map { it.id }

    @Test
    fun aChannelWhoseParentIsUnknownIsHungOffAStubThatItsStateLaterFillsIn() {
        model.feed(channelFrame(2, parent = 1, name = "orphan"))

        assertThat(state.channel(1)!!.name).isNull()
        assertThat(state.channel(2)!!.parent).isEqualTo(1)
        assertThat(state.subchannelIds(1)).containsExactly(2)

        model.feed(channelFrame(1, parent = 0, name = "parent"))
        assertThat(state.channel(1)!!.name).isEqualTo("parent")
        assertThat(state.subchannelIds(1)).containsExactly(2)
        assertThat(channelsBelowRoot()).containsExactly(1, 2).inOrder()
    }

    /** A channel that names itself as its parent keeps its own state and is refused as a one-frame cycle. */
    @Test
    fun aFrameThatNamesItselfAsItsOwnParentIsHungUnderTheRootInstead() {
        model.feed(channelFrame(3, parent = 3, name = "self"))

        assertThat(state.channel(3)!!.name).isEqualTo("self")
        assertThat(state.channel(3)!!.parent).isEqualTo(0)
        assertThat(state.subtreeUserCount(3)).isEqualTo(0)
    }

    /** A channel is never its own ancestor; two frames that would tie the knot are refused. */
    @Test
    fun aParentCycleIsRefused() {
        model.feed(channelFrame(5, parent = 7, name = "a"), channelFrame(7, parent = 5, name = "b"))

        assertThat(state.subtreeUserCount(5)).isEqualTo(0)
        assertThat(state.subtreeUserCount(7)).isEqualTo(0)
        assertThat(channelsBelowRoot()).containsExactly(5, 7)
    }

    /** A refused parent must not take the channel and its users out of the tree. */
    @Test
    fun aRefusedParentLeavesTheChannelAndItsUsersWhereReadersReachThem() {
        model.feed(
            channelFrame(5, parent = 7, name = "a"),
            channelFrame(7, parent = 5, name = "b"),
            userFrame(1) { setName("someone").setChannelId(7) },
        )

        assertThat(channelsBelowRoot()).containsExactly(5, 7)
        assertThat(state.flatten().flatMap { state.usersIn(it.id) }.map { it.name }).containsExactly("someone")
    }

    /** A chain deep enough to exhaust a reader's stack is cut off; the too-deep channel lands under the root. */
    @Test
    fun aChainDeeperThanTheTreeMayBeIsCutOff() {
        for (id in 1..DEEP_CHAIN) model.feed(channelFrame(id, parent = id - 1, name = "channel $id"))

        assertThat(state.subtreeUserCount(0)).isEqualTo(0)
        assertThat(state.channel(DEEP_CHAIN)!!.name).isEqualTo("channel $DEEP_CHAIN")
        var depth = 0
        var channel = 0
        while (state.subchannelIds(channel).isNotEmpty()) {
            channel = state.subchannelIds(channel).last()
            depth++
        }
        assertThat(depth).isAtMost(ServerWriter.MAX_CHANNEL_DEPTH)
    }

    @Test
    fun aChannelRefusedForDepthIsHungUnderTheRootRatherThanDropped() {
        val tooDeep = ServerWriter.MAX_CHANNEL_DEPTH + 1
        for (id in 1..tooDeep) model.feed(channelFrame(id, parent = id - 1, name = "channel $id"))
        model.feed(userFrame(1) { setName("someone").setChannelId(tooDeep) })

        assertThat(state.channel(tooDeep)!!.parent).isEqualTo(0)
        assertThat(channelsBelowRoot()).contains(tooDeep)
        assertThat(state.flatten().flatMap { state.usersIn(it.id) }.map { it.name }).containsExactly("someone")
    }

    /** The refusal is about the frame, not the channel: a channel already placed keeps its place. */
    @Test
    fun aRefusedFrameLeavesAChannelWhereTheServerAlreadyPutIt() {
        model.feed(channelFrame(2, parent = 0, name = "two"), channelFrame(5, parent = 2, name = "five"))

        model.feed(channelFrame(5, parent = 5, name = "five"))

        assertThat(state.channel(5)!!.parent).isEqualTo(2)
        assertThat(state.subchannelIds(0)).containsExactly(2)
    }

    /** The root's own frame need not arrive before a refused one: the fallback stubs the root. */
    @Test
    fun aFrameRefusedBeforeTheRootFrameArrivedStillLandsUnderTheRoot() {
        val early = ReducerHarness()

        early.feed(channelFrame(5, parent = 5, name = "five"))

        assertThat(early.state.root).isNotNull()
        assertThat(early.state.channel(5)!!.parent).isEqualTo(0)
    }

    /** The fallback must not make the root its own parent. Timed out: a cycle makes walks hang. */
    @Test(timeout = 30_000)
    fun aRootThatNamesItselfAsItsParentIsNotHungUnderItself() {
        model.feed(channelFrame(0, parent = 0, name = "Root"))

        assertThat(state.root!!.parent).isNull()
        assertThat(state.subchannelIds(0)).isEmpty()
    }

    @Test
    fun userMovesUpdateTheDirectAndTheRecursiveCounts() {
        model.feed(userFrame(1) { setName("a").setChannelId(0) })
        assertThat(state.userIds(0)).hasSize(1)
        assertThat(state.subtreeUserCount(0)).isEqualTo(1)

        model.feed(channelFrame(1, parent = 0, name = "sub"), userFrame(2) { setName("b").setChannelId(1) })
        assertThat(state.userIds(0)).hasSize(1)
        assertThat(state.subtreeUserCount(0)).isEqualTo(2)

        model.feed(userFrame(1) { channelId = 1 })
        assertThat(state.userIds(0)).isEmpty()
        assertThat(state.userIds(1)).hasSize(2)
        assertThat(state.subtreeUserCount(0)).isEqualTo(2)
    }

    private companion object {
        /** Deep enough that a recursive walk without the depth limit exhausts a default stack. */
        const val DEEP_CHAIN = 20_000
    }
}
