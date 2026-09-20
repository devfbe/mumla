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
 * Run it with:
 * `./gradlew :app:testFossDebugUnitTest --tests '*AdapterRebuildBenchmarkTest' -Dbenchmark=1`
 * after removing the `@Ignore`, and read the numbers from the test report's system-out.
 *
 * Measured on this machine, 5 000 channels, 1 000 users, branching 4, best of seven:
 *
 * | | rebuild | recursive-count node visits | 1 024-event sync |
 * |---|---|---|---|
 * | before | 444.4 us | 33 179 | 307.2 ms |
 * | after  | 127.5 us | 0      | 0.2 ms   |
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

        // The whole sync: 1024 surviving model events, then the looper drained.
        repeat(3) { syncMs(adapter) }
        val best = (1..5).minOf { syncMs(adapter) }
        println("1024-event sync main-thread = %.1f ms".format(best))
    }

    private fun rebuildUs(adapter: ChannelListAdapter): Double {
        val runs = 200
        val t0 = System.nanoTime()
        repeat(runs) { adapter.updateChannels(); idle() }
        return (System.nanoTime() - t0) / 1000.0 / runs
    }

    private fun syncMs(adapter: ChannelListAdapter): Double {
        val t0 = System.nanoTime()
        repeat(1024) { adapter.updateChannels() }
        idle()
        return (System.nanoTime() - t0) / 1_000_000.0
    }

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
