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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Channel
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.NoopObserver
import se.lublin.humla.testutil.SilentLogger

/**
 * What [ModelHandler] does with a frame that names a channel it has never heard of.
 *
 * Every id in a `ChannelState` is looked up in `mChannels` and the result used without a check, so
 * a parent, a link, a link to add or a link to remove that we have no `ChannelState` for was a
 * `NullPointerException`. Since task 4 that runs on the `humla-protocol` [android.os.HandlerThread],
 * which installs no uncaught-exception handler - so the default one takes it, and the process dies.
 * The server decides those ids, so this is reachable from outside.
 *
 * The remedy for an unknown parent is the one this file already uses for an unknown channel on the
 * user path: a stub channel, which the real `ChannelState` fills in when it arrives, because it
 * lands on the same object. Unknown links are skipped instead - a link is an attribute of a channel
 * we already have, not a place in the tree that has to exist for the rest to hang off.
 */
@RunWith(RobolectricTestRunner::class)
class ModelHandlerFrameTest {

    private lateinit var handler: ModelHandler

    @Before
    fun setUp() {
        handler = newHandler()
        handler.messageChannelState(channelState(0, name = "Root"))
    }

    private fun newHandler() = ModelHandler(
        ApplicationProvider.getApplicationContext(),
        NoopObserver(),
        SilentLogger,
        null,
        null,
    )

    @Test
    fun aChannelWhoseParentIsUnknownIsHungOffAStubInsteadOfKillingTheProtocolThread() {
        handler.messageChannelState(channelState(2, parent = 1, name = "orphan"))

        val stub = handler.getChannel(1)
        assertThat(stub).isNotNull()
        assertThat(handler.getChannel(2)!!.getParent()).isEqualTo(stub)
        assertThat(stub!!.getSubchannels().map { it.getId() }).containsExactly(2)

        // The real ChannelState arrives later and lands on the same object, so nothing is lost.
        handler.messageChannelState(channelState(1, parent = 0, name = "parent"))
        assertThat(handler.getChannel(1)!!.getName()).isEqualTo("parent")
        assertThat(handler.getChannel(1)!!.getSubchannels().map { it.getId() }).containsExactly(2)
    }

    @Test
    fun aLinkSetNamingUnknownChannelsKeepsOnlyTheKnownOnes() {
        handler.messageChannelState(channelState(1, parent = 0, name = "a"))
        handler.messageChannelState(channelState(2, parent = 0, name = "b"))

        handler.messageChannelState(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinks(2).addLinks(99).build()
        )

        assertThat(handler.getChannel(1)!!.getLinks().map { it.getId() }).containsExactly(2)
    }

    @Test
    fun addingAndRemovingALinkToAnUnknownChannelIsIgnored() {
        handler.messageChannelState(channelState(1, parent = 0, name = "a"))

        handler.messageChannelState(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksAdd(99).build()
        )
        handler.messageChannelState(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksRemove(99).build()
        )

        assertThat(handler.getChannel(1)!!.getLinks()).isEmpty()
    }

    /**
     * The parent id is read out of the map *before* the channel is created, so for a frame whose
     * channel id is its own parent id the lookup necessarily misses - and the stub created for
     * that miss then overwrites the freshly named channel in the map. What `onChannelAdded` handed
     * the observers is no longer what `getChannel` returns.
     */
    @Test
    fun aFrameThatNamesItselfAsItsOwnParentDoesNotReplaceTheChannelItJustNamed() {
        handler.messageChannelState(channelState(3, parent = 3, name = "self"))

        assertThat(handler.getChannel(3)!!.getName()).isEqualTo("self")
        // And the frame is refused rather than believed: a channel that is its own parent is a
        // one-frame cycle, and the walk over it never returns. It lands under the root instead of
        // nowhere, so it is still in the list - see
        // aRefusedParentLeavesTheChannelAndItsUsersWhereTheListCanReachThem.
        assertThat(handler.getChannel(3)!!.getParent()).isEqualTo(handler.getChannel(0))
        assertThat(handler.getChannel(3)!!.getSubchannelUserCount()).isEqualTo(0)
    }

    /**
     * A channel is never its own ancestor, and a server that says otherwise is refused rather than
     * believed. Two frames are enough to tie the knot, and the result is not a wrong tree but a
     * `StackOverflowError` out of `getSubchannelUserCount` - on the main thread, from
     * `ChannelListAdapter` (`:438`, `:181`), where `updateChannels()` catches `IllegalStateException`
     * and nothing else (`:326`). The parent id comes from the server, so this is reachable from
     * outside.
     */
    @Test
    fun aParentCycleIsRefusedInsteadOfKillingTheMainThread() {
        handler.messageChannelState(channelState(5, parent = 7, name = "a"))
        handler.messageChannelState(channelState(7, parent = 5, name = "b"))

        assertThat(handler.getChannel(5)!!.getSubchannelUserCount()).isEqualTo(0)
        assertThat(handler.getChannel(7)!!.getSubchannelUserCount()).isEqualTo(0)
    }

    /**
     * The same crash without a cycle: a chain deep enough to exhaust the stack in the recursion,
     * which takes one frame per channel. The tree is cut off at [ModelHandler.MAX_CHANNEL_DEPTH]
     * instead - a channel below it keeps its name, its place in the map and its users, and is hung
     * under the root, so no *path* is ever longer than the limit while the channel itself is still
     * in the list.
     */
    @Test
    fun aChainDeeperThanTheTreeMayBeIsCutOffInsteadOfKillingTheMainThread() {
        for (id in 1..DEEP_CHAIN) {
            handler.messageChannelState(channelState(id, parent = id - 1, name = "channel $id"))
        }

        assertThat(handler.getChannel(0)!!.getSubchannelUserCount()).isEqualTo(0)
        assertThat(handler.getChannel(DEEP_CHAIN)!!.getName()).isEqualTo("channel $DEEP_CHAIN")
        var depth = 0
        var channel = handler.getChannel(0)!!
        while (channel.getSubchannels().isNotEmpty()) {
            channel = channel.getSubchannels()[0]
            depth++
        }
        assertThat(depth).isAtMost(ModelHandler.MAX_CHANNEL_DEPTH)
    }

