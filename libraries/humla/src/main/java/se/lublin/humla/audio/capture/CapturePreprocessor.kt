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

package se.lublin.humla.audio.capture

/**
 * One stage of the capture pipeline. [process] modifies every 10 ms 48 kHz mono frame in place on
 * the capture thread and returns a voice probability in [0, 1], or null for no opinion. Must not
 * block or allocate per frame (GC pauses are audible dropouts).
 */
interface CapturePreprocessor {
    fun process(frame: ShortArray): Float?

    /** Frees native resources; the instance must not be used afterwards. Idempotent. */
    fun release()
}

/**
 * Far-end entry point of the echo canceller, called on the playback thread with each 10 ms frame
 * about to be played, before the capture frame containing its echo.
 */
interface FarEndSink {
    fun analyzeReverseStream(frame: ShortArray)
}

object NoopPreprocessor : CapturePreprocessor {
    override fun process(frame: ShortArray): Float? = null
    override fun release() = Unit
}

/**
 * Runs [stages] in order on the same frame; the probability is the last non-null one. Echo
 * cancellation must come first: anything time-varying in front keeps AEC3 from converging.
 */
class ChainedPreprocessor(stages: List<CapturePreprocessor>) : CapturePreprocessor {
    // Array + index loop: for-in over a List allocates an Iterator per frame.
    private val stages: Array<CapturePreprocessor> = stages.toTypedArray()

    override fun process(frame: ShortArray): Float? {
        // No lambda: a captured var allocates a Ref.ObjectRef per frame.
        var probability: Float? = null
        for (i in this.stages.indices) {
            val stageProbability = this.stages[i].process(frame)
            if (stageProbability != null) probability = stageProbability
        }
        return probability
    }

    override fun release() {
        for (i in stages.indices) stages[i].release()
    }
}
