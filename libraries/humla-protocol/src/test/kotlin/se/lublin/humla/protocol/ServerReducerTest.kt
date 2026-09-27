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
import org.junit.Before
import org.junit.Test
import se.lublin.humla.model.Bytes
import se.lublin.humla.model.LocalUserSettings
import se.lublin.humla.model.ServerState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent

/** What [ServerReducer] makes of each frame: the next snapshot and the chat log notices. */
class ServerReducerTest {

    private val model = ReducerHarness(
        ServerState.empty(LocalUserSettings(mutedUserIds = setOf(40), ignoredUserIds = setOf(41))),
    )
    private val state get() = model.state

    /** A tree of Root(0) > Lobby(1), Games(2), with us (session 1) in the lobby and Ann (2) too. */
    @Before
    fun setUp() {
        model.feed(
            channelFrame(0, name = "Root"),
            channelFrame(1, parent = 0, name = "Lobby"),
            channelFrame(2, parent = 0, name = "Games"),
            userFrame(1) { setName("Me").setChannelId(1) },
            userFrame(2) { setName("Ann").setChannelId(1) },
            serverSync(1, "Welcome!"),
        )
        model.events.clear()
    }

    @Test
    fun aSnapshotIsNeverChangedByTheFramesAfterIt() {
        val before = state

        model.feed(userFrame(2) { setChannelId(2) }, channelFrame(3, parent = 0, name = "New"))

        assertThat(before.user(2)!!.channel).isEqualTo(1)
        assertThat(before.userIds(1)).containsExactly(2, 1).inOrder()
        assertThat(before.channel(3)).isNull()
        assertThat(state.userIds(2)).containsExactly(2)
    }

    @Test
    fun aFrameThatChangesNothingKeepsTheSnapshot() {
        val before = state

        model.feed(userFrame(2) { setChannelId(1) }, Mumble.Version.getDefaultInstance())

        assertThat(state).isSameInstanceAs(before)
    }

    @Test
    fun aChannelIsAddedUpdatedAndRemoved() {
        model.feed(channelFrame(3, parent = 0, name = "New"), channelFrame(3, name = "Renamed"))
        assertThat(state.channel(3)!!.name).isEqualTo("Renamed")
        assertThat(state.subchannelIds(0)).containsExactly(2, 1, 3).inOrder()

        model.feed(Mumble.ChannelRemove.newBuilder().setChannelId(3).build())

        assertThat(state.channel(3)).isNull()
        assertThat(state.subchannelIds(0)).containsExactly(2, 1).inOrder()
    }

    @Test
    fun theRootIsNeverRemoved() {
        model.feed(Mumble.ChannelRemove.newBuilder().setChannelId(0).build())

        assertThat(state.root).isNotNull()
    }

    @Test
    fun subchannelsAreOrderedByPositionThenNameAndFollowRenames() {
        model.feed(
            channelFrame(3, parent = 0, name = "b") { position = 1 },
            channelFrame(4, parent = 0, name = "a") { position = 1 },
            channelFrame(5, parent = 0, name = "z") { position = -1 },
        )
        assertThat(state.subchannelIds(0)).containsExactly(5, 2, 1, 4, 3).inOrder()

        model.feed(channelFrame(5) { position = 2 }, channelFrame(4, name = "c"))

        assertThat(state.subchannelIds(0)).containsExactly(2, 1, 3, 4, 5).inOrder()
    }

    @Test
    fun usersAreOrderedByNameIgnoringCaseAndFollowRenames() {
        model.feed(
            userFrame(3) { setName("bob").setChannelId(1) },
            userFrame(4) { setName("Carl").setChannelId(1) },
        )
        assertThat(state.userIds(1)).containsExactly(2, 3, 4, 1).inOrder()

        model.feed(userFrame(2) { setName("Zed") })

        assertThat(state.userIds(1)).containsExactly(3, 4, 1, 2).inOrder()
        assertThat(state.usersIn(1).map { it.name }).containsExactly("bob", "Carl", "Me", "Zed").inOrder()
    }

    @Test
    fun theRootChannelsPermissionsAreTheServerWideOnes() {
        model.feed(Mumble.PermissionQuery.newBuilder().setChannelId(0).setPermissions(0x42).build())
        model.feed(Mumble.PermissionQuery.newBuilder().setChannelId(2).setPermissions(0x7).build())

        assertThat(state.permissions).isEqualTo(0x42)
        assertThat(state.permissionsIn(0)).isEqualTo(0x42)
        assertThat(state.permissionsIn(2)).isEqualTo(0x7)
    }

