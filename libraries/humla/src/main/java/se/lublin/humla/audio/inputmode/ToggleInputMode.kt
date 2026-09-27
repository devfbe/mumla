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

import se.lublin.humla.audio.capture.IInputMode

/**
 * An input mode that depends on a toggle, such as push to talk. The capture thread keeps running
 * while the toggle is off, so no stale audio is queued up for the next key press.
 *
 * [inputOn] is written by the UI thread and read by the capture thread, hence `@Volatile`.
 */
class ToggleInputMode : IInputMode {
    @Volatile
    private var inputOn = false

    val isTalkingOn: Boolean get() = inputOn

    fun setTalkingOn(talking: Boolean) {
        inputOn = talking
    }

    override fun shouldTransmit(pcm: ShortArray, length: Int, vadProbability: Float?): Boolean = inputOn
}
