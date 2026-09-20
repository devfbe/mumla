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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
 * The acceptance test for the model race that task 4 opened by moving frame parsing off the main
 * looper (spec 4.1, "close the model race task 4 opened").
 *
 * This is the *measured* reproduction, not a model of it. It runs the two threads production runs:
 *
 * - The **protocol thread** feeds 5 000 frames through [ModelHandler] exactly as the
 *   `humla-protocol` looper does: `ChannelState` frames that build a tree, `UserState` frames that
 *   put users in it, and `UserState` frames that move them between channels. Every write to
 *   `mChannels`, `mUsers`, `Channel.mSubchannels` and `Channel.mUsers` happens there.
 * - The **main thread** walks the tree the way `ChannelListAdapter.updateChannels()` does, which is
 *   what the `onChannelAdded`/`onUserJoinedChannel` observers call: `session.getChannel(rootId)`
 *   (`ChannelListAdapter.java:320`) and then `constructNodes`, which reads
 *   `getSubchannelUserCount()` (`:438`), iterates `getUsers()` (`:444`) and recurses over
 *   `getSubchannels()` (`:450`).
 *
 * Two failures were measured on the unguarded model, and each has its own assertion below:
 *
 * 1. `ConcurrentModificationException` out of the walk, after 158 iterations at frame 835 of 5 000.
 *    `updateChannels()` catches only `IllegalStateException`, so on the device this is a crash on
 *    joining a large server with the channel list open. The throwing frame is worth naming exactly,
 *    because it decides which method needs the copy: measured five times out of five, it is
 *    `Channel.getSubchannelUserCount`'s own `for (sub in mSubchannels)`, reached four frames deep
 *    from the `subchannelUserCount` read at the top of [constructNodes] - *not* from
 *    [constructNodes]'s own loop over `channel.subchannels`, which is the line the walk appears to
 *    blame. The recursion iterates the live list; the adapter only ever iterates a snapshot it was
 *    handed.
 * 2. `HashMap.get` returning `null` for a key that is present, because a concurrent `put` is
 *    rehashing. `updateChannels()` skips a null channel, so the walk silently renders an empty
 *    channel list. Measured standalone at 20 rounds out of 20 before this test existed.
 *
 * Note what this test does *not* do: every assertion is about state accumulated while both threads
 * were running. Asserting that the tree is consistent after the feeder has joined would prove
 * nothing - the race window is closed by then and the assertion holds either way (spec 4.04,
 * "assert after the teardown returned").
 */
@RunWith(RobolectricTestRunner::class)
class ModelRaceTest {

    /** The frame budget of the measured reproduction: the failure landed at frame 835 of these. */
    private val channelFrames = 500
    private val userFrames = 1_500
    private val churnFrames = 3_000

    @Test
    fun aChannelListWalkSurvivesAServerSyncOnTheProtocolThread() {
        val handler = ModelHandler(
            ApplicationProvider.getApplicationContext(),
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
                    // ChannelListAdapter.updateChannels(): resolve the root channels by id, then
                    // walk. A null here is the measured HashMap-resize read, not a missing channel.
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
        // Guards the guard: a walk that ran only after the feeder had finished would satisfy both
        // assertions above without ever opening the window (spec 4.04). A "concurrent walk" is one
        // during which the frame counter advanced, so this counts overlap rather than wall-clock
        // luck.
        //
        // The bound used to be 1, which is enough to fail a walk that never ran and blind to
        // anything short of that: a change that pushed the overlap from hundreds to two would go
        // unnoticed. Measured over six runs, three here and three by review: 696, 714, 860 of
        // 1 177-1 296 walks, and 613 of 1 120. 100 sits an order of magnitude under the worst of
        // those and well over the handful of walks the unguarded model needed to throw (4, 20,
        // 112, 112, 275), so it stays insensitive to the scheduler and sensitive to the window
        // closing.
        assertWithMessage("only %s of %s walks overlapped a frame", concurrentWalks.get(), walks.get())
            .that(concurrentWalks.get()).isAtLeast(100)
    }

    /**
     * The second half of the measured failure, on its own so that it is not decided by how far the
     * tree walk above happens to get. `mChannels` was a plain `HashMap`, and a `get` that lands in
     * a concurrent rehash returns null for a key that is present - `ChannelListAdapter`
     * (`:320-322`) then skips that root channel without a word, so the list comes up empty or
     * short. Measured on a bare `HashMap` before any of this existed: 20 rounds out of 20 produced
     * at least one bogus null, up to 2 889 in a round.
     *
     * The frames here carry no parent, so nothing but the map is exercised: a tree would make the
     * writer slow enough to close the window it is supposed to open.
     */
    @Test
    fun aChannelLookupNeverMissesAChannelTheProtocolThreadAlreadyStored() {
        val handler = ModelHandler(
            ApplicationProvider.getApplicationContext(),
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
        // Without this a reader that never got to run would pass (spec 4.04).
        assertThat(lookups.get()).isGreaterThan(1_000)
        assertThat(stored.get()).isEqualTo(4_999)
    }

    /**
     * `ChannelListAdapter.constructNodes`, minus the view-model objects it accumulates: the reads
     * are what races, not the nodes. Every channel counts as expanded, which is what the adapter
     * does for a channel that has users and no stored preference.
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
