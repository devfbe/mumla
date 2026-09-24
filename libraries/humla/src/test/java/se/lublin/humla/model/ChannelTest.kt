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
package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The overlap the race helpers must produce, so that a reader whose loop never started cannot
 * report no damage and pass. A floor on a machine-dependent distribution, kept well below the
 * lowest counts seen under load.
 */
private const val MIN_OVERLAPPING_READS = 50

/** How many observations the two tests with a fixed reader take while their writer runs. */
private const val OBSERVATIONS = 20_000

/** How many subchannels the recursive count races against; the double-count signal needs many. */
private const val SUBCHANNELS = 500

/** How many times [ChannelTest.race] calls its writer. */
private const val WRITES = 20_000

/**
 * The three lists a [Channel] owns are written on the protocol thread and read on the main thread:
 * does a read hand back a snapshot, and can a read during a write come back damaged ([Damage])?
 */
class ChannelTest {

    @Test
    fun userListIsASnapshotUnaffectedByLaterMoves() {
        val root = Channel(0, false)
        User(1, "a").setChannel(root)

        val seen = root.getUsers()
        User(2, "b").setChannel(root)

        assertThat(seen).hasSize(1)
        assertThat(root.getUsers()).hasSize(2)
    }

    @Test
    fun subchannelListIsASnapshotUnaffectedByLaterAdditions() {
        val root = Channel(0, false)
        root.addSubchannel(Channel(1, false).apply { setName("a") })

        val seen = root.getSubchannels()
        root.addSubchannel(Channel(2, false).apply { setName("b") })

        assertThat(seen).hasSize(1)
        assertThat(root.getSubchannels()).hasSize(2)
    }

    @Test
    fun linkListIsASnapshot() {
        val a = Channel(1, false).apply { setName("a") }
        val b = Channel(2, false).apply { setName("b") }
        a.addLink(b)

        val links = a.getLinks()
        a.setLinks(emptyList())

        assertThat(links).containsExactly(b)
        assertThat(a.getLinks()).isEmpty()
    }

    /**
     * Getters return unmodifiable lists, so callers get an exception rather than a write that
     * silently goes nowhere.
     */
    @Test
    fun aSnapshotIsStillNotWritable() {
        val root = Channel(0, false)
        User(1, "a").setChannel(root)
        root.addSubchannel(Channel(1, false).apply { setName("s") })
        root.addLink(Channel(2, false).apply { setName("l") })

        @Suppress("UNCHECKED_CAST")
        val writes = listOf<() -> Unit>(
            { (root.getUsers() as MutableList<User>).clear() },
            { (root.getSubchannels() as MutableList<Channel>).clear() },
            { (root.getLinks() as MutableList<Channel>).clear() },
        )
        writes.forEach { assertThrows(UnsupportedOperationException::class.java) { it() } }

        assertThat(root.getUsers()).hasSize(1)
        assertThat(root.getSubchannels()).hasSize(1)
        assertThat(root.getLinks()).hasSize(1)
    }

    @Test
    fun subchannelsStaySortedByPositionThenName() {
        val root = Channel(0, false)
        val b = Channel(1, false).apply { setName("b"); setPosition(1) }
        val a = Channel(2, false).apply { setName("a"); setPosition(1) }
        val z = Channel(3, false).apply { setName("z"); setPosition(0) }

        root.addSubchannel(b)
        root.addSubchannel(a)
        root.addSubchannel(z)

        assertThat(root.getSubchannels().map { it.getName() }).containsExactly("z", "a", "b").inOrder()
    }

    /**
     * `ModelHandler.createStubChannel` puts a nameless channel in the tree, and the server can
     * announce it as a parent before announcing its name; the sorted insert compares against it.
     */
    @Test
    fun namelessChannelsSortFirstInsteadOfThrowing() {
        val root = Channel(0, false)
        root.addSubchannel(Channel(1, false).apply { setName("a") })
        root.addSubchannel(Channel(2, false))
        root.addLink(Channel(3, false).apply { setName("b") })
        root.addLink(Channel(4, false))

        assertThat(root.getSubchannels().map { it.getId() }).containsExactly(2, 1).inOrder()
        assertThat(root.getLinks().map { it.getId() }).containsExactly(4, 3).inOrder()
    }

    @Test
    fun nullLinksAndSubchannelsAreIgnored() {
        val a = Channel(1, false).apply { setName("a") }

        a.addLink(null)
        a.removeLink(null)
        a.addSubchannel(null)
        a.removeSubchannel(null)

        assertThat(a.getLinks()).isEmpty()
        assertThat(a.getSubchannels()).isEmpty()
    }

