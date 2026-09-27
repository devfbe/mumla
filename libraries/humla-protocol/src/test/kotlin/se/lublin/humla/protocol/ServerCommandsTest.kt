/*
 * Copyright (C) 2026 The Mumla Authors
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
import com.google.protobuf.MessageLite
import org.junit.Test
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protobuf.Mumble

class ServerCommandsTest {
    private val sent = mutableListOf<Pair<HumlaTCPMessageType, MessageLite>>()
    private val commands = ServerCommands { message, type -> sent += type to message }

    private inline fun <reified T : MessageLite> single(type: HumlaTCPMessageType): T {
        assertThat(sent.map { it.first }).containsExactly(type)
        return sent.single().second as T
    }

    /** Opus and no CELT version: Opus is the only codec this client has. */
    @Test
    fun theHandshakeSendsTheVersionThenAnOpusOnlyAuthenticate() {
        commands.handshake("Mumla 1", "14", "me", "pw", listOf("t1", "t2"))

        assertThat(sent.map { it.first })
            .containsExactly(HumlaTCPMessageType.Version, HumlaTCPMessageType.Authenticate).inOrder()
        val version = sent[0].second as Mumble.Version
        assertThat(version.versionV1).isEqualTo(0x010500)
        assertThat(version.versionV2).isEqualTo(0x0001_0005_0000_0000L)
        assertThat(version.release).isEqualTo("Mumla 1")
        assertThat(version.os).isEqualTo("Android")
        assertThat(version.osVersion).isEqualTo("14")
        val auth = sent[1].second as Mumble.Authenticate
        assertThat(auth.username).isEqualTo("me")
        assertThat(auth.password).isEqualTo("pw")
        assertThat(auth.opus).isTrue()
        assertThat(auth.celtVersionsList).isEmpty()
        assertThat(auth.tokensList).containsExactly("t1", "t2").inOrder()
    }

    @Test
    fun accessTokensGoOutAsAnAuthenticateWithOnlyTheTokens() {
        commands.sendAccessTokens(listOf("a", "b"))

        val auth = single<Mumble.Authenticate>(HumlaTCPMessageType.Authenticate)
        assertThat(auth.tokensList).containsExactly("a", "b").inOrder()
        assertThat(auth.hasUsername()).isFalse()
    }

    @Test
    fun listeningAddsOrRemovesTheChannelWithoutMovingTheUser() {
        commands.setListening(1, 5, listen = true)
        commands.setListening(1, 6, listen = false)

        val states = sent.map { it.second as Mumble.UserState }
        assertThat(states.map { it.session }).containsExactly(1, 1)
        assertThat(states[0].listeningChannelAddList).containsExactly(5)
        assertThat(states[0].listeningChannelRemoveList).isEmpty()
        assertThat(states[1].listeningChannelRemoveList).containsExactly(6)
        assertThat(states[1].listeningChannelAddList).isEmpty()
        assertThat(states.none { it.hasChannelId() }).isTrue()
    }

    @Test
    fun movingAUserSetsItsChannel() {
        commands.moveUser(3, 7)

        val state = single<Mumble.UserState>(HumlaTCPMessageType.UserState)
        assertThat(state.session).isEqualTo(3)
        assertThat(state.channelId).isEqualTo(7)
    }

    @Test
    fun aNewChannelCarriesEveryField() {
        commands.createChannel(2, "name", "desc", 4, temporary = true)

        val state = single<Mumble.ChannelState>(HumlaTCPMessageType.ChannelState)
        assertThat(state.parent).isEqualTo(2)
        assertThat(state.name).isEqualTo("name")
        assertThat(state.description).isEqualTo("desc")
        assertThat(state.position).isEqualTo(4)
        assertThat(state.temporary).isTrue()
        assertThat(state.hasChannelId()).isFalse()
    }

    @Test
    fun removingAChannelNamesIt() {
        commands.removeChannel(9)

        assertThat(single<Mumble.ChannelRemove>(HumlaTCPMessageType.ChannelRemove).channelId).isEqualTo(9)
    }

    @Test
    fun linkingAndUnlinkingEditTheLinksOfTheFirstChannel() {
        commands.linkChannels(1, 2)
        commands.unlinkChannels(1, listOf(3, 4))

        val states = sent.map { it.second as Mumble.ChannelState }
        assertThat(states[0].channelId).isEqualTo(1)
        assertThat(states[0].linksAddList).containsExactly(2)
        assertThat(states[1].channelId).isEqualTo(1)
        assertThat(states[1].linksRemoveList).containsExactly(3, 4).inOrder()
    }

    @Test
    fun theBlobRequestsAskForExactlyOneThing() {
        commands.requestComment(1)
        commands.requestAvatar(2)
        commands.requestChannelDescription(3)

        assertThat(sent.map { it.first }).containsExactly(
            HumlaTCPMessageType.RequestBlob, HumlaTCPMessageType.RequestBlob, HumlaTCPMessageType.RequestBlob,
        )
        val blobs = sent.map { it.second as Mumble.RequestBlob }
        assertThat(blobs[0].sessionCommentList).containsExactly(1)
        assertThat(blobs[0].sessionTextureList).isEmpty()
        assertThat(blobs[1].sessionTextureList).containsExactly(2)
        assertThat(blobs[1].sessionCommentList).isEmpty()
        assertThat(blobs[2].channelDescriptionList).containsExactly(3)
    }

    @Test
    fun permissionsAreQueriedPerChannel() {
        commands.requestPermissions(4)

        assertThat(single<Mumble.PermissionQuery>(HumlaTCPMessageType.PermissionQuery).channelId).isEqualTo(4)
    }

    @Test
    fun userStatsAreRequestedInFull() {
        commands.requestUserStats(7)

        val request = single<Mumble.UserStats>(HumlaTCPMessageType.UserStats)
        assertThat(request.session).isEqualTo(7)
        assertThat(request.statsOnly).isFalse()
    }

    @Test
    fun registeringAUserSetsUserIdZero() {
        commands.registerUser(5)

        val state = single<Mumble.UserState>(HumlaTCPMessageType.UserState)
        assertThat(state.session).isEqualTo(5)
        assertThat(state.userId).isEqualTo(0)
        assertThat(state.hasUserId()).isTrue()
    }

    @Test
    fun aKickOrBanCarriesItsReason() {
        commands.kickBanUser(5, "spam", ban = true)

        val remove = single<Mumble.UserRemove>(HumlaTCPMessageType.UserRemove)
        assertThat(remove.session).isEqualTo(5)
        assertThat(remove.reason).isEqualTo("spam")
        assertThat(remove.ban).isTrue()
    }

    @Test
    fun commentAndPrioritySpeakerAreUserStates() {
        commands.setComment(2, "hi")
        commands.setPrioritySpeaker(2, true)

        val states = sent.map { it.second as Mumble.UserState }
        assertThat(states[0].comment).isEqualTo("hi")
        assertThat(states[1].prioritySpeaker).isTrue()
        assertThat(states.map { it.session }).containsExactly(2, 2)
    }

    /** Unmuting also lifts a suppression; muting leaves it to the server. */
    @Test
    fun anAdminUnmuteLiftsTheSuppressionAndAMuteDoesNotTouchIt() {
        commands.setMuteDeaf(2, mute = false, deaf = false)
        commands.setMuteDeaf(2, mute = true, deaf = true)

        val states = sent.map { it.second as Mumble.UserState }
        assertThat(states[0].hasSuppress()).isTrue()
        assertThat(states[0].suppress).isFalse()
        assertThat(states[1].hasSuppress()).isFalse()
        assertThat(states[1].mute).isTrue()
        assertThat(states[1].deaf).isTrue()
    }

    /** Our own state needs no session: the server applies it to the sender. */
    @Test
    fun selfMuteAndDeafenNameNoSession() {
        commands.setSelfMuteDeaf(mute = true, deaf = false)

        val state = single<Mumble.UserState>(HumlaTCPMessageType.UserState)
        assertThat(state.hasSession()).isFalse()
        assertThat(state.selfMute).isTrue()
        assertThat(state.selfDeaf).isFalse()
    }

    @Test
    fun textMessagesTargetAUserAChannelOrATree() {
        commands.textToUser(3, "to user")
        commands.textToChannel(4, "to channel", tree = false)
        commands.textToChannel(5, "to tree", tree = true)

        val texts = sent.map { it.second as Mumble.TextMessage }
        assertThat(texts[0].sessionList).containsExactly(3)
        assertThat(texts[0].message).isEqualTo("to user")
        assertThat(texts[1].channelIdList).containsExactly(4)
        assertThat(texts[1].treeIdList).isEmpty()
        assertThat(texts[2].treeIdList).containsExactly(5)
        assertThat(texts[2].channelIdList).isEmpty()
    }

    @Test
    fun aVoiceTargetCarriesItsIdAndTarget() {
        val target = Mumble.VoiceTarget.Target.newBuilder().setChannelId(6).setLinks(true).build()

        commands.registerVoiceTarget(3, target)

        val voiceTarget = single<Mumble.VoiceTarget>(HumlaTCPMessageType.VoiceTarget)
        assertThat(voiceTarget.id).isEqualTo(3)
        assertThat(voiceTarget.targetsList).containsExactly(target)
    }
}
