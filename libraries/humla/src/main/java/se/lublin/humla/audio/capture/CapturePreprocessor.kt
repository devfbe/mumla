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
 * One stage of the capture pipeline. [process] is called for every 10 ms 48 kHz mono frame (never
 * gated on the talking state) and modifies [frame] in place.
 *
 * Runs on the capture thread: must not block on anything it doesn't control and must not allocate
 * per frame (GC pauses are audible dropouts; see `CaptureThreadAllocationTest`).
 *
 * @return this stage's voice probability in [0, 1], or null when the stage has no opinion.
 */
interface CapturePreprocessor {
    fun process(frame: ShortArray): Float?

    /** Frees native resources; the instance must not be used afterwards. Idempotent. */
    fun release()
}

/**
 * Playback-side entry point of a stage that needs the far-end signal (the echo canceller). Called on
 * the playback thread once per 10 ms frame, with the frame about to be played, before the capture
 * frame containing its echo. [SingleHandleStage] guards both entry points with one lock.
 */
interface FarEndSink {
    fun analyzeReverseStream(frame: ShortArray)
}

/** A disabled stage: leaves the frame untouched. */
object NoopPreprocessor : CapturePreprocessor {
    override fun process(frame: ShortArray): Float? = null
    override fun release() = Unit
}

/**
 * Runs [stages] in order on the same frame; the probability is the last non-null one.
 *
 * Order matters and is the caller's: echo cancellation must come first. AEC3 models the echo path as
 * a slowly adapting linear filter; a noise suppressor or AGC in front of it makes that path vary per
 * frame so it never converges (and an unconverged AEC fails silently). Canonical chain: WebRTC APM
 * (high-pass, AEC, AGC) -> noise suppressor (Speex or RNNoise) -> rest. With the noise suppressor off
 * and WebRTC on, the reported "probability" is the APM's level estimate, not a speech model.
 *
 * The chain is never rebuilt in place (the list is copied); see [CaptureChain] for swapping chains.
 */
class ChainedPreprocessor(stages: List<CapturePreprocessor>) : CapturePreprocessor {
    // Array + index loop: for-in over a List allocates an Iterator per frame.
    private val stages: Array<CapturePreprocessor> = stages.toTypedArray()

    override fun process(frame: ShortArray): Float? {
        // No `?.let { probability = it }`: a lambda-captured var allocates a Ref.ObjectRef per frame.
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
