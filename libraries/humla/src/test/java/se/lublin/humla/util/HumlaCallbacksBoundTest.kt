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
        val channelsAdded = mutableListOf<Int>()
        val channelStates = mutableListOf<String?>()
        val userStates = mutableListOf<Int>()
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
     * The bound is allowed to throw away tree-shape events and nothing else, so a queue of chat and
     * log events grows past it rather than losing a message.
     */
    @Test
    fun chatAndLogEventsAreNeverDropped() {
        val callbacks = HumlaCallbacks(Handler(Looper.getMainLooper()), 10)
        val recorder = Recorder()
        callbacks.registerObserver(recorder)

        thread { repeat(100) { callbacks.onLogInfo("m$it") } }.join()

        assertThat(callbacks.queuedEvents).isEqualTo(100)
        assertThat(callbacks.droppedEvents).isEqualTo(0)
        mainLooper.idle()
        assertThat(recorder.logs).isEqualTo((0 until 100).map { "m$it" })
    }

    /**
     * An undroppable event at the head must not stop the bound from working on what is behind it,
     * and must not be the thing that gets dropped either.
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
        mainLooper.idle()
        assertThat(recorder.logs).isEqualTo((0 until 5).map { "m$it" })
        assertThat(recorder.channelsAdded).isEqualTo((96..100).toList())
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
}
