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
        val reads = AtomicInteger()
        val done = AtomicBoolean(false)
        val writer = thread(name = "relinker") {
            try {
                repeat(20_000) { root.setLinks(linked) }
            } finally {
                done.set(true)
            }
        }
        val reader = thread(name = "link-reader") {
            while (!done.get()) {
                if (root.getLinks().size != linked.size) partials.incrementAndGet()
                reads.incrementAndGet()
            }
        }
        writer.join()
        reader.join()

        assertThat(partials.get()).isEqualTo(0)
        assertThat(reads.get()).isGreaterThan(0)
    }

    /**
     * `getSubchannelUserCount` reads both lists and then recurses, so it is the one member that
     * would hold a lock while calling into another [Channel] if it were simply `@Synchronized`.
     * Its own race, because the reader here is the recursion rather than a returned list.
     */
    @Test
    fun countingUsersRecursivelyWhileTheTreeChangesNeverThrows() {
        val root = Channel(0, false).apply { setName("root") }
        val subchannels = (1..20).map { Channel(it, false).apply { setName("channel $it") } }
        subchannels.forEach { root.addSubchannel(it) }
        val users = (0 until 50).map { User(it, "user$it") }

        val damage = race(
            write = { i ->
                val sub = subchannels[i % subchannels.size]
                if (i % 2 == 0) root.addSubchannel(sub) else root.removeSubchannel(sub)
                users[i % users.size].setChannel(sub)
            },
            read = { root.getSubchannelUserCount(); emptyList<Any?>() },
        )

        assertThat(damage.report()).isEmpty()
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

        fun report(): List<String> = buildList {
            failure.get()?.let { add("threw $it") }
            if (holes.get() > 0) add("${holes.get()} snapshots with a null element")
            if (doubles.get() > 0) add("${doubles.get()} snapshots with a duplicate element")
        }
    }

    /**
     * Runs [write] 20 000 times on one thread while another repeatedly takes [read] and inspects
     * the result, and reports what the reader saw. Both threads are joined before anything is
     * asserted, but every observation is taken while the writer is running - an inspection after
     * the join would see a settled list and prove nothing (spec 4.04).
     */
    private fun race(write: (Int) -> Unit, read: () -> List<Any?>): Damage {
        val damage = Damage()
        val done = AtomicBoolean(false)
        val writer = thread(name = "writer") {
            try {
                for (i in 0 until 20_000) write(i)
            } finally {
                done.set(true)
            }
        }
        val reader = thread(name = "reader") {
            try {
                while (!done.get()) {
                    val snapshot = read()
                    if (snapshot.any { it == null }) damage.holes.incrementAndGet()
                    else if (snapshot.toSet().size != snapshot.size) damage.doubles.incrementAndGet()
                    for (element in snapshot) element?.hashCode()
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
