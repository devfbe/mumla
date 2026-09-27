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

package se.lublin.mumla.channel

import android.view.View
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import java.lang.management.ManagementFactory

/**
 * Manual measurement harness for the channel list on a 5000-channel, 1000-user server; not a
 * gate, since wall-clock assertions are flaky. Remove the `@Ignore` to run it and read the numbers
 * from the test report's system-out:
 * `./gradlew :app:testFossDebugUnitTest --tests '*ChannelListBenchmarkTest'`
 */
@Ignore("measurement harness, not a gate -- see the KDoc")
@RunWith(RobolectricTestRunner::class)
class ChannelListBenchmarkTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocated() = threads.getThreadAllocatedBytes(Thread.currentThread().id)

    private val noTaps = object : ChannelListAdapter.Listener {
        override fun onChannelClick(row: ChannelRow.Channel) = Unit
        override fun onUserClick(row: ChannelRow.User) = Unit
        override fun onExpandClick(row: ChannelRow.Channel) = Unit
        override fun onJoinClick(row: ChannelRow.Channel) = Unit
        override fun onChannelMore(anchor: View, row: ChannelRow.Channel) = Unit
        override fun onUserMore(anchor: View, row: ChannelRow.User) = Unit
        override fun onStopListening(row: ChannelRow.Listener) = Unit
    }

    private fun model(moverChannel: Int): ServerState = ServerState.of(
        (0 until CHANNELS).map { ChannelState(it, "channel-$it", if (it == 0) null else (it - 1) / 4) },
        (1..USERS).map { UserState(it, "user-$it", (it * 5) % CHANNELS) } + UserState(MOVER, "mover", moverChannel),
        selfSession = 1,
    )

    @Test
    @Suppress("LongMethod") // One measurement after the other.
    fun measure() {
        val context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val config = AsyncDifferConfig.Builder(ChannelListAdapter.DIFF).setBackgroundThreadExecutor { it.run() }.build()
        val adapter = ChannelListAdapter(context, noTaps, config)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        fun layOut() {
            list.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            list.layout(0, 0, 1080, 1920)
        }
        val models = listOf(model(0), model(1))
        val rows = models.map { channelRows(it, listOf(0), emptyMap(), true) }
        adapter.submitList(rows[0])
        idleMainLooper()
        layOut()
        println("BENCH rows=${rows[0].size}")

        // A talk state change of a visible user (session 1 is in the root).
        repeat(WARMUP) {
            adapter.setTalkStates(if (it % 2 == 0) mapOf(1 to TalkState.TALKING) else emptyMap())
            layOut()
        }
        var a0 = allocated()
        var t0 = System.nanoTime()
        repeat(TALK_RUNS) {
            adapter.setTalkStates(if (it % 2 == 0) mapOf(1 to TalkState.TALKING) else emptyMap())
            layOut()
        }
        println(
            "BENCH talk-state change, main thread: %.1f us, %d bytes"
                .format((System.nanoTime() - t0) / 1e3 / TALK_RUNS, (allocated() - a0) / TALK_RUNS),
        )

        // A user move: the rows (background), the diff (background), then dispatch and layout (main).
        repeat(MOVE_WARMUP) { channelRows(models[it % 2], listOf(0), emptyMap(), true) }
        t0 = System.nanoTime()
        a0 = allocated()
        repeat(MOVE_RUNS) { channelRows(models[it % 2], listOf(0), emptyMap(), true) }
        println(
            "BENCH user move, rows (background): %.1f us, %d bytes"
                .format((System.nanoTime() - t0) / 1e3 / MOVE_RUNS, (allocated() - a0) / MOVE_RUNS),
        )
        val callback = object : DiffUtil.Callback() {
            var old = rows[0]
            var new = rows[1]
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        }
        repeat(MOVE_WARMUP) { DiffUtil.calculateDiff(callback) }
        t0 = System.nanoTime()
        a0 = allocated()
        repeat(MOVE_RUNS) { DiffUtil.calculateDiff(callback) }
        val diffNanos = (System.nanoTime() - t0) / MOVE_RUNS
        val diffBytes = (allocated() - a0) / MOVE_RUNS
        println("BENCH user move, diff (background): %.1f us, %d bytes".format(diffNanos / 1e3, diffBytes))

        repeat(MOVE_WARMUP) {
            adapter.submitList(rows[(it + 1) % 2])
            idleMainLooper()
            layOut()
        }
        t0 = System.nanoTime()
        a0 = allocated()
        repeat(MOVE_RUNS) {
            adapter.submitList(rows[(it + 1) % 2])
            idleMainLooper()
            layOut()
        }
        val totalNanos = (System.nanoTime() - t0) / MOVE_RUNS
        val totalBytes = (allocated() - a0) / MOVE_RUNS
        println(
            "BENCH user move, main thread (submit minus diff, plus layout): %.1f us, %d bytes"
                .format((totalNanos - diffNanos) / 1e3, totalBytes - diffBytes),
        )
    }

    private companion object {
        const val CHANNELS = 5_000
        const val USERS = 1_000
        const val MOVER = 99_999
        const val WARMUP = 2_000
        const val TALK_RUNS = 5_000
        const val MOVE_WARMUP = 50
        const val MOVE_RUNS = 200
    }
}
