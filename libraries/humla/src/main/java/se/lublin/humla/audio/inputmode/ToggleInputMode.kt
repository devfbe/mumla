/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An input mode that depends on a toggle, such as push to talk.
 *
 * Two corrections to the Java original this replaces, both of which it had from the start:
 *
 * - [inputOn] is read by the capture thread through [shouldTransmit] and by the main thread
 *   through [isTalkingOn], and written by whichever thread owns the button. It was a plain
 *   `boolean`; it is `@Volatile` now. `ToggleInputModeTest.the talking flag is published` pins
 *   the declaration, which is all a unit test can reach -- a missed write is a JIT outcome, not a
 *   value this process can observe on demand.
 * - `lock()` ... `unlock()` without `try`/`finally` leaves the lock held for the life of the
 *   process if anything between them throws, and [waitForInput] blocks the capture thread on that
 *   very lock. [withLock] is the finally. Not pinned: nothing between the two lines can throw.
 */
class ToggleInputMode : IInputMode {
    private val toggleLock = ReentrantLock()
    private val toggleCondition = toggleLock.newCondition()

    @Volatile
    private var inputOn = false

    fun toggleTalkingOn() = setTalkingOn(!inputOn)

    fun isTalkingOn(): Boolean = inputOn

    fun setTalkingOn(talking: Boolean) {
        toggleLock.withLock {
            inputOn = talking
            toggleCondition.signalAll()
        }
    }

    override fun shouldTransmit(pcm: ShortArray, length: Int): Boolean = inputOn

    override fun waitForInput() {
        toggleLock.withLock {
            if (!inputOn) {
                Log.v(TAG, "PTT: Suspending audio input.")
                val start = System.currentTimeMillis()
                try {
                    toggleCondition.await()
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Blocking for PTT interrupted, likely due to input thread shutdown.")
                }
                Log.v(TAG, "PTT: Suspended audio input for " + (System.currentTimeMillis() - start) + "ms.")
            }
        }
    }

    private companion object {
        val TAG: String = ToggleInputMode::class.java.name
    }
}