    @Test
    fun aFlushClearsEveryChannelsPermissionsButTheQueriedOne() {
        model.feed(
            Mumble.PermissionQuery.newBuilder().setChannelId(1).setPermissions(0x1).build(),
            Mumble.PermissionQuery.newBuilder().setChannelId(2).setPermissions(0x2).setFlush(true).build(),
        )

        assertThat(state.channel(1)!!.permissions).isEqualTo(0)
        assertThat(state.channel(2)!!.permissions).isEqualTo(0x2)
    }

    @Test
    fun theWelcomeTextIsAnInfoLineAndServerSyncNamesUs() {
        model.feed(serverSync(1, "<b>Hi</b>"))

        assertThat(model.notices).containsExactly(HumlaEvent.LogMessage(HumlaEvent.Level.INFO, "<b>Hi</b>"))
        assertThat(state.self!!.name).isEqualTo("Me")
        assertThat(state.selfChannel!!.name).isEqualTo("Lobby")
    }

    @Test
    fun serverConfigIsKept() {
        model.feed(Mumble.ServerConfig.newBuilder().setImageMessageLength(1234).build())

        assertThat(state.serverSettings!!.imageMessageLength).isEqualTo(1234)
    }

    @Test
    fun enterRestrictionsAreKeptUntilTheServerChangesThem() {
        assertThat(state.channel(2)!!.canEnter).isTrue()
        assertThat(state.channel(2)!!.isEnterRestricted).isFalse()

        model.feed(channelFrame(2) { setIsEnterRestricted(true).setCanEnter(false) }, channelFrame(2, name = "Games 2"))
        assertThat(state.channel(2)!!.isEnterRestricted).isTrue()
        assertThat(state.channel(2)!!.canEnter).isFalse()

        model.feed(channelFrame(2) { canEnter = true })
        assertThat(state.channel(2)!!.canEnter).isTrue()
        assertThat(state.channel(2)!!.isEnterRestricted).isTrue()
    }

    @Test
    fun aDescriptionHashDropsTheDescriptionAndADescriptionItsHash() {
        model.feed(channelFrame(2) { descriptionHash = ByteString.copyFromUtf8("h") })
        assertThat(state.channel(2)!!.hasDescriptionHash).isTrue()
        assertThat(state.channel(2)!!.description).isNull()

        model.feed(channelFrame(2) { description = "text" })
        assertThat(state.channel(2)!!.hasDescriptionHash).isFalse()
        assertThat(state.channel(2)!!.description).isEqualTo("text")
    }

    @Test
    fun linksAreMutualWhenAddedOrRemovedAndOneSidedAsAFullSet() {
        model.feed(Mumble.ChannelState.newBuilder().setChannelId(1).addLinksAdd(2).build())
        assertThat(state.channel(1)!!.links).containsExactly(2)
        assertThat(state.channel(2)!!.links).containsExactly(1)

        model.feed(Mumble.ChannelState.newBuilder().setChannelId(2).addLinksRemove(1).build())
        assertThat(state.channel(1)!!.links).isEmpty()
        assertThat(state.channel(2)!!.links).isEmpty()

        model.feed(linksFrame(1, 2, 99))
        assertThat(state.channel(1)!!.links).containsExactly(2)
        assertThat(state.channel(2)!!.links).isEmpty()
    }

    @Test
    fun aRemovedChannelLeavesNoLinkBehind() {
        model.feed(
            channelFrame(3, parent = 0, name = "c"),
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksAdd(3).build(),
        )

        model.feed(Mumble.ChannelRemove.newBuilder().setChannelId(3).build())

        assertThat(state.channel(1)!!.links).isEmpty()
    }

    @Test
    fun addingAndRemovingALinkToAnUnknownChannelIsIgnored() {
        model.feed(
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksAdd(99).build(),
            Mumble.ChannelState.newBuilder().setChannelId(1).addLinksRemove(99).build(),
        )

        assertThat(state.channel(1)!!.links).isEmpty()
        assertThat(state.channel(99)).isNull()
    }

