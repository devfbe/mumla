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

import se.lublin.humla.audio.native.RnnoiseApi

/**
 * xiph RNNoise as one capture stage (spec B4): 48 kHz, 480-sample frames, denoised in place, and
 * the one stage in this package whose probability comes from a model trained on speech rather
 * than from a level.
 *
 * ### The frame it is handed
 *
 * `rnnoise_process_frame` reads and writes exactly 480 samples and nothing bounds it -- so the
 * bridge checks the array length and answers **-1** rather than letting it run off the end. This
 * stage does not check the length a second time: spec §4.04, two guards over one observable are
 * one guard and a lie, and the one that has to exist is the one in C. What it does instead is
 * treat -1 as what it is, a frame rnnoise never saw, and say so through [rejectedFrames].
 *
 * Spec §4.1 rules out the alternative the plan's own listing asked for -- `require(frame.size ==
 * 480)`, i.e. an `IllegalArgumentException` once per 10 ms frame on the capture thread. An
 * uncaught exception there kills the recording thread and the user goes silent with no warning,
 * which is the complaint this project started from.
 *
 * ### What it costs per frame
 *
 * One `java.lang.Float` box, 16 B, because the model answers a continuous probability and there is
 * no finite set of values to pre-box the way [SpeexPreprocessor] does with its integer percent.
 * Spec §4.1 decided to pay that (about 1.6 KB/s for this stage) rather than trade `Float?` for a
 * NaN sentinel; `CaptureThreadAllocationTest` measures it in both a cold and a hot window so the
 * number is a measurement and not an assumption. Nothing else on this path allocates.
 */
class RnnoisePreprocessor(private val api: RnnoiseApi) : SingleHandleStage(api.create(), WHAT) {
    /**
     * Frames rnnoise did not answer for: a frame shorter than 480 samples, which the bridge
     * refuses with -1, and any answer outside `[0, 1]`.
     *
     * A stage cannot report anything itself -- spec §4's channel is `AudioHandler.captureState`,
     * and only whoever built the chain holds it -- so this is the observable it leaves behind for
     * that owner, and it is the only thing that separates "rnnoise refused the frame" from "this
     * stage has no opinion", both of which are `null`. Written only from [onCaptureFrame], i.e.
     * with the one lock held; `@Volatile` so a reader on another thread sees it.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        val probability = api.processFrame(handle, frame)
        // Written inverted, over one condition rather than two, because it has to catch a NaN as
        // well as the -1: every comparison against a NaN is false, so `probability < 0f` would let
        // one straight through -- and a NaN compares false against both of task 7's thresholds,
        // i.e. it reads as permanent silence. The lower bound is deliberately not *clamped*: -1 is
        // the refusal sentinel, and coercing it to 0f would turn "rnnoise never saw this frame"
        // into "certainly not speech", which is the frame path muting the user.
        if (!(probability >= 0f)) {
            rejectedFrames++
            return null
        }
        // The upper bound is clamped rather than refused: rnnoise documents [0, 1], so anything
        // above it is a broken build rather than an input, and task 7 compares this against a
        // threshold in [0, 1] where a 1.4 would be a frame no setting can ever gate.
        return probability.coerceAtMost(1f)
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    private companion object {
        const val WHAT = "the rnnoise denoiser"
    }
}
