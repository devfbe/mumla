/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.audio.inputmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Robolectric because [ToggleInputMode.waitForInput] logs, and `android.util.Log` is not mocked. */
@RunWith(RobolectricTestRunner::class)
class ToggleInputModeTest {
    @Test
    fun `transmits only while toggled on, ignoring audio and probability`() {
        val mode = ToggleInputMode()
        assertThat(mode.shouldTransmit(ShortArray(480) { 32767 }, 480, 1.0f)).isFalse()
        mode.setTalkingOn(true)
        assertThat(mode.shouldTransmit(ShortArray(480), 480, 0.0f)).isTrue()
        mode.toggleTalkingOn()
        assertThat(mode.isTalkingOn()).isFalse()
        assertThat(mode.shouldTransmit(ShortArray(480) { 32767 }, 480, 1.0f)).isFalse()
    }

    /**
     * Written against the thread's state rather than against a stopwatch, on purpose.
     *
     * The plan's form starts the thread, asserts that a latch does **not** count down within
     * 200 ms and then toggles. That passes for two different reasons -- the thread blocked, or the
     * thread had not been scheduled at all -- and only one of them is the property. It is the
     * vacuous pass spec §4.05 names, and it costs 200 ms of wall clock per run to get it.
     * `Condition.await()` with no timeout parks in [Thread.State.WAITING], so waiting *for* that
     * state distinguishes the two and finishes as soon as it is true.
     */
    @Test
    fun `waitForInput blocks while off and wakes when toggled on`() {
        val mode = ToggleInputMode()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val thread = Thread {
            entered.countDown()
            mode.waitForInput()
            returned.countDown()
        }
        thread.start()

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() - deadline < 0) Thread.yield()
        assertThat(thread.state).isEqualTo(Thread.State.WAITING)
        assertThat(returned.count).isEqualTo(1L)

        mode.setTalkingOn(true)
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /**
     * **The `catch (InterruptedException)` around `await()` is not a log line, it is a control-flow
     * arm**, and pinning it is what this test is for. Its effect is to swallow the exception so
     * that [ToggleInputMode.waitForInput] **returns**; the `Log.w` inside it is incidental. Deleting
     * the `try`/`catch` and leaving a bare `await()` used to survive the whole suite.
     *
     * What it costs, read off the chain rather than guessed: `AudioInput.mRecordThread` runs
     * `AudioInput.run()`, which calls `onAudioInputReceived` (`:207`), which reaches
     * `AudioHandler:488` and `waitForInput()` -- so `await()` parks the **recording** thread.
     * `AudioInput.stopRecording()` (`:144-152`) clears `mRecording`, then `interrupt()`s that
     * thread, then `join()`s it. Without the catch the `InterruptedException` leaves `waitForInput`,
     * leaves `onAudioInputReceived`, leaves the `while (mRecording)` loop, and the thread dies
     * **before `mAudioRecord.stop()` at `:213`**. The user-visible result: shutting down in push to
     * talk with the button not held leaves the `AudioRecord` running and the microphone indicator
     * lit. `AudioInput.java:203`'s comment ("we want to always cleanly shutdown") names exactly the
     * property this arm holds.
     *
     * Written against [Thread.State.WAITING] for the same reason the test above is: a latch that
     * has not counted down does not distinguish "blocked" from "never scheduled".
     */
    @Test
    fun `waitForInput returns when the waiting thread is interrupted`() {
        val mode = ToggleInputMode()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val thread = Thread {
            entered.countDown()
            mode.waitForInput()
            returned.countDown()
        }
        thread.start()

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() - deadline < 0) Thread.yield()
        assertThat(thread.state).isEqualTo(Thread.State.WAITING)

        // Transmission stays off: the only thing that releases this thread is the interrupt.
        thread.interrupt()
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(mode.isTalkingOn()).isFalse()
    }

    /** The other half of the contract: while transmission is on, it must not block at all. */
    @Test
    fun `waitForInput returns immediately while toggled on`() {
        val mode = ToggleInputMode()
        mode.setTalkingOn(true)
        val returned = CountDownLatch(1)
        Thread { mode.waitForInput(); returned.countDown() }.start()
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /**
     * The flag is written by whichever thread owns the button and read unsynchronised by the
     * capture thread in [ToggleInputMode.shouldTransmit]. A missed publication is a JIT outcome,
     * not a value this process can produce on demand, so what is pinned is the **declaration**:
     * this is the one test that turns red if `@Volatile` is deleted. It is not a proof that the
     * write is seen; it is a proof that nobody removed the only thing that makes it so.
     */
    @Test
    fun `the talking flag is published`() {
        val field = ToggleInputMode::class.java.getDeclaredField("inputOn")
        assertThat(Modifier.isVolatile(field.modifiers)).isTrue()
    }

    @Test
    fun `continuous mode always transmits`() {
        assertThat(ContinuousInputMode().shouldTransmit(ShortArray(480), 480, null)).isTrue()
        assertThat(ContinuousInputMode().shouldTransmit(ShortArray(0), 0, 0.0f)).isTrue()
    }

    @Test
    fun `continuous mode never blocks`() {
        ContinuousInputMode().waitForInput()
    }
}