    @Test
    fun listenersAreTrackedPerChannelFromEveryUsersState() {
        model.feed(userFrame(1) { addListeningChannelAdd(2).addListeningChannelAdd(0) })
        model.feed(userFrame(2) { addListeningChannelAdd(2) })

        assertThat(state.listenerIds(2)).containsExactly(2, 1).inOrder()
        assertThat(state.listenerIds(0)).containsExactly(1)
        assertThat(state.user(1)!!.listening).containsExactly(0, 2)

        model.feed(userFrame(1) { addListeningChannelRemove(2) })

        assertThat(state.listenersOf(2).map { it.name }).containsExactly("Ann")
    }

    @Test
    fun aListenerForAnUnknownChannelIsIgnoredAndARemovedUserStopsListening() {
        model.feed(userFrame(2) { addListeningChannelAdd(2).addListeningChannelAdd(99) })
        assertThat(state.user(2)!!.listening).containsExactly(2)

        model.feed(userRemoveFrame(2))

        assertThat(state.listenerIds(2)).isEmpty()
        assertThat(state.channel(99)).isNull()
    }

    @Test
    fun aNewUserFrameMayAlreadyListen() {
        model.feed(userFrame(3) { setName("Bob").setChannelId(1).addListeningChannelAdd(2) })

        assertThat(state.listenersOf(2).map { it.name }).containsExactly("Bob")
    }

    @Test
    fun aNewUserIsAnnouncedAndStartsInTheRoot() {
        model.feed(userFrame(3) { setName("Bob") })

        assertThat(model.notices).containsExactly(HumlaEvent.UserJoinedServer("Bob"))
        assertThat(state.user(3)!!.channel).isEqualTo(0)
        assertThat(state.userIds(0)).containsExactly(3)
    }

    @Test
    fun aFrameForAnUnknownSessionWithoutANameIsDropped() {
        model.feed(userFrame(9) { setChannelId(1) })

        assertThat(state.user(9)).isNull()
        assertThat(model.events).isEmpty()
    }

    @Test
    fun aUserFromTheLocalHistoriesComesBackMutedAndIgnored() {
        model.feed(userFrame(4) { setName("Old").setUserId(40) }, userFrame(5) { setName("Pest").setUserId(41) })

        assertThat(state.user(4)!!.isLocalMuted).isTrue()
        assertThat(state.user(5)!!.isLocalIgnored).isTrue()
    }

    @Test
    fun ourOwnMuteChangesAreReported() {
        model.feed(
            userFrame(1) { selfMute = true },
            userFrame(1) { selfDeaf = true },
            userFrame(1) { setSelfMute(false).setSelfDeaf(false) },
        )

        assertThat(model.notices).containsExactly(
            HumlaEvent.SelfMuteChanged(muted = true, deafened = false),
            HumlaEvent.SelfMuteChanged(muted = true, deafened = true),
            HumlaEvent.SelfMuteChanged(muted = false, deafened = false),
        ).inOrder()
        assertThat(state.self!!.isSelfMuted).isFalse()
    }

    @Test
    fun aMuteChangeIsReportedOnlyForUsersInOurChannel() {
        model.feed(userFrame(3) { setName("Far").setChannelId(2) })
        model.events.clear()

        model.feed(userFrame(2) { selfMute = true }, userFrame(3) { selfMute = true })

        assertThat(model.notices).containsExactly(HumlaEvent.UserMuteChanged("Ann", muted = true, deafened = false))
    }

    @Test
    fun serverFlagsAreApplied() {
        model.feed(userFrame(2) { setMute(true).setDeaf(true).setSuppress(true).setPrioritySpeaker(true) })

        val ann = state.user(2)!!
        assertThat(listOf(ann.isMuted, ann.isDeafened, ann.isSuppressed, ann.isPrioritySpeaker)).doesNotContain(false)
    }

    @Test
    fun recordingIsReportedForUsAndForUsersInOurChannel() {
        model.feed(userFrame(3) { setName("Far").setChannelId(2) })
        model.events.clear()

        model.feed(
            userFrame(1) { recording = true },
            userFrame(2) { recording = true },
            userFrame(3) { recording = true },
            userFrame(2) { recording = false },
        )

        assertThat(model.notices).containsExactly(
            HumlaEvent.SelfRecordingChanged(true),
            HumlaEvent.UserRecordingChanged("Ann", true),
            HumlaEvent.UserRecordingChanged("Ann", false),
        ).inOrder()
    }

