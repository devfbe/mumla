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
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.HumlaEvent.DenyType

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

    private val handler = ModelHandler(ServerState.empty(), {}, publisher, avatars::add)

    /** Runs what is queued on the protocol context, as it would after the frames before it. */
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

    private val events = mutableListOf<HumlaEvent>()
    private val eventful = ModelHandler(ServerState.empty(), events::add, publisher)

    private fun synced() = eventful.apply {
        onMessage(channelFrame(0, name = "Root"))
        onMessage(channelFrame(1, parent = 0, name = "Lobby"))
        onMessage(userFrame(1) { setName("Me").setChannelId(1) })
        onMessage(userFrame(2) { setName("Ann").setChannelId(1) })
        onMessage(serverSync(1, "Welcome!"))
        events.clear()
    }

    @Test
    fun theNoticesOfTheModelReachTheEvents() {
        synced().onMessage(userFrame(2) { selfMute = true })

        assertThat(events).containsExactly(HumlaEvent.UserMuteChanged("Ann", muted = true, deafened = false))
    }

    @Test
    fun aPermissionDenialCarriesItsTypeAndTheServersReason() {
        val cases = mapOf(
            Mumble.PermissionDenied.DenyType.ChannelName to DenyType.CHANNEL_NAME,
            Mumble.PermissionDenied.DenyType.TextTooLong to DenyType.TEXT_TOO_LONG,
            Mumble.PermissionDenied.DenyType.TemporaryChannel to DenyType.TEMPORARY_CHANNEL,
            Mumble.PermissionDenied.DenyType.MissingCertificate to DenyType.MISSING_CERTIFICATE,
            Mumble.PermissionDenied.DenyType.UserName to DenyType.USER_NAME,
            Mumble.PermissionDenied.DenyType.ChannelFull to DenyType.CHANNEL_FULL,
            Mumble.PermissionDenied.DenyType.NestingLimit to DenyType.NESTING_LIMIT,
            Mumble.PermissionDenied.DenyType.ChannelCountLimit to DenyType.CHANNEL_COUNT_LIMIT,
            Mumble.PermissionDenied.DenyType.ChannelListenerLimit to DenyType.CHANNEL_LISTENER_LIMIT,
            Mumble.PermissionDenied.DenyType.UserListenerLimit to DenyType.USER_LISTENER_LIMIT,
            Mumble.PermissionDenied.DenyType.Permission to DenyType.OTHER,
        )
        for (type in cases.keys) eventful.onMessage(Mumble.PermissionDenied.newBuilder().setType(type).build())
        eventful.onMessage(
            Mumble.PermissionDenied.newBuilder().setType(Mumble.PermissionDenied.DenyType.Text).setReason("no").build(),
        )

        val denials = events.filterIsInstance<HumlaEvent.PermissionDenied>()
        assertThat(denials.map { it.type }).containsExactlyElementsIn(cases.values + DenyType.OTHER).inOrder()
        assertThat(denials.map { it.reason }).containsExactlyElementsIn(List(cases.size) { null } + "no").inOrder()
    }

    @Test
    fun userStatsArePublished() {
        eventful.onMessage(Mumble.UserStats.newBuilder().setSession(2).setOnlinesecs(10).build())

        val stats = events.filterIsInstance<HumlaEvent.UserStatsReceived>().single().stats
        assertThat(stats.session).isEqualTo(2)
        assertThat(stats.onlineSeconds).isEqualTo(10)
    }

    @Test
    fun aTextMessageNamesItsSenderOrNobodyForTheServerAndCarriesItsTargetsAsTheyAre() {
        synced()
        eventful.onMessage(
            Mumble.TextMessage.newBuilder().setActor(2).addChannelId(1).addChannelId(99).setMessage("hi").build(),
        )
        eventful.onMessage(Mumble.TextMessage.newBuilder().setActor(0).addSession(1).setMessage("motd").build())

        val messages = events.filterIsInstance<HumlaEvent.TextMessage>().map { it.message }
        assertThat(messages.map { it.actorName }).containsExactly("Ann", null).inOrder()
        assertThat(messages[0].targetChannels.map { it.name }).containsExactly("Lobby")
        assertThat(messages[1].targetUsers.single().name).isEqualTo("Me")
    }

    @Test
    fun aLocallyIgnoredSendersMessageIsDropped() {
        synced().onLocal(LocalInput.Ignore(2, true))

        eventful.onMessage(Mumble.TextMessage.newBuilder().setActor(2).setMessage("hi").build())

        assertThat(events).isEmpty()
    }

    @Test
    fun ourOwnRemovalIsPublishedAtOnceSinceTheConnectionEndsOverIt() {
        synced()
        drain()
        published.clear()
        eventful.onMessage(userFrame(4) { setName("Mod").setChannelId(0) })

        eventful.onMessage(userRemoveFrame(1, actor = 4, reason = "spam"))

        assertThat(published.single().user(4)!!.name).isEqualTo("Mod")
        assertThat(published.single().user(1)).isNull()
    }
}
