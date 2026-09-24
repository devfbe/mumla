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
 * xiph RNNoise as a capture stage: 48 kHz, 480-sample frames, denoised in place; its probability
 * comes from a speech model rather than a level.
 *
 * The bridge rejects frames of the wrong length with -1 (no exception on the capture thread); this
 * stage counts those in [rejectedFrames]. Allocates one `Float` box per frame.
 */
class RnnoisePreprocessor(private val api: RnnoiseApi) : SingleHandleStage(api.create(), WHAT) {
    /**
     * Frames rnnoise did not answer for (refused with -1, or an answer outside `[0, 1]`). The only way
     * to tell "refused" from "no opinion", since both yield null. Written under the stage lock.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        val probability = api.processFrame(handle, frame)
        // Inverted so NaN is rejected too. -1 is not clamped to 0: that would read as "not speech".
        if (!(probability >= 0f)) {
            rejectedFrames++
            return null
        }
        // Values above 1 would never pass a [0, 1] threshold; clamp.
        return probability.coerceAtMost(1f)
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    private companion object {
        const val WHAT = "the rnnoise denoiser"
    }
}
