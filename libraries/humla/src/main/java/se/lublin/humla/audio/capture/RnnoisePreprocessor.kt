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
import se.lublin.humla.audio.native.RnnoiseNative
import kotlin.math.pow

private const val WHAT = "the rnnoise denoiser"

/**
 * xiph RNNoise as a capture stage: 48 kHz, 480-sample frames, denoised in place; its probability
 * comes from a speech model rather than a level.
 *
 * RNNoise may attenuate by at most [attenuationLimitDb]: the output is `wet + a * (dry - wet)` with
 * `a = 10^(-limit/20)`, `dry` being the input delayed by `latency` samples so it lines up with
 * rnnoise's output (a misaligned mix would comb-filter). [Float.POSITIVE_INFINITY] is RNNoise
 * alone, bit for bit, with no delay line.
 *
 * The bridge rejects frames of the wrong length with -1 (no exception on the capture thread); this
 * stage counts those in [rejectedFrames]. Allocates one `Float` box per frame.
 */
internal class RnnoisePreprocessor(
    private val api: RnnoiseApi,
    attenuationLimitDb: Float = ATTENUATION_LIMIT_DB,
    latency: Int = LATENCY_SAMPLES,
) : SingleHandleStage(
    // Checked before the native state exists, so a bad argument leaks nothing.
    run {
        require(attenuationLimitDb >= 0f) { "attenuation limit must be at least 0 dB, got $attenuationLimitDb" }
        require(latency >= 0) { "latency must not be negative, got $latency" }
        api.create()
    },
    WHAT,
) {
    /**
     * Frames rnnoise did not answer for (refused with -1, or an answer outside `[0, 1]`). The only way
     * to tell "refused" from "no opinion", since both yield null. Written under the stage lock.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    /** The dry signal's share `a`; 0 without a limit. */
    private val dryGain: Float =
        if (attenuationLimitDb == Float.POSITIVE_INFINITY) 0f else 10f.pow(-attenuationLimitDb / DB_PER_DECADE)

    private val mixing = dryGain > 0f

    /** The last `latency` input samples, a ring starting at [delayPosition]. Empty unless [mixing]. */
    private val delayLine = ShortArray(if (mixing) latency else 0)
    private var delayPosition = 0

    /** This frame's dry samples: the input from `latency` samples ago. */
    private val dry = ShortArray(if (mixing) FRAME_SIZE else 0)

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        // A short frame is refused by the bridge and must leave the delay line alone.
        val mix = mixing && frame.size >= FRAME_SIZE
        if (mix) delay(frame)
        val probability = api.processFrame(handle, frame)
        // Inverted so NaN is rejected too. -1 is not clamped to 0: that would read as "not speech".
        if (!(probability >= 0f)) {
            rejectedFrames++
            return null
        }
        if (mix) limit(frame)
        // Values above 1 would never pass a [0, 1] threshold; clamp.
        return probability.coerceAtMost(1f)
    }

    /** Pushes the frame into the delay line and takes the samples `latency` earlier into [dry]. */
    private fun delay(frame: ShortArray) {
        val line = delayLine
        if (line.isEmpty()) {
            System.arraycopy(frame, 0, dry, 0, FRAME_SIZE)
            return
        }
        var position = delayPosition
        for (i in 0 until FRAME_SIZE) {
            dry[i] = line[position]
            line[position] = frame[i]
            if (++position == line.size) position = 0
        }
        delayPosition = position
    }

    /** `wet + a * (dry - wet)`: a convex combination of two samples, so it cannot overflow. */
    private fun limit(frame: ShortArray) {
        val a = dryGain
        for (i in 0 until FRAME_SIZE) {
            val wet = frame[i].toFloat()
            frame[i] = Math.round(wet + a * (dry[i] - wet)).toShort()
        }
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    companion object {
        private const val FRAME_SIZE = RnnoiseNative.FRAME_SIZE
        private const val DB_PER_DECADE = 20f

        /**
         * rnnoise's output lags its input by two frames: the analysis window spans the previous and
         * the current frame (overlap-add gives back the previous one), and the gains are applied
         * to the spectrum one frame later still (`delayed_X` in `denoise.c`, the model's look-ahead).
         * `RnnoiseLatencyDeviceTest` measures it on the real library.
         */
        const val LATENCY_SAMPLES = 2 * FRAME_SIZE

        /**
         * How far RNNoise may pull a frame down in the app's chains; see the class KDoc. Unlimited
         * for now: `RnnoiseAttenuationLimitDeviceTest` found 24 dB lifts the gate in double talk
         * from about 44 % to 85 % of voiced near-end frames and halves echo-only false transmit on
         * average, but on a loud nonlinear echo path it opens on echo alone 12 points more often
         * than without a limit, past the 5-point bound set for shipping it.
         */
        const val ATTENUATION_LIMIT_DB = Float.POSITIVE_INFINITY
    }
}
