package se.lublin.mumla.channel

import androidx.fragment.app.FragmentManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.testing.stubConnected

/**
 * Manual measurement harness for [ChannelListAdapter]'s rebuild coalescing; not a gate, since
 * wall-clock assertions are flaky. The invariants are pinned in [ChannelListAdapterRebuildTest].
 *
 * Remove the `@Ignore` to run it and read the numbers from the test report's system-out:
 * `./gradlew :app:testFossDebugUnitTest --tests '*AdapterRebuildBenchmarkTest'`
 */
@Ignore("measurement harness, not a gate -- see the KDoc")
@RunWith(RobolectricTestRunner::class)
class AdapterRebuildBenchmarkTest {

    @Test
    fun measure() {
        val (root, byId) = buildChannelTree(channelCount = 5000, branching = 4, userEvery = 5)
        val session = mockk<IHumlaSession>(relaxed = true)
        every { session.getChannel(any()) } answers { byId[firstArg<Int>()] }
        val service = mockk<IHumlaService>(relaxed = true).stubConnected(session)

        val adapter = ChannelListAdapter(
            ApplicationProvider.getApplicationContext(),
            service,
            MumlaRepository(mockk<MumlaDatabase>(relaxed = true), Dispatchers.Unconfined),
            mockk<FragmentManager>(relaxed = true),
            false,
            true,
        )
        println("nodes=${adapter.itemCount}")

        // warmup
        repeat(200) { adapter.updateChannels(); idle() }

        root.counters.reset()
        adapter.updateChannels()
        idle()
        println("per-rebuild subchannelUserCount node visits = ${root.counters.subchannelUserCountCalls}")
        println("per-rebuild getUsers calls = ${root.counters.getUsersCalls}")
        println("per-rebuild getSubchannels calls = ${root.counters.getSubchannelsCalls}")

        val perRebuildUs = (1..7).minOf { rebuildUs(adapter) }
        println("per-rebuild = %.1f us".format(perRebuildUs))

        // The whole sync: every surviving model event, then the looper drained.
        for (events in intArrayOf(1024, 5000)) {
            repeat(3) { syncMs(adapter, events) }
            val best = (1..5).minOf { syncMs(adapter, events) }
            println("%d-event sync main-thread = %.1f ms".format(events, best))
        }
    }

    private fun rebuildUs(adapter: ChannelListAdapter): Double {
        val runs = 200
        val t0 = System.nanoTime()
        repeat(runs) { adapter.updateChannels(); idle() }
        return (System.nanoTime() - t0) / 1000.0 / runs
    }

    private fun syncMs(adapter: ChannelListAdapter, events: Int): Double {
        val t0 = System.nanoTime()
        repeat(events) { adapter.updateChannels(); adapter.notifyDataSetChanged() }
        idle()
        return (System.nanoTime() - t0) / 1_000_000.0
    }

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