    /** As desktop Mumble: a recorder in a channel linked to ours, directly or through others, counts. */
    @Test
    fun recordingIsReportedForUsersInChannelsLinkedToOurs() {
        model.feed(
            channelFrame(3, parent = 0, name = "Linked"),
            channelFrame(4, parent = 0, name = "Chained"),
            linksFrame(1, 3),
            linksFrame(3, 1, 4),
            linksFrame(4, 3),
            userFrame(3) { setName("Near").setChannelId(3) },
            userFrame(4) { setName("Nearish").setChannelId(4) },
            userFrame(5) { setName("Far").setChannelId(2) },
        )
        model.events.clear()

        model.feed(
            userFrame(3) { recording = true },
            userFrame(4) { recording = true },
            userFrame(5) { recording = true },
        )

        assertThat(model.notices).containsExactly(
            HumlaEvent.UserRecordingChanged("Near", true),
            HumlaEvent.UserRecordingChanged("Nearish", true),
        ).inOrder()
    }

    @Test
    fun leavingOurChannelNamesTheDestinationAndWhoMovedThem() {
        model.feed(userFrame(3) { setName("Mod").setChannelId(1) })
        model.events.clear()

        model.feed(
            userFrame(2) { setChannelId(2).setActor(2) },
            userFrame(2) { setChannelId(1).setActor(2) },
            userFrame(2) { setChannelId(2).setActor(3) },
            userFrame(2) { channelId = 1 },
        )

        assertThat(model.notices).containsExactly(
            HumlaEvent.UserLeftChannel("Ann", "Games", actor = "Ann", byThemselves = true),
            HumlaEvent.UserEnteredChannel("Ann", "Games", actor = "Ann", byThemselves = true),
            HumlaEvent.UserLeftChannel("Ann", "Games", actor = "Mod", byThemselves = false),
            HumlaEvent.UserEnteredChannel("Ann", "Games", actor = null, byThemselves = false),
        ).inOrder()
    }

    @Test
    fun movesElsewhereAndOurOwnMovesAreNotReported() {
        model.feed(userFrame(3) { setName("Far").setChannelId(2) })
        model.events.clear()

        model.feed(userFrame(3) { setChannelId(0).setActor(3) }, userFrame(1) { setChannelId(2).setActor(1) })

        assertThat(model.notices).isEmpty()
        assertThat(state.selfChannel!!.id).isEqualTo(2)
    }

    @Test
    fun aUserStateNamingAnUnknownChannelStopsThere() {
        model.feed(userFrame(2) { setChannelId(99).setComment("hi").setMute(true) })

        assertThat(model.events).isEmpty()
        assertThat(state.user(2)!!.comment).isNull()
        assertThat(state.user(2)!!.isMuted).isTrue()
        assertThat(state.user(2)!!.channel).isEqualTo(1)
    }

    @Test
    fun aNewHashDropsTheBlobAndANewBlobItsHash() {
        model.feed(userFrame(2) { commentHash = ByteString.copyFromUtf8("c") })
        assertThat(state.user(2)!!.hasCommentHash).isTrue()

        model.feed(userFrame(2) { setComment("hello").setTexture(ByteString.copyFrom(byteArrayOf(1, 2))) })
        assertThat(state.user(2)!!.comment).isEqualTo("hello")
        assertThat(state.user(2)!!.hasCommentHash).isFalse()
        assertThat(state.user(2)!!.texture).isEqualTo(Bytes.of(byteArrayOf(1, 2)))

        model.feed(userFrame(2) { textureHash = ByteString.copyFromUtf8("t") })
        assertThat(state.user(2)!!.texture).isNull()
    }

    @Test
    fun beingKickedOrBannedIsAWarningNamingTheActor() {
        model.feed(userRemoveFrame(1, actor = 2, reason = "spam"))
        model.feed(userRemoveFrame(1, actor = 9, reason = "bye", ban = true))

        assertThat(model.notices).containsExactly(
            HumlaEvent.SelfKicked("Ann", "spam", ban = false),
            HumlaEvent.SelfKicked(null, "bye", ban = true),
        ).inOrder()
        assertThat(model.notices.map { it.level }.toSet()).containsExactly(HumlaEvent.Level.WARNING)
    }

    @Test
    fun anotherUserKickedBySomebodyIsAWarningAndOneWhoLeftIsInfo() {
        model.feed(userFrame(3) { setName("Bob") })
        model.events.clear()

        model.feed(userRemoveFrame(3, actor = 2, reason = "r", ban = true), userRemoveFrame(2, actor = 99))

        assertThat(model.notices).containsExactly(
            HumlaEvent.UserKicked("Bob", "Ann", "r", ban = true),
            HumlaEvent.UserLeftServer("Ann"),
        ).inOrder()
        assertThat(model.notices.map { it.level })
            .containsExactly(HumlaEvent.Level.WARNING, HumlaEvent.Level.INFO).inOrder()
        assertThat(state.user(2)).isNull()
        assertThat(state.userIds(1)).containsExactly(1)
    }

