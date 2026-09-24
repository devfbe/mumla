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
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.mockk
import org.junit.Test
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.NoopObserver
import se.lublin.humla.testutil.SilentLogger
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The model race between the protocol thread and the main thread.
 *
 * The protocol thread feeds 5 000 frames through [ModelHandler] (channels building a tree, users
 * joining and moving), while the main thread walks the tree the way the app's channel list does:
 * `getChannel(rootId)`, then `getSubchannelUserCount()`, `getUsers()` and `getSubchannels()`
 * recursively. On an unguarded model this fails in two ways, each asserted below:
 *
 * 1. `ConcurrentModificationException` out of the walk (from `getSubchannelUserCount` iterating
 *    the live list), which on the device is a crash.
 * 2. `HashMap.get` returning null for a present key during a concurrent rehash, which renders an
 *    empty channel list.
 *
 * Every assertion is about state accumulated while both threads ran; checking after the feeder has
 * joined would prove nothing.
 */
class ModelRaceTest {

    /** The number of channel frames fed. */
    private val channelFrames = 500
    private val userFrames = 1_500
    private val churnFrames = 3_000

    @Test
    fun aChannelListWalkSurvivesAServerSyncOnTheProtocolThread() {
        val handler = ModelHandler(
            mockk(relaxed = true),
            NoopObserver(),
            SilentLogger,
            null,
            null,
        )
        // The root channel the walk starts from exists before either thread runs: the question is
        // what happens to everything below it.
        handler.messageChannelState(channel(0, name = "Root"))

        val highestChannel = AtomicInteger(0)
        val highestSession = AtomicInteger(-1)
        val done = AtomicBoolean(false)
        val frames = AtomicInteger(0)
        val failedAtFrame = AtomicInteger(-1)
        val walkFailure = AtomicReference<Throwable?>()
        val nullReadsForPresentKeys = AtomicInteger(0)
        val walks = AtomicInteger(0)
        val concurrentWalks = AtomicInteger(0)

        val protocolThread = thread(name = "humla-protocol") {
            for (id in 1..channelFrames) {
                handler.messageChannelState(channel(id, parent = id / 4, name = "channel $id"))
                highestChannel.set(id)
                frames.incrementAndGet()
            }
            for (session in 0 until userFrames) {
                handler.messageUserState(
                    user(session, name = "user $session", channelId = session % channelFrames + 1)
                )
                highestSession.set(session)
                frames.incrementAndGet()
            }
            // The churn a synchronised server keeps producing: users move, and channels are
            // re-announced, which re-sorts them into their parent's subchannel list.
            val random = Random(7)
            repeat(churnFrames) { i ->
                if (i % 3 == 0) {
                    val id = random.nextInt(channelFrames) + 1
                    handler.messageChannelState(channel(id, parent = id / 4, name = "channel $id"))
                } else {
                    handler.messageUserState(
                        user(random.nextInt(userFrames), channelId = random.nextInt(channelFrames) + 1)
                    )
                }
                frames.incrementAndGet()
            }
            done.set(true)
        }

        val mainWalk = thread(name = "main-walk") {
            val random = Random(11)
            try {
                while (!done.get()) {
                    val framesAtWalkStart = frames.get()
                    // Resolve the root channel by id, then walk. A null here is the HashMap-resize
                    // read, not a missing channel.
                    val root = handler.getChannel(0)
                    if (root == null) nullReadsForPresentKeys.incrementAndGet() else constructNodes(root)

                    val knownChannel = highestChannel.get()
                    if (handler.getChannel(random.nextInt(knownChannel + 1)) == null) {
                        nullReadsForPresentKeys.incrementAndGet()
                    }
                    val knownSession = highestSession.get()
                    if (knownSession >= 0 && handler.getUser(random.nextInt(knownSession + 1)) == null) {
                        nullReadsForPresentKeys.incrementAndGet()
                    }
                    walks.incrementAndGet()
                    if (frames.get() > framesAtWalkStart) concurrentWalks.incrementAndGet()
                }
            } catch (t: Throwable) {
                failedAtFrame.set(frames.get())
                walkFailure.set(t)
            }
        }

        protocolThread.join()
        mainWalk.join()

        assertWithMessage("walk failed at frame %s of %s", failedAtFrame.get(), frames.get())
            .that(walkFailure.get()).isNull()
        assertThat(nullReadsForPresentKeys.get()).isEqualTo(0)
        // A walk counts as concurrent when the frame counter advanced during it. The bound sits an
        // order of magnitude below observed overlap, so it fails only when the window closes.
        assertWithMessage("only %s of %s walks overlapped a frame", concurrentWalks.get(), walks.get())
            .that(concurrentWalks.get()).isAtLeast(100)
    }

    /**
     * The map half on its own: a `get` during a concurrent rehash of a plain `HashMap` returns null
     * for a present key, and the channel list silently skips that root. The frames carry no
     * parent, so only the map is exercised; a tree would slow the writer and close the window.
     */
    @Test
    fun aChannelLookupNeverMissesAChannelTheProtocolThreadAlreadyStored() {
        val handler = ModelHandler(
            mockk(relaxed = true),
            NoopObserver(),
            SilentLogger,
            null,
            null,
        )
        val stored = AtomicInteger(-1)
        val done = AtomicBoolean(false)
        val bogusNulls = AtomicInteger(0)
        val lookups = AtomicInteger(0)

        val protocolThread = thread(name = "humla-protocol") {
            for (id in 0 until 5_000) {
                handler.messageChannelState(channel(id, name = "channel $id"))
                stored.set(id)
            }
            done.set(true)
        }
        val mainLookups = thread(name = "main-lookups") {
            val random = Random(3)
            while (!done.get()) {
                val known = stored.get()
                if (known < 0) continue
                if (handler.getChannel(random.nextInt(known + 1)) == null) bogusNulls.incrementAndGet()
                lookups.incrementAndGet()
            }
        }

        protocolThread.join()
        mainLookups.join()

        assertThat(bogusNulls.get()).isEqualTo(0)
        // Without this a reader that never got to run would pass.
        assertThat(lookups.get()).isGreaterThan(1_000)
        assertThat(stored.get()).isEqualTo(4_999)
    }

    /**
     * The channel list's tree walk, minus the view objects: the reads are what races. Every channel
     * counts as expanded.
     */
    private fun constructNodes(channel: IChannel) {
        if (channel.subchannelUserCount == 0) return
        for (user: IUser? in channel.users) {
            if (user == null) continue
            user.name
        }
        for (sub in channel.subchannels) constructNodes(sub)
    }

    private fun channel(id: Int, parent: Int? = null, name: String): Mumble.ChannelState =
        Mumble.ChannelState.newBuilder()
            .setChannelId(id)
            .setName(name)
            .also { if (parent != null) it.setParent(parent) }
            .build()

    private fun user(session: Int, name: String? = null, channelId: Int): Mumble.UserState =
        Mumble.UserState.newBuilder()
            .setSession(session)
            .also { if (name != null) it.setName(name) }
            .setChannelId(channelId)
            .build()
}
