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
import org.junit.Test
import se.lublin.humla.model.ServerState
import java.lang.management.ManagementFactory

/**
 * A large server stays cheap to reduce: every frame costs about what it changes. The bounds are
 * an order of magnitude above what a laptop takes, so they catch a quadratic step, not noise; the
 * numbers are printed for the record.
 */
class ServerReducerScaleTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())

    @Test
    fun aFiveThousandChannelSyncAndTheMovesAfterItStayCheap() {
        val frames = syncFrames(channels = 5_000, users = 1_000)
        repeat(WARMUP) { ReducerHarness().feed(*frames.toTypedArray()) }

        var bestNanos = Long.MAX_VALUE
        var bytes = 0L
        lateinit var synced: ServerState
        repeat(RUNS) {
            val harness = ReducerHarness()
            val before = allocated()
            val start = System.nanoTime()
            synced = harness.feed(*frames.toTypedArray())
            val nanos = System.nanoTime() - start
            if (nanos < bestNanos) {
                bestNanos = nanos
                bytes = allocated() - before
            }
        }
        assertThat(synced.channels).hasSize(5_000)
        assertThat(synced.subtreeUserCount(0)).isEqualTo(1_000)

        val moves = (0 until MOVES).map { userFrame(2) { channelId = it % 5_000 } }
        val harness = ReducerHarness(synced)
        harness.feed(*moves.toTypedArray())
        val before = allocated()
        val start = System.nanoTime()
        harness.feed(*moves.toTypedArray())
        val moveNanos = (System.nanoTime() - start) / MOVES
        val moveBytes = (allocated() - before) / MOVES

        var batchNanos = Long.MAX_VALUE
        var batchBytes = 0L
        repeat(RUNS) {
            val writer = ServerWriter(ServerState.empty())
            val before = allocated()
            val start = System.nanoTime()
            for (frame in frames) writer.onMessage(frame) {}
            writer.snapshot()
            val nanos = System.nanoTime() - start
            if (nanos < batchNanos) {
                batchNanos = nanos
                batchBytes = allocated() - before
            }
        }

        println("5000-channel sync, a snapshot per frame: %.2f ms, %d KB".format(bestNanos / 1e6, bytes / 1024))
        println("the same sync as one burst, one snapshot: %.2f ms, %d KB".format(batchNanos / 1e6, batchBytes / 1024))
        println("one user move after it: %.2f us, %d bytes allocated".format(moveNanos / 1e3, moveBytes))
        assertThat(bestNanos).isLessThan(SYNC_BOUND_NANOS)
        assertThat(batchNanos).isLessThan(SYNC_BOUND_NANOS)
        assertThat(moveNanos).isLessThan(MOVE_BOUND_NANOS)
    }

    private companion object {
        const val WARMUP = 5
        const val RUNS = 7
        const val MOVES = 2_000
        const val SYNC_BOUND_NANOS = 2_000_000_000L
        const val MOVE_BOUND_NANOS = 1_000_000L
    }
}
