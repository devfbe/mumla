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
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.HumlaEvent.DenyType

/** What [ModelHandler] publishes for each frame, the chat log notices in particular. */
class ModelHandlerEventsTest {

    private val events = mutableListOf<HumlaEvent>()
    private val handler = ModelHandler({ events += it }, localMuteHistory = listOf(40), localIgnoreHistory = listOf(41))

    private inline fun <reified T : HumlaEvent> published(): List<T> = events.filterIsInstance<T>()

    private fun notices(): List<HumlaEvent.Notice> = published()

    /** A tree of Root(0) > Lobby(1), Games(2), with us (session 1) in the lobby and Ann (2) too. */
    @Before
    fun setUp() {
        handler.onMessage(channel(0, name = "Root"))
        handler.onMessage(channel(1, parent = 0, name = "Lobby"))
        handler.onMessage(channel(2, parent = 0, name = "Games"))
        handler.onMessage(userState(1) { setName("Me").setChannelId(1) })
        handler.onMessage(userState(2) { setName("Ann").setChannelId(1) })
        handler.onMessage(Mumble.ServerSync.newBuilder().setSession(1).setWelcomeText("Welcome!").build())
        events.clear()
    }

    // ---- the tree -------------------------------------------------------------------------------

    @Test
    fun aNewChannelIsAddedAndAKnownOneUpdated() {
        handler.onMessage(channel(3, parent = 0, name = "New"))
        handler.onMessage(channel(3, name = "Renamed"))
        handler.onMessage(Mumble.ChannelRemove.newBuilder().setChannelId(3).build())

        assertThat(events.map { it::class }).containsExactly(
            HumlaEvent.ChannelAdded::class,
            HumlaEvent.ChannelStateUpdated::class,
            HumlaEvent.ChannelRemoved::class,
        ).inOrder()
        assertThat(published<HumlaEvent.ChannelStateUpdated>().single().channel.name).isEqualTo("Renamed")
    }

    @Test
    fun theRootChannelsPermissionsAreTheServerWideOnes() {
        handler.onMessage(Mumble.PermissionQuery.newBuilder().setChannelId(0).setPermissions(0x42).build())

        assertThat(handler.permissions).isEqualTo(0x42)
        assertThat(published<HumlaEvent.ChannelPermissionsUpdated>().single().channel.id).isEqualTo(0)
    }

    @Test
    fun theWelcomeTextIsAnInfoLine() {
        handler.onMessage(Mumble.ServerSync.newBuilder().setSession(1).setWelcomeText("<b>Hi</b>").build())

        assertThat(notices()).containsExactly(HumlaEvent.LogMessage(HumlaEvent.Level.INFO, "<b>Hi</b>"))
    }

    // ---- users ----------------------------------------------------------------------------------

    @Test
    fun aNewUserIsAnnouncedThenPublishedAsConnected() {
        handler.onMessage(userState(3) { setName("Bob") })

        assertThat(events.map { it::class }).containsExactly(
            HumlaEvent.UserJoinedServer::class,
            HumlaEvent.UserConnected::class,
        ).inOrder()
        assertThat(notices()).containsExactly(HumlaEvent.UserJoinedServer("Bob"))
        assertThat(handler.getUser(3)!!.getChannel()!!.id).isEqualTo(0)
    }

    @Test
    fun aUserFromTheLocalHistoriesComesBackMutedAndIgnored() {
        handler.onMessage(userState(4) { setName("Old").setUserId(40) })
        handler.onMessage(userState(5) { setName("Pest").setUserId(41) })

        assertThat(handler.getUser(4)!!.isLocalMuted()).isTrue()
        assertThat(handler.getUser(5)!!.isLocalIgnored()).isTrue()
    }

    @Test
    fun ourOwnMuteChangesAreReported() {
        handler.onMessage(userState(1) { setSelfMute(true) })
        handler.onMessage(userState(1) { setSelfDeaf(true) })
        handler.onMessage(userState(1) { setSelfMute(false).setSelfDeaf(false) })

        assertThat(notices()).containsExactly(
            HumlaEvent.SelfMuteChanged(muted = true, deafened = false),
            HumlaEvent.SelfMuteChanged(muted = true, deafened = true),
            HumlaEvent.SelfMuteChanged(muted = false, deafened = false),
        ).inOrder()
    }

    @Test
    fun aMuteChangeIsReportedOnlyForUsersInOurChannel() {
        handler.onMessage(userState(3) { setName("Far").setChannelId(2) })
        events.clear()

        handler.onMessage(userState(2) { setSelfMute(true) })
        handler.onMessage(userState(3) { setSelfMute(true) })

        assertThat(notices()).containsExactly(HumlaEvent.UserMuteChanged("Ann", muted = true, deafened = false))
    }

    @Test
    fun recordingIsReportedForUsAndForUsersInOurChannel() {
        handler.onMessage(userState(3) { setName("Far").setChannelId(2) })
        events.clear()

        handler.onMessage(userState(1) { setRecording(true) })
        handler.onMessage(userState(2) { setRecording(true) })
        handler.onMessage(userState(3) { setRecording(true) })
        handler.onMessage(userState(2) { setRecording(false) })

        assertThat(notices()).containsExactly(
            HumlaEvent.SelfRecordingChanged(true),
            HumlaEvent.UserRecordingChanged("Ann", true),
            HumlaEvent.UserRecordingChanged("Ann", false),
        ).inOrder()
    }

