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
package se.lublin.humla.util

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Channel
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.testutil.SilentLogger
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * The queue's bound (spec 4.1, "bound and coalesce the observer queue"). Task 2 left the queue
 * unbounded and named a task that never opens this file as the owner of the cap; task 4 made it
 * matter by letting the protocol thread outrun the main thread.
 *
 * Robolectric's paused main looper *is* the "main thread is busy" of the measurement: events raised
 * on a background thread pile up until `idle()` runs the drain, so what `queuedEvents` reports here
 * is what the device would be holding.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaCallbacksBoundTest {

    private val mainLooper = shadowOf(Looper.getMainLooper())

    private class Recorder : HumlaObserver() {
        val logs = mutableListOf<String?>()
        var connected = 0
        val channelsAdded = mutableListOf<Int>()
        val channelStates = mutableListOf<String?>()
        val userStates = mutableListOf<Int>()
        override fun onConnected() { connected++ }
        override fun onLogInfo(message: String?) { logs += message }
        override fun onChannelAdded(channel: IChannel?) { channelsAdded += channel!!.id }
        override fun onChannelStateUpdated(channel: IChannel?) { channelStates += channel?.name }
        override fun onUserStateUpdated(user: IUser?) { userStates += user!!.session }
    }

    /**
     * The measurement the spec asks for, run through the real producer: [ModelHandler] fed the same
     * 5 000-frame sync as `ModelRaceTest`, with [HumlaCallbacks] as its observer and the main looper
     * never getting a turn.
     */
    @Test
    fun aFiveThousandChannelSyncNoLongerParksAnEventPerChannel() {
        val unbounded = HumlaCallbacks(Handler(Looper.getMainLooper()), Int.MAX_VALUE)
        val bounded = HumlaCallbacks()

        val before = sync(unbounded)
        val after = sync(bounded)

        println(
            "MEASURE observer queue after a 5 000-channel sync: unbounded=$before bounded=$after" +
                " dropped=${bounded.droppedEvents}"
        )
        assertThat(before).isAtLeast(5_000)
        assertThat(after).isAtMost(HumlaCallbacks.MAX_QUEUED_EVENTS)
    }

    @Test
    fun repeatedStateRefreshesForOneSubjectAreDeliveredOnce() {
        val callbacks = HumlaCallbacks()
        val recorder = Recorder()
        callbacks.registerObserver(recorder)
        val user = User(7, "u")

        thread { repeat(1_000) { callbacks.onUserStateUpdated(user) } }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(1)
        mainLooper.idle()
        assertThat(recorder.userStates).containsExactly(7)
    }

    @Test
    fun stateRefreshesForDifferentSubjectsDoNotFoldIntoEachOther() {
        val callbacks = HumlaCallbacks()
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread {
            repeat(500) {
                callbacks.onUserStateUpdated(User(1, "a"))
                callbacks.onUserStateUpdated(User(2, "b"))
            }
        }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(2)
        mainLooper.idle()
        assertThat(recorder.userStates).containsExactly(1, 2).inOrder()
    }

    /**
     * Folding replaces the queued payload instead of discarding the newer event, so the one
     * delivery carries the newest state. The two channels below are equal (same id) and carry
     * different names, which is the only way to tell the two directions apart.
     */
    @Test
    fun aFoldedRefreshDeliversTheNewestPayload() {
        val callbacks = HumlaCallbacks()
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread {
            callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("old") })
            callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("new") })
        }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(1)
        mainLooper.idle()
        assertThat(recorder.channelStates).containsExactly("new")
    }

    /**
     * An event that has left the queue must leave the fold index with it, and the cost of getting
     * that wrong is not one lost refresh. [HumlaCallbacks] keys the index by subject, so a stale
     * entry is matched by *every* later refresh for that channel or user: each one folds into an
     * event nobody holds any more and is never queued at all. The subject would go without a name,
     * a comment, a mute symbol or a talk state for the rest of the connection, and
     * `ChannelDescriptionFragment`/`UserCommentFragment`, which unregister on the single refresh
     * they wait for, would hang for good.
     *
     * The two ways out of the queue are two call sites and are pinned separately: the drain takes
     * the head here, and [aRefreshTheCeilingDiscardedDoesNotSwallowTheNextOneToo] has the absolute
     * ceiling throw one away. Measured: dropping the fold branch of `forget()` left all 195 tests
     * in this module green before these two existed, and with them each site fails alone - taking
     * the index update out of the drain only fails this one ("expected [first, second] but was
     * [first]"), out of `discard()` only the other ("expected [after] but was []").
     */
    @Test
    fun aRefreshThatWasDeliveredDoesNotSwallowTheNextOneForItsSubject() {
        val callbacks = HumlaCallbacks()
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread { callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("first") }) }.join()
        mainLooper.idle()
        assertThat(recorder.channelStates).containsExactly("first")

        // Equal to the first one (same id), so it hits the same fold key - which is the point.
        thread { callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("second") }) }.join()
        mainLooper.idle()

        assertThat(recorder.channelStates).containsExactly("first", "second").inOrder()
    }

    /**
     * The same, for the event the absolute ceiling throws away rather than delivers. The refresh
     * below is the oldest thing in the queue and the only thing the ceiling is allowed to take, so
     * the log flood behind it evicts exactly that one.
     */
    @Test
    fun aRefreshTheCeilingDiscardedDoesNotSwallowTheNextOneToo() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread {
            callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("evicted") })
            repeat(callbacks.absoluteCeiling) { callbacks.onLogInfo("m$it") }
        }.join()
        mainLooper.idle()
        // The setup, and not the point: the ceiling took the refresh and nothing else.
        assertThat(callbacks.droppedEvents).isEqualTo(1)
        assertThat(recorder.channelStates).isEmpty()

        thread { callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("after") }) }.join()
        mainLooper.idle()

        assertThat(recorder.channelStates).containsExactly("after")
    }

    @Test
    fun theBoundDropsTheOldestTreeShapeEventAndKeepsTheNewest() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread { for (id in 1..100) callbacks.onChannelAdded(Channel(id, false)) }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(10)
        assertThat(callbacks.droppedEvents).isEqualTo(90)
        mainLooper.idle()
        assertThat(recorder.channelsAdded).isEqualTo((91..100).toList())
    }

    /**
     * The first ceiling is allowed to throw away tree-shape events and nothing else, so a queue of
     * chat and log events grows past it rather than losing a message. Five times past it here, and
     * still short of [HumlaCallbacks.absoluteCeiling], which is the only thing that stops it -
     * see [undroppableEventsCannotPushTheQueuePastTheAbsoluteCeiling] for the other side.
     */
    @Test
    fun chatAndLogEventsAreNeverDroppedByTheFirstCeiling() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 20)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread { repeat(100) { callbacks.onLogInfo("m$it") } }.join()

        assertThat(callbacks.absoluteCeiling).isAtLeast(100) // this one is the first ceiling's job
        assertThat(callbacks.queuedEvents).isEqualTo(100)
        assertThat(callbacks.droppedEvents).isEqualTo(0)
        mainLooper.idle()
        assertThat(recorder.logs).isEqualTo((0 until 100).map { "m$it" })
    }

    /**
     * An undroppable event at the head must not stop the bound from working on what is behind it,
     * and must not be the thing that gets dropped either.
     *
     * The second phase is the one that matters: the first stops at five undroppable events against
     * a bound of ten, which is still below it. Once the undroppable events alone are over the
     * bound there is no room left to take from, and the question becomes whether the tree-shape
     * events that arrive afterwards are delivered at all or thrown away as they are accepted.
     */
    @Test
    fun undroppableEventsAtTheHeadAreKeptAndSkippedOver() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread {
            repeat(5) { callbacks.onLogInfo("m$it") }
            for (id in 1..100) callbacks.onChannelAdded(Channel(id, false))
        }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(10)

        thread {
            // Past the bound: 25 events nothing may drop, and then ten more tree-shape ones.
            repeat(20) { callbacks.onLogInfo("n$it") }
            for (id in 101..110) callbacks.onChannelAdded(Channel(id, false))
        }.join()

        mainLooper.idle()
        assertThat(recorder.logs).isEqualTo((0 until 5).map { "m$it" } + (0 until 20).map { "n$it" })
        // The five from the first phase lose their place to the ten that arrive behind them, and
        // the newest survives - which is the one whose rebuild shows all 110.
        assertThat(recorder.channelsAdded).isEqualTo(listOf(110))
    }

    /**
     * The starvation the bound must not produce: undroppable events are not allowed to evict
     * tree-shape ones, because every observer of those answers by rebuilding the list from the
     * model. With the bound already full of chat and log events, an evicting bound delivers *no*
     * `onChannelAdded` at all and the channel list stays empty until something else happens to
     * trigger a rebuild.
     */
    @Test
    fun aQueueFullOfUndroppableEventsStillDeliversTheNewestTreeShapeEvent() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread {
            repeat(10) { callbacks.onLogInfo("m$it") }
            for (id in 1..100) callbacks.onChannelAdded(Channel(id, false))
        }.join()

        mainLooper.idle()
        assertThat(recorder.logs).isEqualTo((0 until 10).map { "m$it" })
        assertThat(recorder.channelsAdded).isEqualTo(listOf(100))
    }

    /**
     * The same starvation at the production bound and in the order a real synchronisation produces
     * it: Mumble sends the channel tree first and the users after it, and `onUserConnected` and
     * `onUserJoinedChannel` are undroppable. Behind a busy main thread the user half alone fills
     * the bound, so a bound that lets it evict tree-shape events drops all 5 000 `onChannelAdded`
     * - measured on that shape: `dropped=5000`, nothing delivered, no last channel.
     */
    @Test
    fun aUserSyncBehindAChannelSyncDoesNotSwallowEveryChannel() {
        val callbacks = HumlaCallbacks()
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread {
            for (id in 1..5_000) callbacks.onChannelAdded(Channel(id, false))
            repeat(HumlaCallbacks.MAX_QUEUED_EVENTS) { callbacks.onLogInfo("m$it") }
        }.join()

        mainLooper.idle()
        assertThat(recorder.logs).hasSize(HumlaCallbacks.MAX_QUEUED_EVENTS)
        assertThat(recorder.channelsAdded.lastOrNull()).isEqualTo(5_000)
    }

    /**
     * The second bound, and the one the first does not give. Trimming runs only when the arriving
     * event is itself droppable - which is what keeps a burst of chat from starving the channel
     * rebuild - so `queue.size <= maxQueuedEvents` holds only while the undroppable events stay
     * under it. Above that the first bound's ceiling is `#undroppable + 1`, and **nothing bounds
     * `#undroppable`**: twelve of the nineteen events are undroppable and two of them are bulk,
     * because `ModelHandler.messageUserState` raises `onUserConnected` *and* an `onLogInfo` per new
     * user. A 5 000-user server is therefore at least 10 000 events nothing may touch, and the
     * count is the server's to choose.
     */
    @Test
    fun undroppableEventsCannotPushTheQueuePastTheAbsoluteCeiling() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread { repeat(1_000) { callbacks.onLogInfo("m$it") } }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(callbacks.absoluteCeiling)
        assertThat(callbacks.droppedEvents).isEqualTo(1_000L - callbacks.absoluteCeiling)
        mainLooper.idle()
        // Oldest first: what survives is the newest ceiling-worth of them.
        assertThat(recorder.logs)
            .isEqualTo((1_000 - callbacks.absoluteCeiling until 1_000).map { "m$it" })
    }

    /** The same at the production bound, in the shape a real user sync produces it. */
    @Test
    fun aFiveThousandUserSyncIsBoundedInTotalAndNotOnlyInDroppableEvents() {
        val callbacks = HumlaCallbacks()

        thread {
            repeat(5_000) {
                callbacks.onUserConnected(User(it, "u$it"))
                callbacks.onLogInfo("u$it connected")
            }
        }.join()

        println("MEASURE observer queue after a 5 000-user sync: ${callbacks.queuedEvents}")
        assertThat(callbacks.queuedEvents).isAtMost(callbacks.absoluteCeiling)
    }

    /**
     * The one exemption, and why it is the only one: an absolute ceiling that may throw away
     * `onConnected` would leave `MumlaActivity` (`:172`) on its connecting screen for good, and
     * nothing in the model can be re-read to recover it. The four connection-lifecycle events are
     * also the only undroppable ones whose count is the *connection's* to choose rather than the
     * server's, so exempting them leaves the ceiling bounded by something no server can inflate.
     */
    @Test
    fun aConnectionLifecycleEventSurvivesTheCeilingThatSwallowsTheBulk() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)
        val flood = callbacks.absoluteCeiling * 2

        thread {
            callbacks.onConnected()
            repeat(flood) { callbacks.onLogInfo("m$it") }
        }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(callbacks.absoluteCeiling)
        mainLooper.idle()
        assertThat(recorder.connected).isEqualTo(1)
        // The lifecycle event holds a place, so one fewer log line does.
        assertThat(recorder.logs)
            .isEqualTo((flood - callbacks.absoluteCeiling + 1 until flood).map { "m$it" })
    }

    /**
     * What the trim costs on the raise path, which is the only new cost either ceiling puts on the
     * hot path - and that path is `dispatch()` holding a lock the protocol thread shares with the
     * audio thread (`AudioOutput` -> `onUserTalkStateUpdated`).
     *
     * Finding the oldest droppable event by scanning the queue is O(queue), and the queue is
     * deepest exactly when the trim runs. The second half below is **64 times** deeper than the
     * first and must not cost eight times as much: a ratio is a statement about the algorithm,
     * where a wall-clock budget would be a statement about the machine (spec 4.04). Over four runs
     * here the index measures 948 to 1 297 ns at 128 queued and 645 to 1 492 ns at 8 192 - ratio
     * **0.50x to 1.57x for 64x the depth** - while the scan it replaced measures 4 548 to 9 742 ns
     * against 91 227 to 112 942 ns, ratio **11.4x to 24.8x**. The threshold of 8 sits with a 5.1x
     * margin under the worst real reading and a 1.4x margin over the best mutated one.
     *
     * Two things about the shape of the measurement, both of which cost the assertion its teeth
     * before they were fixed, and both measured rather than reasoned:
     * - **The shallow half must not pay for the JIT.** Written without the warm-up call below, the
     *   first measurement carried the compilation of the whole raise path: 16 430 to 20 585 ns for
     *   a 1 024-element scan, against 7 to 8 ns per element once warm. That inflates the number the
     *   ratio divides by, and it inflates it only in the mutated build, where the scan is what gets
     *   compiled.
     * - **The spread has to beat the fixed cost per raise.** At the 8x spread this test first used
     *   (1 024 against 8 192) the scan mutation produced ratios of 2.77x, 3.99x, 3.54x and 2.95x -
     *   **under a 4x threshold in four runs out of four**, so the ratio assertion did not fire at
     *   all and only the absolute bound did. "No linear scan can pass a ratio" is a property of the
     *   spread and the warm-up, not of ratios.
     *
     * The absolute bound is kept as a floor under a change that makes the whole thing pathological,
     * and it comes **second** so that it cannot shadow the ratio: with it first, the same mutation
     * reported "expected to be less than: 20000 but was: 55975" here - 279 528 on the machine the
     * review ran on - and the ratio line below it never ran.
     */
    @Test
    fun findingTheOldestDroppableEventDoesNotScanTheQueue() {
        // Discarded: it is here so that the first *measured* raise is not the one that pays for
        // compiling the raise path. See the doc above for what that cost the assertion.
        nanosPerRaiseAgainstAFullQueue(16)
        val shallow = nanosPerRaiseAgainstAFullQueue(16)
        val deep = nanosPerRaiseAgainstAFullQueue(1_024)

        println(
            "MEASURE ns per droppable raise: ${16 * HumlaCallbacks.CEILING_FACTOR} queued=$shallow," +
                " ${1_024 * HumlaCallbacks.CEILING_FACTOR} queued=$deep"
        )
        assertThat(deep).isLessThan(shallow * 8)
        assertThat(deep).isLessThan(20_000L)
    }

    /**
     * Fills a queue to [HumlaCallbacks.absoluteCeiling] the way a sync does - the tree first, then
     * the undroppable user traffic behind it - and returns the mean cost of one more droppable
     * raise against it.
     */
    private fun nanosPerRaiseAgainstAFullQueue(bound: Int): Long {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), bound)
        thread {
            for (id in 1..bound) callbacks.onChannelAdded(Channel(id, false))
            repeat(callbacks.absoluteCeiling * 2) { callbacks.onLogInfo("m$it") }
        }.join()
        assertThat(callbacks.queuedEvents).isEqualTo(callbacks.absoluteCeiling)

        var total = 0L
        thread {
            repeat(RAISES) {
                val start = System.nanoTime()
                callbacks.onChannelAdded(Channel(1_000_000 + it, false))
                total += System.nanoTime() - start
            }
        }.join()
        return total / RAISES
    }

    /**
     * What each of the nineteen events may have done to it, pinned as a set rather than as
     * nineteen cases (spec 4.04, handle 2).
     *
     * The mapping is decided one raise site at a time at the bottom of [HumlaCallbacks], which is a
     * hand-written list. That was harmless while `Plain` meant no more than "is never dropped".
     * Since [HumlaCallbacks.absoluteCeiling] it also means "counts against the only ceiling that
     * holds", so a twentieth event somebody adds and forgets to classify moves that ceiling
     * silently. Here it fails instead, because the expectation below is compared against the
     * interface and not only against the behaviour.
     *
     * The policy is private, so each event is *classified by what the queue does with it*, which is
     * the property anyone cares about anyway:
     * - raise it twice and one delivery is left -> it folds;
     * - raise it three times against a first ceiling of one and something was dropped -> the first
     *   ceiling may drop it;
     * - put it under an absolute ceiling's worth of events that ceiling may not drop, and see
     *   whether it or nothing is thrown away -> it is exempt, or it is not.
     *
     * Both halves have been seen red rather than assumed to work: giving `onUserConnected`
     * `Policy.Droppable` fails with "for key onUserConnected expected PLAIN but got DROPPABLE", and
     * deleting `onLogError` from the expectation fails with "missing (1): onLogError".
     */
    @Test
    fun everyObserverEventHasTheQueuePolicyThisFileClaimsForIt() {
        val methods = IHumlaObserver::class.java.declaredMethods.sortedBy { it.name }

        // No overloads today, so the name is a key; if that ever changes this says so first.
        assertThat(methods.map { it.name }).containsNoDuplicates()
        assertThat(EXPECTED_POLICIES.keys).containsExactlyElementsIn(methods.map { it.name })
        assertThat(methods.associate { it.name to classify(it) })
            .containsExactlyEntriesIn(EXPECTED_POLICIES)
    }

    private enum class QueuePolicy { FOLD, DROPPABLE, LIFECYCLE, PLAIN }

    private fun classify(event: Method): QueuePolicy = when {
        raiseTwice(event) == 1 -> QueuePolicy.FOLD
        droppedWhenRaisedThreeTimesAgainstACeilingOfOne(event) > 0 -> QueuePolicy.DROPPABLE
        droppedWhenBuriedUnderExemptEvents(event) == 0L -> QueuePolicy.LIFECYCLE
        else -> QueuePolicy.PLAIN
    }

    /** How many deliveries two raises of [event] leave queued. One means it folded into itself. */
    private fun raiseTwice(event: Method): Int = measure(bound = 4) { callbacks ->
        repeat(2) { raise(callbacks, event) }
    }.queued

    private fun droppedWhenRaisedThreeTimesAgainstACeilingOfOne(event: Method): Long =
        measure(bound = 1) { callbacks -> repeat(3) { raise(callbacks, event) } }.dropped

    /**
     * Raises [event] and then fills the queue past [HumlaCallbacks.absoluteCeiling] with events the
     * ceiling is not allowed to touch, so [event] is the only thing left for it to take. Zero drops
     * means the ceiling found nothing it was allowed to drop, i.e. [event] is exempt too.
     */
    private fun droppedWhenBuriedUnderExemptEvents(event: Method): Long =
        measure(bound = 1) { callbacks ->
            raise(callbacks, event)
            repeat(callbacks.absoluteCeiling) { callbacks.onConnecting() }
        }.dropped

    private fun raise(callbacks: HumlaCallbacks, event: Method) {
        // Null arguments throughout: every parameter in IHumlaObserver is a reference type, and a
        // folded event keys on the model object it carries, so two nulls are two raises for one
        // subject - which is exactly what the fold is being asked about.
        event.invoke(callbacks, *arrayOfNulls<Any?>(event.parameterCount))
    }

    private class Reading(val queued: Int, val dropped: Long)

    /** Runs [raises] off the delivery thread, so nothing is delivered inline, and reads the counters. */
    private fun measure(bound: Int, raises: (HumlaCallbacks) -> Unit): Reading {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), bound)
        thread { raises(callbacks) }.join()
        val reading = Reading(callbacks.queuedEvents, callbacks.droppedEvents)
        mainLooper.idle()
        return reading
    }

    /** Feeds the 5 000-frame sync from `ModelRaceTest` and reports what is left in the queue. */
    private fun sync(callbacks: HumlaCallbacks): Int {
        val handler = ModelHandler(
            ApplicationProvider.getApplicationContext(),
            callbacks,
            SilentLogger,
            null,
            null,
        )
        thread(name = "humla-protocol") {
            handler.messageChannelState(channelState(0, name = "Root"))
            for (id in 1..5_000) {
                handler.messageChannelState(channelState(id, parent = id / 4, name = "channel $id"))
            }
        }.join()
        return callbacks.queuedEvents
    }

    private fun channelState(id: Int, parent: Int? = null, name: String): Mumble.ChannelState =
        Mumble.ChannelState.newBuilder()
            .setChannelId(id)
            .setName(name)
            .also { if (parent != null) it.setParent(parent) }
            .build()

    private companion object {
        /** Enough raises that one slow one cannot decide the mean. */
        const val RAISES = 2_000

        /**
         * What [HumlaCallbacks] claims to do with each event, as its raise sites are written.
         * A name that is not in `IHumlaObserver`, or one of its methods that is not here, fails
         * [everyObserverEventHasTheQueuePolicyThisFileClaimsForIt].
         */
        val EXPECTED_POLICIES = mapOf(
            // The connection's own progress: exempt from both ceilings.
            "onConnected" to QueuePolicy.LIFECYCLE,
            "onConnecting" to QueuePolicy.LIFECYCLE,
            "onDisconnected" to QueuePolicy.LIFECYCLE,
            "onTLSHandshakeFailed" to QueuePolicy.LIFECYCLE,
            // Everything an observer answers by rebuilding the whole list.
            "onChannelAdded" to QueuePolicy.DROPPABLE,
            "onChannelRemoved" to QueuePolicy.DROPPABLE,
            "onUserRemoved" to QueuePolicy.DROPPABLE,
            // A refresh for one subject, which the next one for that subject replaces.
            "onChannelStateUpdated" to QueuePolicy.FOLD,
            "onChannelPermissionsUpdated" to QueuePolicy.FOLD,
            "onUserStateUpdated" to QueuePolicy.FOLD,
            "onUserTalkStateUpdated" to QueuePolicy.FOLD,
            // The rest: delivered as raised, and counting against the absolute ceiling.
            "onUserConnected" to QueuePolicy.PLAIN,
            "onUserJoinedChannel" to QueuePolicy.PLAIN,
            "onPermissionDenied" to QueuePolicy.PLAIN,
            "onMessageLogged" to QueuePolicy.PLAIN,
            "onVoiceTargetChanged" to QueuePolicy.PLAIN,
            "onLogInfo" to QueuePolicy.PLAIN,
            "onLogWarning" to QueuePolicy.PLAIN,
            "onLogError" to QueuePolicy.PLAIN,
        )
    }
}
