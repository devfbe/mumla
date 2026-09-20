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
 * One stage of the capture pipeline (spec B2). [process] is called for EVERY 10 ms 48 kHz mono
 * frame, never gated on the talking state, and modifies [frame] in place.
 *
 * Both methods run on the capture thread, so an implementation must neither block on anything it
 * does not itself control nor allocate: a garbage collection that lands between two frames is a
 * dropout the user hears. `CaptureThreadAllocationTest` measures the skeleton at 0 bytes per
 * frame and fails if that changes.
 *
 * @return this stage's voice probability in [0, 1], or null when the stage has no opinion.
 */
interface CapturePreprocessor {
    fun process(frame: ShortArray): Float?

    /** Frees native resources; the instance must not be used afterwards. Idempotent. */
    fun release()
}

/**
 * The playback-side entry point of a stage that also needs the far-end signal, i.e. the echo
 * canceller. Called on the *playback* thread, once per 10 ms frame, with the frame that is about
 * to be played, before the capture frame that will contain its echo.
 *
 * It lives next to [CapturePreprocessor] rather than next to its only implementation because the
 * two together are what the one lock in [SingleHandleStage] has to cover -- spec §4.1, "One lock
 * across both audio streams": a stage with a reverse stream has two audio threads in it, and
 * splitting the declaration is what makes it look like it has one.
 */
interface FarEndSink {
    fun analyzeReverseStream(frame: ShortArray)
}

/** A stage that is switched off. Off is off: it runs no code on the frame at all. */
object NoopPreprocessor : CapturePreprocessor {
    override fun process(frame: ShortArray): Float? = null
    override fun release() = Unit
}

/**
 * Runs [stages] in order on the same frame; the probability is the last non-null one.
 *
 * **The order is the caller's, and it is semantics rather than taste.** Echo cancellation has to
 * come first, before any noise suppressor or gain stage:
 *
 * - AEC3 models the path from the far-end reference to the microphone as a *linear* filter and
 *   adapts it over seconds. Noise suppression and automatic gain are time-varying, signal-
 *   dependent gains. Put either of them in front of the canceller and the transfer function it is
 *   tracking changes from frame to frame for reasons the reference cannot explain, so the filter
 *   never converges -- and a canceller that has not converged does not fail loudly, it just
 *   returns the frame nearly unchanged (measured in task 2: 21.7 dB between a converged and an
 *   unconverged AEC3, `src/main/cpp/tests/test_apm.c`).
 * - The other direction costs nothing: the noise suppressor sees a signal with the echo already
 *   removed, which is what it was trained on. RNNoise's model is speech plus additive noise; echo
 *   is a delayed copy of speech, which it neither removes nor was ever asked to.
 *
 * So the canonical chain is: WebRTC APM (its own high-pass, AEC and AGC, in that fixed internal
 * order) -> noise suppressor (Speex or RNNoise) -> everything else. The probability rule follows
 * from the same order rather than being a second decision: the last stage that has an opinion
 * wins, and the noise suppressor is the stage with a real voice model, so it is also the one whose
 * opinion should survive.
 *
 * **Switching modes while the user is talking** means building a new chain, publishing it to the
 * capture thread, and only then calling [release] on the old one -- in that order. A chain is
 * never rebuilt in place, which is why this class copies the list it is given; and a stage that
 * has been released degrades to a no-op instead of touching freed native state, so a frame still
 * in flight through the old chain is harmless rather than a crash. See [SingleHandleStage].
 */
class ChainedPreprocessor(stages: List<CapturePreprocessor>) : CapturePreprocessor {
    // An Array and an index loop, not the List and a for-in: `for (s in aList)` allocates an
    // Iterator on every frame. See CaptureThreadAllocationTest.
    private val stages: Array<CapturePreprocessor> = stages.toTypedArray()

    override fun process(frame: ShortArray): Float? {
        // Not `stage.process(frame)?.let { probability = it }`: a local `var` captured by a lambda
        // becomes a Ref.ObjectRef that is allocated on every frame.
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
