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
import org.junit.Before
import org.junit.Test
import se.lublin.humla.model.Channel
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble

/**
 * What [ModelHandler] does with a frame that names a channel it has never heard of. The server
 * decides those ids, and an exception on the protocol thread kills the process.
 *
 * An unknown parent gets a stub channel, which the real `ChannelState` later fills in because it
 * lands on the same object. Unknown links are skipped: a link is an attribute of a channel we
 * already have, not a place in the tree.
 */
class ModelHandlerFrameTest {

    private lateinit var handler: ModelHandler

    @Before
    fun setUp() {
        handler = newHandler()
        handler.onMessage(channelState(0, name = "Root"))
    }

    private fun newHandler() = ModelHandler({}, null, null)

    @Test
    fun aChannelWhoseParentIsUnknownIsHungOffAStubInsteadOfKillingTheProtocolThread() {
        handler.onMessage(channelState(2, parent = 1, name = "orphan"))

        val stub = handler.getChannel(1)
        assertThat(stub).isNotNull()
        assertThat(handler.getChannel(2)!!.parent).isEqualTo(stub)
        assertThat(stub!!.subchannels.map { it.id }).containsExactly(2)

        // The real ChannelState arrives later and lands on the same object, so nothing is lost.
        handler.onMessage(channelState(1, parent = 0, name = "parent"))
        assertThat(handler.getChannel(1)!!.name).isEqualTo("parent")
        assertThat(handler.getChannel(1)!!.subchannels.map { it.id }).containsExactly(2)
    }

    @Test
    fun aLinkSetNamingUnknownChannelsKeepsOnlyTheKnownOnes() {
        handler.onMessage(channelState(1, parent = 0, name = "a"))
        handler.onMessage(channelState(2, parent = 0, name = "b"))

        handler.onMessage(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinks(2).addLinks(99).build()
        )

        assertThat(handler.getChannel(1)!!.links.map { it.id }).containsExactly(2)
    }

    @Test
    fun addingAndRemovingALinkToAnUnknownChannelIsIgnored() {
        handler.onMessage(channelState(1, parent = 0, name = "a"))

        handler.onMessage(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksAdd(99).build()
        )
        handler.onMessage(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksRemove(99).build()
        )

        assertThat(handler.getChannel(1)!!.links).isEmpty()
    }

    /**
     * The parent id is looked up before the channel is created, so for a channel that names itself
     * as parent the stub created for the miss would overwrite the freshly named channel in the map.
     */
    @Test
    fun aFrameThatNamesItselfAsItsOwnParentDoesNotReplaceTheChannelItJustNamed() {
        handler.onMessage(channelState(3, parent = 3, name = "self"))

        assertThat(handler.getChannel(3)!!.name).isEqualTo("self")
        // And the frame is refused: a channel that is its own parent is a one-frame cycle. It lands
        // under the root, so it is still in the list.
        assertThat(handler.getChannel(3)!!.parent).isEqualTo(handler.getChannel(0))
        assertThat(handler.getChannel(3)!!.subchannelUserCount).isEqualTo(0)
    }

    /**
     * A channel is never its own ancestor; a server that says otherwise is refused. Two frames tie
     * the knot, and the result is a `StackOverflowError` out of `getSubchannelUserCount` on the main
     * thread.
     */
    @Test
    fun aParentCycleIsRefusedInsteadOfKillingTheMainThread() {
        handler.onMessage(channelState(5, parent = 7, name = "a"))
        handler.onMessage(channelState(7, parent = 5, name = "b"))

        assertThat(handler.getChannel(5)!!.subchannelUserCount).isEqualTo(0)
        assertThat(handler.getChannel(7)!!.subchannelUserCount).isEqualTo(0)
    }

    /**
     * The same crash without a cycle: a chain deep enough to exhaust the stack. A channel below
     * [ModelHandler.MAX_CHANNEL_DEPTH] keeps its name, map entry and users, and is hung under the
     * root.
     */
    @Test
    fun aChainDeeperThanTheTreeMayBeIsCutOffInsteadOfKillingTheMainThread() {
        for (id in 1..DEEP_CHAIN) {
            handler.onMessage(channelState(id, parent = id - 1, name = "channel $id"))
        }

        assertThat(handler.getChannel(0)!!.subchannelUserCount).isEqualTo(0)
        assertThat(handler.getChannel(DEEP_CHAIN)!!.name).isEqualTo("channel $DEEP_CHAIN")
        var depth = 0
        var channel = handler.getChannel(0)!!
        while (channel.subchannels.isNotEmpty()) {
            channel = channel.subchannels[0]
            depth++
        }
        assertThat(depth).isAtMost(ModelHandler.MAX_CHANNEL_DEPTH)
    }