    @Test
    fun theSubtreeUserCountCountsEveryUserBelow() {
        model.feed(channelFrame(3, parent = 1, name = "Deep"), userFrame(3) { setName("x").setChannelId(3) })

        assertThat(state.subtreeUserCount(0)).isEqualTo(3)
        assertThat(state.subtreeUserCount(1)).isEqualTo(3)
        assertThat(state.subtreeUserCount(2)).isEqualTo(0)
        assertThat(state.flatten().map { it.id }).containsExactly(0, 2, 1, 3).inOrder()
    }

    @Test
    fun aLocalMuteIgnoreAndVolumeChangeTheUserAndAreRemembered() {
        model.feed(userFrame(2) { setUserId(7).setHash("cert") })

        model.local(LocalInput.Mute(2, true))
        model.local(LocalInput.Ignore(2, true))
        model.local(LocalInput.Volume(2, 0.5f))

        val ann = state.user(2)!!
        assertThat(listOf(ann.isLocalMuted, ann.isLocalIgnored, ann.localVolume)).containsExactly(true, true, 0.5f)
        assertThat(state.local.mutedUserIds).containsExactly(40, 7)
        assertThat(state.local.ignoredUserIds).containsExactly(41, 7)
        assertThat(state.local.volumes).containsExactly("cert:cert", 0.5f)

        model.feed(userRemoveFrame(2), userFrame(8) { setName("Ann").setUserId(7).setHash("cert") })

        val back = state.user(8)!!
        assertThat(listOf(back.isLocalMuted, back.isLocalIgnored, back.localVolume)).containsExactly(true, true, 0.5f)
    }

    @Test
    fun aVolumeBackAtOneIsForgottenAndAnUnmuteLeavesTheHistory() {
        model.feed(userFrame(2) { setUserId(40).setHash("cert") })
        model.local(LocalInput.Volume(2, 2f))

        model.local(LocalInput.Volume(2, 1f))
        model.local(LocalInput.Mute(2, false))

        assertThat(state.local.volumes).isEmpty()
        assertThat(state.local.mutedUserIds).isEmpty()
    }

    @Test
    fun aVolumeIsKeptByNameOnThisServerWithoutACertificate() {
        val local = LocalUserSettings(serverScope = "host:1", volumes = mapOf("name:host:1:Ann" to 0.25f))
        val scoped = ReducerHarness(ServerState.empty(local))

        scoped.feed(channelFrame(0, name = "Root"), userFrame(2) { setName("Ann") })

        assertThat(scoped.state.user(2)!!.localVolume).isEqualTo(0.25f)
    }

    @Test
    fun aVolumeFollowsIdentityChanges() {
        val scoped = ReducerHarness(ServerState.empty(LocalUserSettings(volumes = mapOf("cert:abc" to 0.3f))))
        scoped.feed(channelFrame(0, name = "Root"), userFrame(2) { setName("Ann") })
        assertThat(scoped.state.user(2)!!.localVolume).isEqualTo(1f)

        scoped.feed(userFrame(2) { hash = "abc" })

        assertThat(scoped.state.user(2)!!.localVolume).isEqualTo(0.3f)
    }

    @Test
    fun aLocalChangeForAnUnknownSessionChangesNothing() {
        val before = state

        model.local(LocalInput.Mute(99, true))

        assertThat(state).isSameInstanceAs(before)
    }

    @Test
    fun aSnapshotAssembledFromItsRecordsHasTheSameTree() {
        val reduced = ReducerHarness().feed(*syncFrames(channels = 200, users = 60).toTypedArray())
        val listening = reduced.users.values.map { if (it.session % 3 == 0) it.copy(listening = setOf(7)) else it }

        val assembled = ServerState.of(reduced.channels.values, listening, reduced.selfSession)

        for (id in reduced.channels.keys) {
            assertThat(assembled.subchannelIds(id)).isEqualTo(reduced.subchannelIds(id))
            assertThat(assembled.userIds(id)).isEqualTo(reduced.userIds(id))
        }
        assertThat(assembled.listenerIds(7)).containsExactlyElementsIn((3..60 step 3).toList())
        assertThat(assembled.self).isEqualTo(reduced.self)
    }
}
