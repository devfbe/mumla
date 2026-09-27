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

package se.lublin.humla.testutil

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.lang.management.ManagementFactory

/**
 * Bytes the calling thread allocates per call, for the real-time paths where an allocation risks
 * a GC pause mid-frame. `getThreadAllocatedBytes` measures a window, so a claim is "below a
 * threshold", never "nothing"; [checkInstrument] first proves the counter counts, since a dead
 * counter would read as a perfect result.
 */
public object AllocationMeter {
    /**
     * Half the smallest JVM object (16 B): separates a per-call allocation from a one-off
     * allocation elsewhere on the thread. Observed floor readings are at most ~0.12 B per call, so
     * `isEqualTo(0.0)` would be flaky; an allocation every few calls can pass.
     */
    public const val HALF_AN_OBJECT: Double = 8.0

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    public fun allocatedBytes(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

    /** Fails unless allocating a [size]-sample array per call reads as at least that many bytes. */
    public fun checkInstrument(size: Int) {
        assertThat(threads.isThreadAllocatedMemorySupported).isTrue()
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()
        val sink = arrayOfNulls<Any>(1)
        assertWithMessage("the allocation counter is not counting; every result would be a false green")
            .that(bytesPerCall(HOT_CALLS, HOT_CALLS) { sink[0] = ShortArray(size) }).isAtLeast(size.toDouble())
    }

    public fun bytesPerCall(warmups: Int, iterations: Int, body: () -> Unit): Double {
        repeat(warmups) { body() }
        val before = allocatedBytes()
        repeat(iterations) { body() }
        return (allocatedBytes() - before).toDouble() / iterations
    }

    /**
     * The worse of a cold and a hot window, printed as [label]. Both are needed: once hot, C2's
     * escape analysis removes short-lived allocations such as a for-in `Iterator` (32 B cold, 0 B
     * hot) that ART would still make; the hot window still catches what it cannot remove.
     */
    public fun worstPerCall(label: String, hotCalls: Int = HOT_CALLS, body: () -> Unit): Double {
        val cold = bytesPerCall(COLD_WARMUPS, COLD_CALLS, body)
        val hot = bytesPerCall(hotCalls, hotCalls, body)
        println("allocation per call (cold / hot): $label ${"%.3f".format(cold)} / ${"%.3f".format(hot)} B")
        return maxOf(cold, hot)
    }

    private const val COLD_WARMUPS = 1_000
    private const val COLD_CALLS = 8_000
    private const val HOT_CALLS = 200_000
}
