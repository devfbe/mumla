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

import kotlin.math.log10
import kotlin.math.sqrt

/** An input mode that sends audio if the amplitude exceeds a certain threshold. */
class ActivityInputMode(private var vadThreshold: Float) : IInputMode {
    private var vadLastDetected = 0L

    override fun shouldTransmit(pcm: ShortArray, length: Int): Boolean {
        // Use a logarithmic energy-based scale for VAD.
        var sum = 1.0f
        for (i in 0 until length) sum += pcm[i] * pcm[i].toFloat()
        val micLevel = sqrt(sum / length)
        val peakSignal = 20.0f * log10(micLevel / 32768.0f) / 96.0f
        var talking = (peakSignal + 1) >= vadThreshold

        // Record the last time where VAD was detected in order to prevent speech dropping.
        if (talking) vadLastDetected = System.nanoTime()

        talking = talking || (System.nanoTime() - vadLastDetected) < SPEECH_DELAY
        return talking
    }

    override fun waitForInput() = Unit

    fun setThreshold(threshold: Float) {
        vadThreshold = threshold
    }

    private companion object {
        /** Continue speech for 250 ms to prevent dropping. */
        const val SPEECH_DELAY = 250_000_000L
    }
}
