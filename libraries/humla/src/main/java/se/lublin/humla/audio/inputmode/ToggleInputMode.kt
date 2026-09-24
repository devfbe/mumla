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
 * [inputOn] is written by the UI thread and read by the capture thread, hence `@Volatile`.
 *
 * [waitForInput] guards `await()` with `if`, not `while`, on purpose: a spurious wakeup just costs
 * one capture-loop turn, whereas a bare `while` would park the thread again after the shutdown
 * interrupt and never return.
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

    override fun shouldTransmit(pcm: ShortArray, length: Int, vadProbability: Float?): Boolean = inputOn

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
