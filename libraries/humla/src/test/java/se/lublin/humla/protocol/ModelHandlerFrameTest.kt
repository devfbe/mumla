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

    private fun channelState(id: Int, parent: Int? = null, name: String): Mumble.ChannelState =
        Mumble.ChannelState.newBuilder()
            .setChannelId(id)
            .setName(name)
            .also { if (parent != null) it.setParent(parent) }
            .build()
}
