package se.lublin.humla.util

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class HumlaCallbacksTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val callbacks = HumlaCallbacks()

    private class RecordingObserver : HumlaObserver() {
        val messages = mutableListOf<String>()
        val onMainThread = mutableListOf<Boolean>()
        override fun onLogInfo(message: String) {
            messages += message
            onMainThread += Looper.myLooper() == Looper.getMainLooper()
        }
    }

    @Test
    fun eventRaisedOnABackgroundThreadIsDeliveredOnTheMainThread() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)

        thread { callbacks.onLogInfo("hello") }.join()

        assertThat(observer.messages).isEmpty() // paused main looper: nothing ran yet
        mainLooper.idle()
        assertThat(observer.messages).containsExactly("hello")
        assertThat(observer.onMainThread).containsExactly(true)
    }

    @Test
    fun eventRaisedOnTheMainThreadWithAnEmptyQueueIsDeliveredInline() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)

        callbacks.onLogInfo("now")

        assertThat(observer.messages).containsExactly("now")
    }

    @Test
    fun orderIsPreservedAcrossSlices() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)

        thread { repeat(200) { callbacks.onLogInfo("m$it") } }.join()
        mainLooper.idle()

        assertThat(observer.messages).isEqualTo((0 until 200).map { "m$it" })
    }

    @Test
    fun aMainLooperTaskPostedBetweenEventsWaitsForAtMostOneSlice() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)
        thread { repeat(1_000) { callbacks.onLogInfo("m$it") } }.join()

        val probeRan = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { probeRan.set(true) }

        var tasksBeforeProbe = 0
        while (!probeRan.get()) {
            mainLooper.runOneTask()
            if (!probeRan.get()) tasksBeforeProbe++
        }
        assertThat(tasksBeforeProbe).isAtMost(1)
        assertThat(observer.messages.size).isAtMost(HumlaCallbacks.MAX_EVENTS_PER_SLICE)

        mainLooper.idle()
        assertThat(observer.messages).hasSize(1_000)
    }

    @Test
    fun unregisteredObserverReceivesNothing() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)
        callbacks.unregisterObserver(observer)

        thread { callbacks.onLogInfo("lost") }.join()
        mainLooper.idle()

        assertThat(observer.messages).isEmpty()
    }

    @Test
    fun eventsRaisedWhileADrainIsPendingAreQueuedBehindIt() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)
        thread { callbacks.onLogInfo("first") }.join()
        awaitUntil { !mainLooper.isIdle }

        callbacks.onLogInfo("second") // main thread, but queue is not empty: must not overtake "first"

        assertThat(observer.messages).isEmpty()
        mainLooper.idle()
        assertThat(observer.messages).containsExactly("first", "second").inOrder()
    }

    /**
     * Case 1: any thread may register or unregister while the delivery thread is fanning events
     * out. The registration set must tolerate that without a concurrent-modification failure.
     */
    @Test
    fun observersMayRegisterAndUnregisterWhileEventsAreBeingDelivered() {
        val stable = RecordingObserver()
        callbacks.registerObserver(stable)

        val churning = AtomicBoolean(true)
        val churnFailure = AtomicReference<Throwable?>(null)
        val churn = thread {
            try {
                val transient = RecordingObserver()
                while (churning.get()) {
                    callbacks.registerObserver(transient)
                    callbacks.unregisterObserver(transient)
                }
            } catch (t: Throwable) {
                churnFailure.set(t)
            }
        }

        try {
            val producer = thread { repeat(2_000) { callbacks.onLogInfo("m$it") } }
            producer.join(5_000)
            assertThat(producer.isAlive).isFalse()
            mainLooper.idle() // fans out while the churn thread mutates the observer set
        } finally {
            churning.set(false)
            churn.join(5_000)
        }

        assertThat(churn.isAlive).isFalse()
        assertThat(churnFailure.get()).isNull()
        assertThat(stable.messages).isEqualTo((0 until 2_000).map { "m$it" })
    }

    /**
     * Case 1 and case 4: an observer that unregisters on the delivery thread receives none of the
     * events still queued behind it, while every observer that stays registered receives all of
     * them, including the ones accepted before the unregistration.
     */
    @Test
    fun anObserverUnregisteredBetweenSlicesLosesThePendingEventsButOthersKeepThem() {
        val leaving = RecordingObserver()
        val staying = RecordingObserver()
        callbacks.registerObserver(leaving)
        callbacks.registerObserver(staying)

        thread { repeat(500) { callbacks.onLogInfo("m$it") } }.join()

        mainLooper.runOneTask() // exactly one slice
        val deliveredBeforeLeaving = leaving.messages.size
        assertThat(deliveredBeforeLeaving).isAtLeast(1)
        assertThat(deliveredBeforeLeaving).isAtMost(HumlaCallbacks.MAX_EVENTS_PER_SLICE)

        callbacks.unregisterObserver(leaving)
        mainLooper.idle()

        assertThat(leaving.messages).isEqualTo((0 until deliveredBeforeLeaving).map { "m$it" })
        assertThat(staying.messages).isEqualTo((0 until 500).map { "m$it" })
    }

    /**
     * Case 2: the budget is only checked between events, so one slow callback runs to completion
     * and the slice ends right after it. Slicing bounds the cadence, never the duration of a
     * single observer callback.
     */
    @Test
    fun aCallbackLongerThanTheSliceBudgetEndsTheSliceAfterItself() {
        val slow = object : HumlaObserver() {
            val delivered = AtomicInteger()
            override fun onLogInfo(message: String) {
                delivered.incrementAndGet()
                val until = System.nanoTime() + 2 * HumlaCallbacks.SLICE_BUDGET_NANOS
                @Suppress("ControlFlowWithEmptyBody")
                while (System.nanoTime() < until) {
                }
            }
        }
        callbacks.registerObserver(slow)

        thread { repeat(3) { callbacks.onLogInfo("m$it") } }.join()

        mainLooper.runOneTask()
        assertThat(slow.delivered.get()).isEqualTo(1)
        mainLooper.runOneTask()
        assertThat(slow.delivered.get()).isEqualTo(2)
        mainLooper.idle()
        assertThat(slow.delivered.get()).isEqualTo(3)
    }

    /**
     * Case 2 and case 4: a queue that keeps refilling while the drain runs still yields the
     * delivery thread after one slice, and loses nothing once the producer stops.
     */
    @Test
    fun aRefillingQueueStillYieldsAfterOneSliceAndLosesNothing() {
        val observer = RecordingObserver()
        callbacks.registerObserver(observer)

        val producing = AtomicBoolean(true)
        val produced = AtomicInteger()
        // Bounded so that a drain which never yields still terminates and fails on the
        // assertions below instead of hanging the suite.
        val producer = thread {
            while (producing.get() && produced.get() < 30_000) {
                callbacks.onLogInfo("m${produced.getAndIncrement()}")
            }
        }

        try {
            awaitUntil(description = "first event queued") { !mainLooper.isIdle }
            val probeRan = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).post { probeRan.set(true) }

            mainLooper.runOneTask() // the drain slice that was already queued ahead of the probe
            assertThat(observer.messages.size).isAtMost(HumlaCallbacks.MAX_EVENTS_PER_SLICE)
            assertThat(probeRan.get()).isFalse()

            mainLooper.runOneTask() // the probe, i.e. it waited for exactly one slice
            assertThat(probeRan.get()).isTrue()
        } finally {
            producing.set(false)
            producer.join(5_000)
        }
        assertThat(producer.isAlive).isFalse()

        mainLooper.idle()
        val total = produced.get()
        assertThat(observer.messages).hasSize(total)
        assertThat((0 until total).firstOrNull { observer.messages[it] != "m$it" }).isNull()
    }

    /**
     * Case 3 and case 4: an event raised from inside an observer callback goes to the back of the
     * queue instead of recursing, so the batch already accepted is delivered first.
     */
    @Test
    fun anEventRaisedFromWithinACallbackIsDeliveredAfterTheCurrentBatch() {
        val observer = object : HumlaObserver() {
            val messages = mutableListOf<String>()
            override fun onLogInfo(message: String) {
                messages += message
                if (message == "outer") callbacks.onLogInfo("inner")
            }
        }
        callbacks.registerObserver(observer)

        thread {
            callbacks.onLogInfo("outer")
            callbacks.onLogInfo("tail")
        }.join()
        mainLooper.idle()

        assertThat(observer.messages).containsExactly("outer", "tail", "inner").inOrder()
    }

    /**
     * Case 4: a callback that throws must not wedge the queue. The failure still surfaces on the
     * delivery thread, but the events behind it keep their turn.
     */
    @Test
    fun anObserverThatThrowsDoesNotWedgeTheQueue() {
        val recorder = RecordingObserver()
        val thrower = object : HumlaObserver() {
            override fun onLogInfo(message: String) {
                if (message == "boom") throw IllegalStateException("observer failed")
            }
        }
        callbacks.registerObserver(recorder)
        callbacks.registerObserver(thrower)

        thread {
            callbacks.onLogInfo("boom")
            callbacks.onLogInfo("after")
        }.join()

        assertThat(runCatching { mainLooper.idle() }.exceptionOrNull()).isNotNull()

        mainLooper.idle() // the drain re-armed itself in its finally block
        assertThat(recorder.messages).contains("after")
    }
}