    /**
     * The writer moves every user back and forth in whole passes, so each write is a real move and
     * users are really between channels (which catches a [User.setChannel] that joins before it
     * leaves). Only the move count is asserted; the observed range depends on the schedule.
     */
    @Test
    fun readingUsersWhileAnotherThreadMovesThemStaysUndamaged() {
        val root = Channel(0, false)
        val other = Channel(1, false)
        val users = (0 until 50).map { User(it, "user$it") }
        val moves = AtomicInteger()
        val fullest = AtomicInteger()
        val emptiest = AtomicInteger(Int.MAX_VALUE)

        val damage = race(
            write = { i ->
                val user = users[i % users.size]
                val target = if ((i / users.size) % 2 == 0) root else other
                if (user.getChannel() !== target) moves.incrementAndGet()
                user.setChannel(target)
            },
            read = {
                root.getUsers().also {
                    fullest.accumulateAndGet(it.size, ::maxOf)
                    emptiest.accumulateAndGet(it.size, ::minOf)
                }
            },
        )

        println(
            "MEASURE channel changes: ${moves.get()} of $WRITES," +
                " root.getUsers() between ${emptiest.get()} and ${fullest.get()}"
        )
        assertThat(damage.report()).isEmpty()
        assertThat(moves.get()).isAtLeast(WRITES / 2)
    }

    @Test
    fun readingSubchannelsWhileAnotherThreadAddsAndRemovesThemStaysUndamaged() {
        val root = Channel(0, false)
        val subchannels = (1..50).map { Channel(it, false).apply { setName("channel $it") } }

        val damage = race(
            write = { i ->
                // Whole passes of adds and of removes, so the list really oscillates between
                // empty and full.
                val sub = subchannels[i % subchannels.size]
                if ((i / subchannels.size) % 2 == 0) root.addSubchannel(sub) else root.removeSubchannel(sub)
            },
            read = { root.getSubchannels() },
        )

        assertThat(damage.report()).isEmpty()
    }

    @Test
    fun readingLinksWhileAnotherThreadRelinksStaysUndamaged() {
        val root = Channel(0, false).apply { setName("root") }
        val linked = (1..50).map { Channel(it, false).apply { setName("channel $it") } }

        val damage = race(
            write = { i ->
                val link = linked[i % linked.size]
                if ((i / linked.size) % 2 == 0) root.addLink(link) else root.removeLink(link)
            },
            read = { root.getLinks() },
        )

        assertThat(damage.report()).isEmpty()
    }

    /**
     * A server re-announces a channel's links as a whole set, and a set arriving in pieces would
     * make the italics of linked channels blink. [Channel.setLinks] replaces the list under the
     * lock, so this assertion is exact: a reader sees the old set or the new one.
     */
    @Test
    fun aRelinkIsNeverSeenHalfDone() {
        val root = Channel(0, false).apply { setName("root") }
        val linked = (1..50).map { Channel(it, false).apply { setName("channel $it") } }
        root.setLinks(linked)

        val partials = AtomicInteger()
        val relinks = AtomicInteger()
        val done = AtomicBoolean(false)
        // The reader takes a fixed number of observations and the writer runs until it has them.
        // The other way round, a relink (50 sorted inserts under the monitor) starves the reader.
        val writer = thread(name = "relinker") {
            while (!done.get()) {
                root.setLinks(linked)
                relinks.incrementAndGet()
                // Monitors are not fair: without a pause the writer re-takes the lock at once
                // and the reader waits seconds for each observation.
                Thread.yield()
            }
        }
        try {
            // Started, not merely spawned: otherwise the reader could finish before the writer
            // is ever scheduled and fail the floor below.
            awaitUntil(description = "the relinker's first pass") { relinks.get() > 0 }
            repeat(OBSERVATIONS) {
                if (root.getLinks().size != linked.size) partials.incrementAndGet()
            }
        } finally {
            done.set(true)
            writer.join()
        }

        assertThat(partials.get()).isEqualTo(0)
        // Every observation was taken between the first relink and the last; check there were
        // enough relinks to overlap.
        assertThat(relinks.get()).isAtLeast(MIN_OVERLAPPING_READS)
    }