    @Test
    fun leavingOurChannelNamesTheDestinationAndWhoMovedThem() {
        handler.onMessage(userState(3) { setName("Mod").setChannelId(1) })
        events.clear()

        handler.onMessage(userState(2) { setChannelId(2).setActor(2) })
        handler.onMessage(userState(2) { setChannelId(1).setActor(2) })
        handler.onMessage(userState(2) { setChannelId(2).setActor(3) })
        handler.onMessage(userState(2) { setChannelId(1) })

        assertThat(notices()).containsExactly(
            HumlaEvent.UserLeftChannel("Ann", "Games", actor = "Ann", byThemselves = true),
            HumlaEvent.UserEnteredChannel("Ann", "Games", actor = "Ann", byThemselves = true),
            HumlaEvent.UserLeftChannel("Ann", "Games", actor = "Mod", byThemselves = false),
            HumlaEvent.UserEnteredChannel("Ann", "Games", actor = null, byThemselves = false),
        ).inOrder()
        val moves = published<HumlaEvent.UserJoinedChannel>()
        assertThat(moves.map { it.oldChannel?.id to it.newChannel.id })
            .containsExactly(1 to 2, 2 to 1, 1 to 2, 2 to 1).inOrder()
    }

    @Test
    fun movesElsewhereAndOurOwnMovesAreNotReported() {
        handler.onMessage(userState(3) { setName("Far").setChannelId(2) })
        events.clear()

        handler.onMessage(userState(3) { setChannelId(0).setActor(3) })
        handler.onMessage(userState(1) { setChannelId(2).setActor(1) })

        assertThat(notices()).isEmpty()
        assertThat(published<HumlaEvent.UserJoinedChannel>()).hasSize(2)
    }

    @Test
    fun aUserStateNamingAnUnknownChannelStopsThere() {
        handler.onMessage(userState(2) { setChannelId(99).setComment("hi") })

        assertThat(events).isEmpty()
        assertThat(handler.getUser(2)!!.getComment()).isNull()
    }

    // ---- removals -------------------------------------------------------------------------------

    @Test
    fun beingKickedOrBannedIsAWarningNamingTheActor() {
        handler.onMessage(userRemove(1, actor = 2, reason = "spam"))
        handler.onMessage(userRemove(1, actor = 9, reason = "bye", ban = true))

        assertThat(notices()).containsExactly(
            HumlaEvent.SelfKicked("Ann", "spam", ban = false),
            HumlaEvent.SelfKicked(null, "bye", ban = true),
        ).inOrder()
        assertThat(notices().map { it.level }.toSet()).containsExactly(HumlaEvent.Level.WARNING)
    }

    @Test
    fun anotherUserKickedBySomebodyIsAWarningAndOneWhoLeftIsInfo() {
        handler.onMessage(userState(3) { setName("Bob") })
        events.clear()

        handler.onMessage(userRemove(3, actor = 2, reason = "r", ban = true))
        handler.onMessage(userRemove(2, actor = 99, reason = ""))

        assertThat(notices()).containsExactly(
            HumlaEvent.UserKicked("Bob", "Ann", "r", ban = true),
            HumlaEvent.UserLeftServer("Ann"),
        ).inOrder()
        assertThat(notices().map { it.level })
            .containsExactly(HumlaEvent.Level.WARNING, HumlaEvent.Level.INFO).inOrder()
        val removed = published<HumlaEvent.UserRemoved>()
        assertThat(removed.map { it.user?.getSession() }).containsExactly(3, 2).inOrder()
        assertThat(removed[0].user!!.getChannel()).isNull()
    }

    // ---- permissions and messages ---------------------------------------------------------------

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
            Mumble.PermissionDenied.DenyType.Permission to DenyType.OTHER,
        )
        for (type in cases.keys) {
            handler.onMessage(Mumble.PermissionDenied.newBuilder().setType(type).build())
        }
        handler.onMessage(
            Mumble.PermissionDenied.newBuilder().setType(Mumble.PermissionDenied.DenyType.Text).setReason("no").build()
        )

        val denials = published<HumlaEvent.PermissionDenied>()
        assertThat(denials.map { it.type }).containsExactlyElementsIn(cases.values + DenyType.OTHER).inOrder()
        assertThat(denials.map { it.reason }).containsExactlyElementsIn(List(cases.size) { null } + "no").inOrder()
    }

    @Test
    fun aTextMessageNamesItsSenderOrNobodyForTheServer() {
        handler.onMessage(Mumble.TextMessage.newBuilder().setActor(2).addChannelId(1).setMessage("hi").build())
        handler.onMessage(Mumble.TextMessage.newBuilder().setActor(0).addSession(1).setMessage("motd").build())

        val messages = published<HumlaEvent.TextMessage>().map { it.message }
        assertThat(messages.map { it.actorName }).containsExactly("Ann", null).inOrder()
        assertThat(messages[0].targetChannels.single().id).isEqualTo(1)
        assertThat(messages[1].targetUsers.single().getSession()).isEqualTo(1)
    }

    @Test
    fun aLocallyIgnoredSendersMessageIsDropped() {
        handler.getUser(2)!!.setLocalIgnored(true)

        handler.onMessage(Mumble.TextMessage.newBuilder().setActor(2).setMessage("hi").build())

        assertThat(events).isEmpty()
    }

    private fun channel(id: Int, parent: Int? = null, name: String): Mumble.ChannelState =
        Mumble.ChannelState.newBuilder()
            .setChannelId(id)
            .setName(name)
            .also { if (parent != null) it.setParent(parent) }
            .build()

    private fun userRemove(session: Int, actor: Int, reason: String, ban: Boolean = false): Mumble.UserRemove =
        Mumble.UserRemove.newBuilder().setSession(session).setActor(actor).setReason(reason).setBan(ban).build()

    private fun userState(session: Int, build: Mumble.UserState.Builder.() -> Unit): Mumble.UserState =
        Mumble.UserState.newBuilder().setSession(session).apply(build).build()
}
