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
 * The bound on [HumlaCallbacks]' observer queue.
 *
 * Robolectric's paused main looper stands in for a busy main thread: events raised on a background
 * thread pile up until `idle()` runs the drain.
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

    /** A 5 000-frame sync through the real [ModelHandler] while the main looper never gets a turn. */
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
     * Folding replaces the queued payload, so the one delivery carries the newest state. The two
     * channels are equal (same id) with different names, which is how the direction is told apart.
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
     * An event that leaves the queue must leave the fold index too. The index is keyed by subject,
     * so a stale entry would swallow every later refresh for that channel or user, and observers
     * that wait for one refresh (`ChannelDescriptionFragment`, `UserCommentFragment`) would hang.
     * The drain is pinned here; the ceiling's discard in
     * [aRefreshTheCeilingDiscardedDoesNotSwallowTheNextOneToo].
     */
    @Test
    fun aRefreshThatWasDeliveredDoesNotSwallowTheNextOneForItsSubject() {
        val callbacks = HumlaCallbacks()
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread { callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("first") }) }.join()
        mainLooper.idle()
        assertThat(recorder.channelStates).containsExactly("first")

        // Equal to the first one (same id), so it hits the same fold key.
        thread { callbacks.onChannelStateUpdated(Channel(1, false).apply { setName("second") }) }.join()
        mainLooper.idle()

        assertThat(recorder.channelStates).containsExactly("first", "second").inOrder()
    }

    /**
     * The same for an event the absolute ceiling discards: the refresh is the oldest (and only
     * droppable) entry, so the log flood behind it evicts exactly that one.
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
        // Precondition: the ceiling took the refresh and nothing else.
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
     * The first ceiling may only discard tree-shape events, so chat and log events grow past it
     * instead of being lost; only [HumlaCallbacks.absoluteCeiling] stops them.
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
     * An undroppable event at the head must neither block the bound for what is behind it nor be
     * dropped itself, including once the undroppable events alone exceed the bound.
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
            // Past the bound: 25 undroppable events, then ten more tree-shape ones.
            repeat(20) { callbacks.onLogInfo("n$it") }
            for (id in 101..110) callbacks.onChannelAdded(Channel(id, false))
        }.join()

        mainLooper.idle()
        assertThat(recorder.logs).isEqualTo((0 until 5).map { "m$it" } + (0 until 20).map { "n$it" })
        // The newest tree-shape event survives, and its rebuild shows all 110.
        assertThat(recorder.channelsAdded).isEqualTo(listOf(110))
    }

    /**
     * Undroppable events must not evict tree-shape ones: observers rebuild the list from those, so
     * with the bound full of chat and log events the channel list would stay empty.
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
     * The same at the production bound, in sync order: channel tree first, then users, whose
     * `onUserConnected`/`onUserJoinedChannel` are undroppable and alone fill the bound.
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
     * The absolute ceiling. The first bound trims only when the arriving event is droppable, so it
     * does not bound undroppable events, and a server with many users produces more of those than
     * any fixed bound (`messageUserState` raises `onUserConnected` and `onLogInfo` per user). The
     * invariant is `queuedEvents <= max(absoluteCeiling, lifecycle events enqueued)`.
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
        // Oldest first: the newest ceiling-worth survives.
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
     * Lifecycle events are exempt: dropping `onConnected` would leave the app on its connecting
     * screen for good. They are raised on the delivery thread itself, so none can arrive while that
     * thread is stuck, which is the only state in which this queue grows.
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
     * The trim must not scan the queue: `dispatch()` holds a lock shared with the audio thread.
     * Asserted as a ratio between a shallow and a 64x deeper queue, so it does not depend on the
     * machine; the absolute bound only catches pathological cases and comes second so it cannot
     * mask the ratio.
     */
    @Test
    fun findingTheOldestDroppableEventDoesNotScanTheQueue() {
        // Warm-up, so the JIT compilation is not charged to the shallow measurement.
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
     * Fills a queue to [HumlaCallbacks.absoluteCeiling] the way a sync does (tree first, then
     * undroppable user traffic) and returns the mean cost of one more droppable raise.
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
     * The queue policy of every observer event, compared against the interface, so a new event
     * that is not classified fails here. Each event is classified by what the queue does with it:
     * - two raises leave one delivery -> it folds;
     * - three raises against a first ceiling of one drop something -> the first ceiling may drop it;
     * - buried under an absolute ceiling's worth of exempt events, nothing is dropped -> exempt.
     */
    @Test
    fun everyObserverEventHasTheQueuePolicyThisFileClaimsForIt() {
        val methods = IHumlaObserver::class.java.declaredMethods.sortedBy { it.name }

        // Names are keys only while there are no overloads.
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
        // Every IHumlaObserver parameter is a reference type, and a folded event keys on its model
        // object, so two null raises are two raises for one subject.
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
            "onTLSCertificateChanged" to QueuePolicy.LIFECYCLE,
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