    /**
     * `getSubchannelUserCount` reads both lists and then recurses, so it would hold a lock while
     * calling into another [Channel] if it were simply `@Synchronized`. Two signals:
     * - an **exception**: an unlocked copy of `mSubchannels` can include a slot a removal already
     *   nulled, and the recursion dereferences it;
     * - a **double count**: a copy taken mid-shift of an insert holds one subchannel twice. This
     *   only fires with many subchannels, and not on every run, so both signals are reported
     *   together.
     *
     * Every user has one home subchannel and only ever joins or leaves that one, and
     * [User.setChannel] leaves before it joins, so the sum can exceed the number of users only
     * through a duplicated subchannel. Users wandering between subchannels would break the ceiling
     * without a race, by design (see [Channel]: a snapshot of one list, not of the tree).
     *
     * The reader's count is fixed, as in [aRelinkIsNeverSeenHalfDone].
     */
    @Test
    fun countingUsersRecursivelyWhileTheTreeChangesNeitherThrowsNorDoubleCounts() {
        val root = Channel(0, false).apply { setName("root") }
        val subchannels = (1..SUBCHANNELS).map { Channel(it, false).apply { setName("channel $it") } }
        val users = (0 until 50).map { User(it, "user$it") }
        // The tree starts empty on purpose: the writer's first pass attaches the subchannels, so
        // each is in the list exactly once or not at all.
        val overcounts = AtomicInteger()
        val throws = AtomicInteger()
        val firstThrow = AtomicReference<Throwable?>()
        val writes = AtomicInteger()
        val done = AtomicBoolean(false)

        val writer = thread(name = "tree-writer") {
            var i = 0
            while (!done.get()) {
                // Whole passes of adds and of removes; alternating per iteration would only grow
                // the list.
                val sub = subchannels[i % subchannels.size]
                if ((i / subchannels.size) % 2 == 0) root.addSubchannel(sub) else root.removeSubchannel(sub)
                val user = users[i % users.size]
                val home = subchannels[(i % users.size) % subchannels.size]
                user.setChannel(if ((i / users.size) % 2 == 0) home else null)
                i++
                writes.incrementAndGet()
            }
        }
        try {
            awaitUntil(description = "the tree writer's first pass") { writes.get() > 0 }
            // Catch per observation so one throw does not hide later double counts.
            repeat(OBSERVATIONS) {
                try {
                    if (root.getSubchannelUserCount() > users.size) overcounts.incrementAndGet()
                } catch (t: Throwable) {
                    firstThrow.compareAndSet(null, t)
                    throws.incrementAndGet()
                }
            }
        } finally {
            done.set(true)
            writer.join()
        }

        // Reported together, like [Damage.report]: the exception signal would otherwise shadow
        // the double count.
        val damage = buildList {
            firstThrow.get()?.let {
                add("${throws.get()} of $OBSERVATIONS observations threw, first $it")
            }
            if (overcounts.get() > 0) add("${overcounts.get()} observations counted a user twice")
            // Every observation was taken while the writer ran; check there were enough writes.
            if (writes.get() < MIN_OVERLAPPING_READS) add("only ${writes.get()} writes to overlap")
        }

        assertThat(damage).isEmpty()
    }

    /**
     * The three ways a snapshot of a list someone else is writing comes back wrong, and they fail
     * differently, so each is counted on its own:
     *
     * - an **exception** out of the iterator or the copy;
     * - a **hole**, a null element in a list that never held one. `ArrayList.toArray` reads the
     *   backing array and the size separately and copies the contents after both, so a concurrent
     *   removal nulls a slot the copy has already decided to include;
     * - a **double**, the same element twice. An insert at an index shifts the tail right with one
     *   `System.arraycopy`, and a copy taken mid-shift sees the moved element in both places.
     *
     * A hole or a double is quiet on the device: the channel list skips nulls, so a user silently
     * vanishes or appears twice.
     */
    private class Damage {
        val failure = AtomicReference<Throwable?>()
        val holes = AtomicInteger()
        val doubles = AtomicInteger()
        val reads = AtomicInteger()
        val overlaps = AtomicInteger()

        fun report(): List<String> = buildList {
            failure.get()?.let { add("threw $it") }
            if (holes.get() > 0) add("${holes.get()} snapshots with a null element")
            if (doubles.get() > 0) add("${doubles.get()} snapshots with a duplicate element")
            if (overlaps.get() < MIN_OVERLAPPING_READS) {
                add("only ${overlaps.get()} of ${reads.get()} observations overlapped a write")
            }
        }
    }

    /**
     * Runs [write] [WRITES] times on one thread while another repeatedly takes [read] and inspects
     * the result. An observation counts as overlapping only when the writer's counter advanced
     * during it, and [Damage.report] reports a shortfall as damage, so a reader that never ran
     * cannot pass.
     */
    private fun race(write: (Int) -> Unit, read: () -> List<Any?>): Damage {
        val damage = Damage()
        val done = AtomicBoolean(false)
        val reading = AtomicBoolean(false)
        val writes = AtomicInteger()
        val writer = thread(name = "writer") {
            try {
                // The reader may still be unscheduled when the writer finishes, which would leave
                // it no window at all.
                awaitUntil(description = "the reader's loop") { reading.get() }
                for (i in 0 until WRITES) {
                    write(i)
                    writes.incrementAndGet()
                }
            } finally {
                done.set(true)
            }
        }
        val reader = thread(name = "reader") {
            try {
                reading.set(true)
                while (!done.get()) {
                    val writesAtStart = writes.get()
                    val snapshot = read()
                    if (snapshot.any { it == null }) damage.holes.incrementAndGet()
                    else if (snapshot.toSet().size != snapshot.size) damage.doubles.incrementAndGet()
                    for (element in snapshot) element?.hashCode()
                    damage.reads.incrementAndGet()
                    if (writes.get() > writesAtStart) damage.overlaps.incrementAndGet()
                }
            } catch (t: Throwable) {
                damage.failure.set(t)
            }
        }
        writer.join()
        reader.join()
        return damage
    }
}
