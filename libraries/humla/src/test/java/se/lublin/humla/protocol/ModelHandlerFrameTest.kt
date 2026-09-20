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
        handler = ModelHandler(
            ApplicationProvider.getApplicationContext(),
            NoopObserver(),
            SilentLogger,
            null,
            null,
        )
        handler.messageChannelState(channelState(0, name = "Root"))
    }

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
        // one-frame cycle, and the walk over it never returns.
        assertThat(handler.getChannel(3)!!.getParent()).isNull()
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
     * instead - a channel below it keeps its name and its place in the map and simply has no
     * parent, which is what the model already does for any channel whose parent has not arrived.
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