    /**
     * A refused parent must not take the channel out of the list.
     *
     * `ChannelListAdapter.updateChannels()` (`:311-328`) walks *down* from its root channels through
     * `getSubchannels()`, and nothing in `app/` iterates `getChannels()` - so a channel with no
     * parent is not in the list at all, and neither is any user standing in it, because
     * `constructNodes` (`:450`) never reaches its `getUsers()`. A `Log.w` is the only trace.
     *
     * That is what separates a refused parent from one that has not arrived yet, which is how this
     * file used to describe it: the not-yet-arrived state heals itself on the next frame, the
     * refused one never does. The server does not resend a `ChannelState` it has already sent and
     * nothing here retries, so the channel and its users are gone for the rest of the connection.
     *
     * Ruling (spec 4.1): hang a refused channel under the root instead of leaving it parentless.
     * The tree stays finite and acyclic, and the channel is visible in the wrong place rather than
     * invisibly absent.
     */
    @Test
    fun aRefusedParentLeavesTheChannelAndItsUsersWhereTheListCanReachThem() {
        handler.messageChannelState(channelState(5, parent = 7, name = "a"))
        handler.messageChannelState(channelState(7, parent = 5, name = "b"))
        User(1, "someone").setChannel(handler.getChannel(7))

        assertThat(channelsBelowRoot().map { it.getId() }).containsExactly(5, 7)
        assertThat(usersBelowRoot().map { it.getName() }).containsExactly("someone")
    }

    /** The same for the other refusal: too deep is still in the tree, at the top of it. */
    @Test
    fun aChannelRefusedForDepthIsHungUnderTheRootRatherThanDropped() {
        for (id in 1..ModelHandler.MAX_CHANNEL_DEPTH + 1) {
            handler.messageChannelState(channelState(id, parent = id - 1, name = "channel $id"))
        }
        val tooDeep = ModelHandler.MAX_CHANNEL_DEPTH + 1
        User(1, "someone").setChannel(handler.getChannel(tooDeep))

        assertThat(handler.getChannel(tooDeep)!!.getParent()).isEqualTo(handler.getChannel(0))
        assertThat(channelsBelowRoot().map { it.getId() }).contains(tooDeep)
        assertThat(usersBelowRoot().map { it.getName() }).containsExactly("someone")
    }

    /**
     * The three tests below are the inside of the fallback. Before them only its call site was
     * covered: swapping the whole fallback back for "leave it parentless" killed three tests, while
     * every single branch within it could be deleted with the suite staying green - a function that
     * reads as tested from one step up, with nothing in it tested at all.
     *
     * First branch: a channel the server has already placed keeps its place. The refusal is about
     * the frame, not about the channel, and moving a channel out of a subtree the user is looking
     * at - for a frame we are refusing precisely because we do not believe it - is worse than
     * ignoring that frame.
     */
    @Test
    fun aRefusedFrameLeavesAChannelWhereTheServerAlreadyPutIt() {
        handler.messageChannelState(channelState(2, parent = 0, name = "two"))
        handler.messageChannelState(channelState(5, parent = 2, name = "five"))

        handler.messageChannelState(channelState(5, parent = 5, name = "five"))

        assertThat(handler.getChannel(5)!!.getParent()).isEqualTo(handler.getChannel(2))
        assertThat(handler.getChannel(0)!!.getSubchannels().map { it.getId() }).containsExactly(2)
    }

    /**
     * Second branch: the root's own frame need not have arrived before the refused one. Nothing in
     * the protocol promises that order, and without the stub the fallback hands back the null it
     * exists to avoid - the channel and its users vanish from the list exactly as they did before
     * the fallback was written.
     */
    @Test
    fun aFrameRefusedBeforeTheRootFrameArrivedStillLandsUnderTheRoot() {
        val early = newHandler()

        early.messageChannelState(channelState(5, parent = 5, name = "five"))

        assertThat(early.getChannel(0)).isNotNull()
        assertThat(early.getChannel(5)!!.getParent()).isEqualTo(early.getChannel(0))
    }

    /**
     * Third branch: the fallback is a hang like any other and is asked the same question. A frame
     * naming the root as its own parent is refused, and handing the root back unchecked would make
     * the root its own parent - the fallback building the very cycle the guard refused.
     *
     * Timed out rather than left to run: a cycle here does not fail an assertion, it makes every
     * walk over the tree stop returning, and a mutation sweep with no per-test deadline reports
     * nothing at all for it.
     */
    @Test(timeout = 30_000)
    fun aRootThatNamesItselfAsItsParentIsNotHungUnderItself() {
        handler.messageChannelState(channelState(0, parent = 0, name = "Root"))

        assertThat(handler.getChannel(0)!!.getParent()).isNull()
        assertThat(handler.getChannel(0)!!.getSubchannels()).isEmpty()
    }

    /** Every channel `ChannelListAdapter` would reach, in the order it reaches them. */
    private fun channelsBelowRoot(): List<Channel> = buildList {
        fun walk(channel: Channel) {
            add(channel)
            channel.getSubchannels().forEach { walk(it) }
        }
        handler.getChannel(0)!!.getSubchannels().forEach { walk(it) }
    }

    private fun usersBelowRoot(): List<User> = channelsBelowRoot().flatMap { it.getUsers() }

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
