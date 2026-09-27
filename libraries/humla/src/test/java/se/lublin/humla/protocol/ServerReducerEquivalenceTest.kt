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

import com.google.common.truth.Truth.assertWithMessage
import com.google.protobuf.MessageLite
import org.junit.Test
import se.lublin.humla.model.LocalUserSettings
import se.lublin.humla.model.LocalVolumes
import se.lublin.humla.model.ServerState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent
import kotlin.random.Random

/**
 * [ServerReducer] against the mutable [ModelHandler] it replaces, on random frame sequences: the
 * same tree, the same users and the same notices. Order within a list is left out, since the
 * reducer also re-sorts on renames, which the old model never did, and so are the duplicate
 * subchannel and link entries the old model could make of one channel.
 */
class ServerReducerEquivalenceTest {

    @Test
    fun randomFrameSequencesBuildTheSameModelAndNotices() {
        for (seed in 1..20) check(seed)
    }

    private fun check(seed: Int) {
        val random = Random(seed)
        val oldEvents = mutableListOf<HumlaEvent>()
        val volumes = mapOf("cert:h3" to 0.5f)
        val old = ModelHandler({ oldEvents += it }, listOf(40), listOf(41), LocalVolumes(null, volumes))
        val local = LocalUserSettings(volumes = volumes, mutedUserIds = setOf(40), ignoredUserIds = setOf(41))
        val new = ReducerHarness(ServerState.empty(local))
        val frames = randomFrames(random)
        for (frame in frames) {
            old.onMessage(frame)
            new.feed(frame)
        }
        val context = "seed $seed"
        assertWithMessage(context).that(new.notices)
            .containsExactlyElementsIn(oldEvents.filterIsInstance<HumlaEvent.Notice>()).inOrder()
        compare(old, new.state, context)
    }

    private fun compare(old: ModelHandler, state: ServerState, context: String) {
        assertWithMessage("$context permissions").that(state.permissions).isEqualTo(old.permissions)
        for (id in 0 until CHANNELS) {
            val o = old.getChannel(id)
            val n = state.channel(id)
            assertWithMessage("$context channel $id exists").that(n != null).isEqualTo(o != null)
            if (o == null || n == null) continue
            val what = "$context channel $id"
            assertWithMessage("$what name").that(n.name).isEqualTo(o.name)
            assertWithMessage("$what parent").that(n.parent).isEqualTo(o.parent?.id)
            assertWithMessage("$what position").that(n.position).isEqualTo(o.position)
            assertWithMessage("$what permissions").that(n.permissions).isEqualTo(o.permissions)
            assertWithMessage("$what canEnter").that(n.canEnter).isEqualTo(o.canEnter)
            assertWithMessage("$what subchannels").that(state.subchannelIds(id))
                .containsExactlyElementsIn(o.subchannels.map { it.id }.distinct())
            assertWithMessage("$what users").that(state.userIds(id))
                .containsExactlyElementsIn(o.users.map { it.session })
            assertWithMessage("$what listeners").that(state.listenerIds(id))
                .containsExactlyElementsIn(o.listeners.map { it.session })
            assertWithMessage("$what links").that(n.links)
                .containsExactlyElementsIn(o.links.map { it.id }.filter { old.getChannel(it) != null }.distinct())
        }
        for (session in 1..USERS) {
            val o = old.getUser(session)
            val n = state.user(session)
            val what = "$context user $session"
            assertWithMessage("$what exists").that(n != null).isEqualTo(o != null && o.channel != null)
            if (o == null || n == null) continue
            assertWithMessage("$what channel").that(n.channel).isEqualTo(o.channel?.id)
            assertWithMessage("$what name").that(n.name).isEqualTo(o.name)
            assertWithMessage("$what flags").that(listOf(n.isMuted, n.isSelfMuted, n.isSelfDeafened, n.isRecording))
                .isEqualTo(listOf(o.isMuted, o.isSelfMuted, o.isSelfDeafened, o.isRecording))
            assertWithMessage("$what local").that(listOf(n.isLocalMuted, n.isLocalIgnored, n.localVolume))
                .isEqualTo(listOf(o.isLocalMuted, o.isLocalIgnored, o.localVolume))
            assertWithMessage("$what comment").that(n.comment).isEqualTo(o.comment)
        }
    }

    /**
     * Frames over a fixed tree of [TREE] channels and [USERS] sessions. The old model kept removed
     * users and let removed channels linger in their users, so a removed session is never used
     * again, and only [REMOVABLE] leaf channels, which hold no users, are removed.
     */
    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    private fun randomFrames(random: Random): List<MessageLite> = buildList {
        add(channelFrame(0, name = "Root"))
        val removedUsers = mutableSetOf<Int>()
        val removedChannels = mutableSetOf<Int>()
        repeat(OPERATIONS) { step ->
            val channel = random.nextInt(TREE)
            val other = random.nextInt(TREE)
            val leaf = TREE + random.nextInt(REMOVABLE)
            val session = random.nextInt(1, USERS + 1)
            val actor = random.nextInt(0, USERS + 1).takeIf { it !in removedUsers } ?: 0
            if (session in removedUsers) return@repeat
            val frame = when (random.nextInt(13)) {
                0, 1 -> channelFrame(channel, parent = other, name = "c$channel") { position = random.nextInt(3) }
                2 -> if (leaf in removedChannels) null else channelFrame(leaf, parent = channel, name = "leaf$leaf")
                3 -> Mumble.ChannelRemove.newBuilder().setChannelId(leaf).build().also { removedChannels += leaf }
                4 -> Mumble.ChannelState.newBuilder().setChannelId(channel)
                    .addLinksAdd(if (step % 2 == 0) other else leaf).build()
                5 -> Mumble.ChannelState.newBuilder().setChannelId(channel).addLinksRemove(other).build()
                6 -> userFrame(session) {
                    setName("u$session").setChannelId(channel).setHash("h$session").setUserId(session * 10)
                }
                7 -> userFrame(session) { setChannelId(channel).setActor(actor) }
                8 -> userFrame(session) { setSelfMute(random.nextBoolean()).setRecording(random.nextBoolean()) }
                9 -> userFrame(session) { addListeningChannelAdd(channel).addListeningChannelRemove(other) }
                10 -> userRemoveFrame(session, actor = actor, reason = "r$step")
                    .also { removedUsers += session }
                11 -> Mumble.PermissionQuery.newBuilder().setChannelId(channel).setPermissions(step)
                    .setFlush(random.nextInt(10) == 0).build()
                else -> if (random.nextInt(5) == 0) serverSync(session) else userFrame(session) {
                    setComment("comment $step").setMute(random.nextBoolean())
                }
            }
            frame?.let(::add)
        }
    }

    private companion object {
        const val TREE = 8
        const val REMOVABLE = 4
        const val CHANNELS = TREE + REMOVABLE
        const val USERS = 8
        const val OPERATIONS = 600
    }
}
