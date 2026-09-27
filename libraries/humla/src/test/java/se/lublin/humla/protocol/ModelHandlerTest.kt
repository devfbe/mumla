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
import com.google.protobuf.ByteString
import org.junit.Test
import se.lublin.humla.model.ServerState

/** How [ModelHandler] publishes what it reduces, and what it asks the server for on its own. */
class ModelHandlerTest {

    private val posted = ArrayDeque<() -> Unit>()
    private val published = mutableListOf<ServerState>()
    private val avatars = mutableListOf<Int>()

    private val publisher = object : ModelHandler.Publisher {
        override fun post(block: () -> Unit) {
            posted.addLast(block)
        }

        override fun publish(state: ServerState) {
            published += state
        }
    }

    private val handler = ModelHandler({}, null, null, publisher = publisher, requestAvatar = avatars::add)

    /** Runs what is queued on the "protocol thread", as its looper would after the frames before it. */
    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    @Test
    fun aBurstOfFramesIsPublishedOnceWithAllOfThem() {
        for (frame in syncFrames(channels = 50, users = 10)) handler.onMessage(frame)

        drain()

        assertThat(published).hasSize(1)
        assertThat(published.single().channels).hasSize(50)
        assertThat(published.single().self!!.name).isEqualTo("user-1")
    }

    @Test
    fun framesAfterAPublicationMakeTheNextOne() {
        handler.onMessage(channelFrame(0, name = "Root"))
        drain()
        handler.onMessage(channelFrame(1, parent = 0, name = "A"))
        drain()

        assertThat(published.map { it.channels.size }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun aLocalChoiceIsPublishedLikeAFrame() {
        handler.onMessage(channelFrame(0, name = "Root"))
        handler.onMessage(userFrame(2) { setName("Ann") })
        drain()

        handler.onLocal(LocalInput.Mute(2, true))
        drain()

        assertThat(published.last().user(2)!!.isLocalMuted).isTrue()
    }

    @Test
    fun anAvatarAnnouncedByItsHashIsAskedForAndOneSentAlongIsNot() {
        handler.onMessage(channelFrame(0, name = "Root"))
        handler.onMessage(userFrame(2) { setName("Ann").setTextureHash(ByteString.copyFromUtf8("h")) })
        handler.onMessage(userFrame(3) { setName("Bob").setTexture(ByteString.copyFromUtf8("png")) })
        handler.onMessage(userFrame(3) { selfMute = true })

        assertThat(avatars).containsExactly(2)
    }
}
