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
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Robolectric because [ToggleInputMode.waitForInput] logs, and `android.util.Log` is not mocked. */
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
     * Waits for [Thread.State.WAITING] rather than asserting a latch stays down for a while: the
     * latter also passes when the thread was simply never scheduled.
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
     * The `catch (InterruptedException)` makes [ToggleInputMode.waitForInput] return. Without it the
     * interrupt from `AudioInput.stopRecording()` would kill the capture thread before it stops the
     * `AudioRecord`, leaving the microphone running in push-to-talk.
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

        // Transmission stays off: only the interrupt releases this thread.
        thread.interrupt()
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(mode.isTalkingOn()).isFalse()
    }

    /** While transmission is on, it must not block at all. */
    @Test
    fun `waitForInput returns immediately while toggled on`() {
        val mode = ToggleInputMode()
        mode.setTalkingOn(true)
        val returned = CountDownLatch(1)
        Thread { mode.waitForInput(); returned.countDown() }.start()
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /** `signalAll`, not `signal`: nothing restricts the mode to a single waiting thread. */
    @Test
    fun `toggling on releases every waiting thread, not just one`() {
        val mode = ToggleInputMode()
        val returned = CountDownLatch(2)
        val threads = List(2) { Thread { mode.waitForInput(); returned.countDown() } }
        threads.forEach { it.start() }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (threads.count { it.state == Thread.State.WAITING } < 2 && System.nanoTime() - deadline < 0) {
            Thread.yield()
        }
        assertThat(threads.count { it.state == Thread.State.WAITING }).isEqualTo(2)

        mode.setTalkingOn(true)
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /**
     * The flag is written by the button's thread and read unsynchronised by the capture thread. A
     * missed publication cannot be provoked on demand, so the `@Volatile` declaration is pinned.
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
