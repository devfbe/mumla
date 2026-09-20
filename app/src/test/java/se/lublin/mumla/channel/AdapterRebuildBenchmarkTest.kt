package se.lublin.mumla.channel

import androidx.fragment.app.FragmentManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import se.lublin.mumla.db.MumlaDatabase
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession

/**
 * The measurement behind [ChannelListAdapter]'s coalescing, kept so the numbers do not have to be
 * rediscovered. Not a gate -- wall-clock assertions are flaky, and the invariants that a
 * regression would break are pinned deterministically in [ChannelListAdapterRebuildTest].
 *
 * Remove the `@Ignore` to run it, and read the numbers from the test report's system-out:
 * `./gradlew :app:testFossDebugUnitTest --tests '*AdapterRebuildBenchmarkTest'`
 *
 * **The wall-clock column is a sample from one machine, not a measurement of the change.** A
 * reviewer ran the same paired benchmark on other hardware and came out up to 58 % away on the
 * clock (2 174.4 ms against 1 376.9 ms for the 5 000-event synchronisation before the change)
 * while reproducing every deterministic count exactly. Read the ratios and the node visits; the
 * milliseconds are here so the order of magnitude does not have to be rediscovered, and spec 4.04
 * asks that the algorithm be asserted and the machine not be (which is why nothing here is a
 * gate).
 *
 * 5 000 channels, 1 000 users, branching factor 4, one paired run, rebuild best of seven and each
 * sync best of five:
 *
 * |                              | before    | after    |
 * |------------------------------|-----------|----------|
 * | one rebuild                  | 351.6 us  | 165.5 us |
 * | recursive-count node visits  | 33 179    | 0        |
 * | 1 024-event synchronisation  | 348.0 ms  | 0.2 ms   |
 * | 5 000-event synchronisation  | 1 376.9 ms| 0.4 ms   |
 *
 * Both event counts are measured because the spec's own figure was corrected: the observer queue
 * is bounded in droppable events but not in total, and `onUserConnected` -- one of the events the
 * channel list answers with a rebuild -- is undroppable and arrives once per user.
 */
@Ignore("measurement harness, not a gate -- see the KDoc")
@RunWith(RobolectricTestRunner::class)
class AdapterRebuildBenchmarkTest {

    @Test
    fun measure() {
        val (root, byId) = buildChannelTree(channelCount = 5000, branching = 4, userEvery = 5)
        val session = mockk<IHumlaSession>(relaxed = true)
        every { session.getChannel(any()) } answers { byId[firstArg<Int>()] }
        val service = mockk<IHumlaService>(relaxed = true)
        every { service.isConnected } returns true
        every { service.HumlaSession() } returns session

        val adapter = ChannelListAdapter(
            ApplicationProvider.getApplicationContext(),
            service,
            mockk<MumlaDatabase>(relaxed = true),
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