    /**
     * A refused parent must not take the channel out of the list. The app walks down from the root
     * through `getSubchannels()`, so a parentless channel and its users would be invisible, and the
     * server never resends the frame. A refused channel is hung under the root instead.
     */
    @Test
    fun aRefusedParentLeavesTheChannelAndItsUsersWhereTheListCanReachThem() {
        handler.onMessage(channelState(5, parent = 7, name = "a"))
        handler.onMessage(channelState(7, parent = 5, name = "b"))
        User(1, "someone").channel = handler.getChannel(7)

        assertThat(channelsBelowRoot().map { it.id }).containsExactly(5, 7)
        assertThat(usersBelowRoot().map { it.name }).containsExactly("someone")
    }

    /** The same for the other refusal: too deep is still in the tree, at the top of it. */
    @Test
    fun aChannelRefusedForDepthIsHungUnderTheRootRatherThanDropped() {
        for (id in 1..ModelHandler.MAX_CHANNEL_DEPTH + 1) {
            handler.onMessage(channelState(id, parent = id - 1, name = "channel $id"))
        }
        val tooDeep = ModelHandler.MAX_CHANNEL_DEPTH + 1
        User(1, "someone").channel = handler.getChannel(tooDeep)

        assertThat(handler.getChannel(tooDeep)!!.parent).isEqualTo(handler.getChannel(0))
        assertThat(channelsBelowRoot().map { it.id }).contains(tooDeep)
        assertThat(usersBelowRoot().map { it.name }).containsExactly("someone")
    }

    /**
     * The next three tests cover the branches of the fallback.
     *
     * A channel the server has already placed keeps its place: the refusal is about the frame, not
     * the channel.
     */
    @Test
    fun aRefusedFrameLeavesAChannelWhereTheServerAlreadyPutIt() {
        handler.onMessage(channelState(2, parent = 0, name = "two"))
        handler.onMessage(channelState(5, parent = 2, name = "five"))

        handler.onMessage(channelState(5, parent = 5, name = "five"))

        assertThat(handler.getChannel(5)!!.parent).isEqualTo(handler.getChannel(2))
        assertThat(handler.getChannel(0)!!.subchannels.map { it.id }).containsExactly(2)
    }

    /**
     * The root's own frame need not arrive before the refused one; the fallback stubs the root
     * instead of handing back null.
     */
    @Test
    fun aFrameRefusedBeforeTheRootFrameArrivedStillLandsUnderTheRoot() {
        val early = newHandler()

        early.onMessage(channelState(5, parent = 5, name = "five"))

        assertThat(early.getChannel(0)).isNotNull()
        assertThat(early.getChannel(5)!!.parent).isEqualTo(early.getChannel(0))
    }

    /**
     * A frame naming the root as its own parent is refused, and the fallback must not make the root
     * its own parent. Timed out because a cycle makes tree walks hang rather than fail.
     */
    @Test(timeout = 30_000)
    fun aRootThatNamesItselfAsItsParentIsNotHungUnderItself() {
        handler.onMessage(channelState(0, parent = 0, name = "Root"))

        assertThat(handler.getChannel(0)!!.parent).isNull()
        assertThat(handler.getChannel(0)!!.subchannels).isEmpty()
    }

    /** Every channel `ChannelListAdapter` would reach, in the order it reaches them. */
    private fun channelsBelowRoot(): List<Channel> = buildList {
        fun walk(channel: Channel) {
            add(channel)
            channel.subchannels.forEach { walk(it) }
        }
        handler.getChannel(0)!!.subchannels.forEach { walk(it) }
    }

    private fun usersBelowRoot(): List<User> = channelsBelowRoot().flatMap { it.users }

    private fun channelState(id: Int, parent: Int? = null, name: String): Mumble.ChannelState =
        Mumble.ChannelState.newBuilder()
            .setChannelId(id)
            .setName(name)
            .also { if (parent != null) it.setParent(parent) }
            .build()

    private companion object {
        /** Deep enough that the recursion in `getSubchannelUserCount` exhausts a default stack. */
        const val DEEP_CHAIN = 20_000
    }
}
