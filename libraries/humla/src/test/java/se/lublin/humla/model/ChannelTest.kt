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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * What [ChannelTest.race] demands of the overlap it produces, so that a reader whose loop never
 * started cannot report no damage and pass (spec 4.04). Measured over four runs of the five tests
 * that use the helper: the lowest any of them produced was 256 overlapping observations of 256
 * taken, the highest 8 069 of 8 344, and the overlap was never below 95% of the reads. A bound of
 * 50 is an order of magnitude under the worst measurement and still tells a future change that
 * closes the window from one that does not.
 */
private const val MIN_OVERLAPPING_READS = 50

/**
 * The three lists a [Channel] owns are written on the protocol thread and read on the main thread,
 * so each one gets the same two questions: does a read hand back a snapshot, and can a read taken
 * while the protocol thread writes come back damaged? See [Damage] for what "damaged" covers and
 * why one signal is not enough.
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
     * The Java original returned `Collections.unmodifiableList`, so callers outside this library
     * are entitled to an exception when they write to what a getter handed them. Copying without
     * wrapping would turn that exception into a write that silently goes nowhere.
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
     * `ModelHandler.createStubChannel` puts a channel with no name in the tree, and the server can
     * announce that channel as someone's parent before it announces its name. Comparing against it
     * threw before the Kotlin conversion, which the sorted insert below would have hit.
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

    @Test
    fun readingUsersWhileAnotherThreadMovesThemStaysUndamaged() {
        val root = Channel(0, false)
        val other = Channel(1, false)
        val users = (0 until 50).map { User(it, "user$it") }

        val damage = race(
            write = { i -> users[i % users.size].setChannel(if (i % 2 == 0) root else other) },
            read = { root.getUsers() },
        )

        assertThat(damage.report()).isEmpty()
    }

    @Test
    fun readingSubchannelsWhileAnotherThreadAddsAndRemovesThemStaysUndamaged() {
        val root = Channel(0, false)
        val subchannels = (1..50).map { Channel(it, false).apply { setName("channel $it") } }

        val damage = race(
            write = { i ->
                // Whole passes of adds and of removes, so the list really oscillates between
                // empty and full. Alternating add/remove per iteration would keep hitting
                // different elements and only ever grow the list.
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
     * A server re-announces a channel's links as a whole set, and `ChannelListAdapter` italicises a
     * channel that is linked to ours (`:167`, `:172`), so a set that arrives in pieces makes the
     * italics blink. [Channel.setLinks] replaces the list under the lock, which makes this the one
     * assertion in this file that is exact rather than statistical: a reader sees the old set or
     * the new one, never a count in between.
     */
    @Test
    fun aRelinkIsNeverSeenHalfDone() {
        val root = Channel(0, false).apply { setName("root") }
        val linked = (1..50).map { Channel(it, false).apply { setName("channel $it") } }
        root.setLinks(linked)

        val partials = AtomicInteger()
        val overlaps = AtomicInteger()
        val relinks = AtomicInteger()
        val done = AtomicBoolean(false)
        val writer = thread(name = "relinker") {
            try {
                repeat(20_000) {
                    root.setLinks(linked)
                    relinks.incrementAndGet()
                }
            } finally {
                done.set(true)
            }
        }
        val reader = thread(name = "link-reader") {
            while (!done.get()) {
                val relinksAtStart = relinks.get()
                if (root.getLinks().size != linked.size) partials.incrementAndGet()
                if (relinks.get() > relinksAtStart) overlaps.incrementAndGet()
            }
        }
        writer.join()
        reader.join()

        assertThat(partials.get()).isEqualTo(0)
        // Same reason as [race]: a count of zero partial reads proves nothing about a reader that
        // never read while the writer was rebuilding, so count the reads that did overlap one.
        assertThat(overlaps.get()).isAtLeast(MIN_OVERLAPPING_READS)
    }

    /**
     * `getSubchannelUserCount` reads both lists and then recurses, so it is the one member that
     * would hold a lock while calling into another [Channel] if it were simply `@Synchronized`.
     * Its own race, because the reader here is the recursion rather than a returned list.
     *
     * It needs its own damage signals too, and that is worth spelling out: the method returns a
     * count, not a snapshot, so the [Damage] holes and doubles below have nothing to look at and
     * an exception is the only one of the three that can reach them. Measured: without the
     * `synchronized` block that exception arrives in every run - the copy of `mSubchannels`
     * includes a slot `fastRemove` has already nulled and the recursion dereferences it, on what
     * is the main thread in production.
     *
     * The count carries the second signal, the duplicate one that spec 4.05 says is the one most
     * likely to be missing. It only carries it because of how this fixture is built, so the
     * constraint is written down rather than assumed: **every user has one home subchannel and
     * only ever joins or leaves that one**, and [User.setChannel] leaves the old channel before
     * joining the new, so a user is in at most one list at any instant and can be counted at most
     * once per walk. The sum can then never exceed the number of users - except when the copy of
     * `mSubchannels` holds the same subchannel twice, which is what an unlocked reader sees while
     * an `add(i, e)` shifts the tail right. Users that wander between subchannels would break the
     * ceiling without any race at all: the walk reads one subchannel after another, so a user that
     * moves from an already-counted subchannel into one still to come is counted twice, and that
     * is by design (see [Channel]'s class doc: a snapshot of one list, not of the tree).
     */
    @Test
    fun countingUsersRecursivelyWhileTheTreeChangesNeverThrows() {
        val root = Channel(0, false).apply { setName("root") }
        val subchannels = (1..20).map { Channel(it, false).apply { setName("channel $it") } }
        val users = (0 until 50).map { User(it, "user$it") }
        val overcounts = AtomicInteger()
        // The tree starts empty on purpose: the writer's first pass is what attaches the
        // subchannels, so each of them is in the list exactly once or not at all. Attaching them
        // here as well would put every one of them in twice for the whole first pass, and the
        // count would pass the ceiling for a reason that has nothing to do with the lock.

        val damage = race(
            write = { i ->
                // Whole passes of adds and of removes, for the reason the two tests above give:
                // an add/remove alternation per iteration removes something that is not there on
                // every odd step, so the list only ever grows and nothing is ever torn.
                val sub = subchannels[i % subchannels.size]
                if ((i / subchannels.size) % 2 == 0) root.addSubchannel(sub) else root.removeSubchannel(sub)
                val user = users[i % users.size]
                val home = subchannels[(i % users.size) % subchannels.size]
                user.setChannel(if ((i / users.size) % 2 == 0) home else null)
            },
            read = {
                if (root.getSubchannelUserCount() > users.size) overcounts.incrementAndGet()
                emptyList<Any?>()
            },
        )

        assertThat(damage.report()).isEmpty()
        assertThat(overcounts.get()).isEqualTo(0)
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
     * The last two are the quiet ones. `ChannelListAdapter.constructNodes` (`:445`) skips nulls, so
     * on the device a hole is a user that silently vanishes and a double is one that appears twice.
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
     * Runs [write] 20 000 times on one thread while another repeatedly takes [read] and inspects
     * the result, and reports what the reader saw. Both threads are joined before anything is
     * asserted, but every observation is taken while the writer is running - an inspection after
     * the join would see a settled list and prove nothing (spec 4.04).
     *
     * That last sentence is a claim about the schedule, so the helper counts rather than claims:
     * an observation counts as overlapping when the writer's own counter advanced while the
     * observation was being taken, and [Damage.report] reports a shortfall as damage of its own.
     * A reader thread that never entered its loop - because it lost the start, or because a later
     * change made [write] finish first - otherwise hands back an empty report and a green test.
     */
    private fun race(write: (Int) -> Unit, read: () -> List<Any?>): Damage {
        val damage = Damage()
        val done = AtomicBoolean(false)
        val writes = AtomicInteger()
        val writer = thread(name = "writer") {
            try {
                for (i in 0 until 20_000) {
                    write(i)
                    writes.incrementAndGet()
                }
            } finally {
                done.set(true)
            }
        }
        val reader = thread(name = "reader") {
            try {
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
